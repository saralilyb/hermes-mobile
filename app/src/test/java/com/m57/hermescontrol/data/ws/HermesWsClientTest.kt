// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class HermesWsClientTest {
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.isLoggable(any<String>(), any<Int>()) } returns false

        mockWebServer = MockWebServer()
        mockWebServer.start()

        mockkObject(AuthManager)
        every { AuthManager.wsUrl() } returns mockWebServer.url("/").toString().replace("http://", "ws://")
        every { AuthManager.wsUrlWithCredential(any(), any()) } returns
            mockWebServer.url("/").toString().replace("http://", "ws://")
        every { AuthManager.isAutoReconnect() } returns false
        every { AuthManager.getSessionCookie() } returns null
        // Non-gated by default (token mode) so the gated ticket path is exercised
        // only by the explicit gated-mode test below.
        every { AuthManager.serverStore } returns
            mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                every { it.getLatestState() } returns
                    com.m57.hermescontrol.data.config.ServerStoreState()
            }

        // Issue #470: clients are built through OkHttpProvider, which now
        // resolves the shared CookieManager.cookieJar. Inject a fake jar so
        // the WS stack can build its OkHttp clients without app context.
        CookieManager.setJarForTest(buildFakePersistentCookieJar())

        // Reset state
        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val statusField = HermesWsClient::class.java.getDeclaredField("_connectionStatus")
        statusField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (statusField.get(HermesWsClient) as MutableStateFlow<ConnectionStatus>).value = ConnectionStatus.DISCONNECTED

        // The singleton's outbound queue survives a plain disconnect by design,
        // so clear it explicitly or a frame queued by one test leaks into the
        // next one's connection.
        outboundQueue().clear()
        pendingPromptSessions().clear()
        val pendingReplyField = HermesWsClient::class.java.getDeclaredField("pendingReply")
        pendingReplyField.isAccessible = true
        pendingReplyField.setBoolean(HermesWsClient, false)
        ActiveSessionHolder.clear()

        HermesWsClient.disconnect() // Ensure it starts clean
        HermesWsClient.setAppForeground(true)
    }

    @Test
    fun testBackgroundWithoutPendingWorkWaitsForGraceBeforeDisconnecting() {
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}),
        )
        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }

        HermesWsClient.setAppForeground(false)

        assertTrue(HermesWsClient.isConnected)
        assertTrue(HermesWsClient.hasScheduledBackgroundIdleCloseForTest())

        HermesWsClient.expireBackgroundIdleGraceForTest()

        assertFalse(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)
        assertFalse(HermesWsClient.shouldReconnectAfterNetworkRestore(autoReconnect = true))
    }

    @Test
    fun testForegroundReturnCancelsBackgroundIdleClose() {
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}),
        )
        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }

        HermesWsClient.setAppForeground(false)
        assertTrue(HermesWsClient.hasScheduledBackgroundIdleCloseForTest())

        HermesWsClient.setAppForeground(true)

        assertFalse(HermesWsClient.hasScheduledBackgroundIdleCloseForTest())
        HermesWsClient.expireBackgroundIdleGraceForTest()
        assertTrue(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)
    }

    @Test
    fun testPromptAfterBackgroundIdleCloseReconnectsAndFlushes() {
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}),
        )
        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        HermesWsClient.setAppForeground(false)
        HermesWsClient.expireBackgroundIdleGraceForTest()
        runBlocking {
            withTimeout(5000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.DISCONNECTED }
            }
        }

        val received = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        if (text.contains(WsMethods.PROMPT_SUBMIT)) received.countDown()
                    }
                },
            ),
        )

        HermesWsClient.sendMessage("runtime-session", "background prompt")

        assertTrue("Background prompt did not reconnect and flush", received.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun testCredentialClearingDisconnectCancelsIdleRecoveryGeneration() {
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}),
        )
        HermesWsClient.connect()
        runBlocking {
            withTimeout(5000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }
        }
        HermesWsClient.setAppForeground(false)
        HermesWsClient.expireBackgroundIdleGraceForTest()
        runBlocking {
            withTimeout(5000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.DISCONNECTED }
            }
        }

        HermesWsClient.sendMessage("runtime-session", "background prompt")
        HermesWsClient.disconnect(clearPendingMessages = true)
        Thread.sleep(300)

        assertFalse(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)
        assertTrue(outboundQueue().isEmpty())
        assertTrue(pendingPromptSessions().isEmpty())
    }

    @Test
    fun testBackgroundPendingPromptStaysConnectedUntilCompletionWithProvenance() {
        lateinit var serverSocket: WebSocket
        val connectedLatch = CountDownLatch(1)
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverSocket = webSocket
                        connectedLatch.countDown()
                    }
                },
            ),
        )
        HermesWsClient.connect()
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS))
        runBlocking {
            withTimeout(5000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }
        }
        ActiveSessionHolder.set("runtime-session", "stored-session")
        HermesWsClient.sendMessage("runtime-session", "hello")
        HermesWsClient.setAppForeground(false)
        assertTrue(HermesWsClient.isConnected)

        val completion =
            runBlocking {
                withTimeout(5000) {
                    launch {
                        serverSocket.send(
                            """
                            {"jsonrpc":"2.0","method":"event","params":{"type":"message.complete",
                            "payload":{"text":"done","session_id":"runtime-session"}}}
                            """.trimIndent(),
                        )
                    }
                    HermesWsClient.sourcedEvents.first { it.event is WsEvent.MessageComplete }
                }
            }

        assertTrue(HermesWsClient.isConnected)
        assertTrue(HermesWsClient.hasScheduledBackgroundIdleCloseForTest())
        HermesWsClient.expireBackgroundIdleGraceForTest()

        runBlocking {
            withTimeout(5000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.DISCONNECTED }
            }
        }
        assertEquals("profile-a", completion.profileId)
        assertEquals("stored-session", completion.storedSessionId)
        assertEquals(connectionGeneration() - 1, completion.connectionGeneration)
    }

    @Test
    fun testCompletionKeepsSocketOpenWhileAnotherSessionPromptIsPending() {
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } returns true
        val listener = installActiveListener(socket)
        HermesWsClient.sendMessage("session-a", "first")
        HermesWsClient.sendMessage("session-b", "second")
        HermesWsClient.setAppForeground(false)

        listener.onMessage(
            socket,
            """
            {"jsonrpc":"2.0","method":"event","params":{"type":"message.complete",
            "payload":{"text":"done","session_id":"session-a"}}}
            """.trimIndent(),
        )

        assertTrue(HermesWsClient.pendingReply)
        assertTrue(HermesWsClient.isConnected)
    }

    private fun outboundQueue(): java.util.Queue<*> {
        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        return queueField.get(HermesWsClient) as java.util.Queue<*>
    }

    private fun pendingCalls(): MutableMap<String, *> {
        val field = HermesWsClient::class.java.getDeclaredField("pendingCalls")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(HermesWsClient) as MutableMap<String, *>
    }

    private fun pendingPromptSessions(): MutableMap<*, *> {
        val field = HermesWsClient::class.java.getDeclaredField("pendingPromptSessions")
        field.isAccessible = true
        return field.get(HermesWsClient) as MutableMap<*, *>
    }

    private fun connectionGeneration(): Int {
        val field = HermesWsClient::class.java.getDeclaredField("connectionGeneration")
        field.isAccessible = true
        return (field.get(HermesWsClient) as AtomicInteger).get()
    }

    private fun installActiveListener(socket: WebSocket): WebSocketListener {
        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, socket)

        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)

        val statusField = HermesWsClient::class.java.getDeclaredField("_connectionStatus")
        statusField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val status = statusField.get(HermesWsClient) as MutableStateFlow<ConnectionStatus>
        status.value = ConnectionStatus.CONNECTED

        val listenerClass =
            HermesWsClient::class.java.declaredClasses.first {
                it.simpleName == "WsListenerImpl"
            }
        val constructor =
            listenerClass.getDeclaredConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
            )
        constructor.isAccessible = true
        return constructor.newInstance(
            "profile-a",
            connectionGeneration(),
        ) as WebSocketListener
    }

    private fun awaitOutboundQueueEmpty(timeoutMs: Long = 1_000): Boolean {
        val queue = outboundQueue()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (queue.isNotEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        return queue.isEmpty()
    }

    @After
    fun tearDown() {
        HermesWsClient.disconnect()
        ActiveSessionHolder.clear()
        // Wait a bit to allow internal OkHttp coroutines to clean up before shutting down MockWebServer
        // Increased from 100ms for OkHttp 5.x — needs more time for the WS close handshake
        Thread.sleep(500)
        try {
            mockWebServer.shutdown()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        unmockkAll()
    }

    @Test
    fun testConnectAndSend() {
        var serverWebSocket: WebSocket? = null
        val serverLatch = CountDownLatch(1)
        val messageLatch = CountDownLatch(1)
        var receivedMessage: String? = null

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverWebSocket = webSocket
                        serverLatch.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        receivedMessage = text
                        messageLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertTrue("Server failed to accept connection", serverLatch.await(5, TimeUnit.SECONDS))
        assertTrue(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)

        // Send a message
        val id = HermesWsClient.send("test_method", mapOf("param" to "value"))

        // Verify message received by server
        assertTrue("Message not received", messageLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(receivedMessage)
        val msg = receivedMessage ?: ""
        assertTrue(msg.contains("test_method"))
        assertTrue(msg.contains("value"))
        assertTrue(msg.contains(id))
    }

    @Test
    fun testReceiveMessage() {
        var serverWebSocket: WebSocket? = null
        val serverLatch = CountDownLatch(1)
        every { AuthManager.getSelectedProfileId() } returns "profile-a"

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverWebSocket = webSocket
                        serverLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS))
        every { AuthManager.getSelectedProfileId() } returns "profile-b"

        // Server sends a message to client
        val jsonResponse =
            """
            {
                "jsonrpc": "2.0",
                "id": "1",
                "result": "success"
            }
            """.trimIndent()

        val receivedEvent =
            runBlocking {
                withTimeout(5000) {
                    launch { serverWebSocket?.send(jsonResponse) }
                    HermesWsClient.sourcedEvents.first {
                        it.event is WsEvent.RpcResult
                    }
                }
            }

        assertEquals("profile-a", receivedEvent.profileId)
        assertTrue(receivedEvent.event is WsEvent.RpcResult)
        assertEquals("1", (receivedEvent.event as WsEvent.RpcResult).id)
    }

    @Test
    fun testDisconnect() {
        val serverLatch = CountDownLatch(1)
        val closedLatch = CountDownLatch(1)

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverLatch.countDown()
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        closedLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS))

        HermesWsClient.disconnect()
        assertFalse(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)

        // Verify server received close frame
        assertTrue(closedLatch.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun testDisconnectRejectsPendingRpcCalls() {
        val serverLatch = CountDownLatch(1)

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS))

        val deferred = HermesWsClient.request("test.method")
        assertEquals(1, pendingCalls().size)

        HermesWsClient.disconnect(clearPendingMessages = true)

        assertTrue(deferred.isCompleted)
        try {
            runBlocking { deferred.await() }
            fail("Expected HermesRpcException")
        } catch (e: HermesWsClient.HermesRpcException) {
            assertTrue(e.message?.contains("cancelled") == true)
        }
        assertEquals(0, pendingCalls().size)
    }

    @Test
    fun testConcurrentRequestsAndRejectAllPendingNeverOrphanDeferreds() {
        val start = CountDownLatch(1)
        val keepRejecting = AtomicBoolean(true)
        val deferreds =
            Collections.synchronizedList(
                mutableListOf<CompletableDeferred<Any?>>(),
            )
        val requesters = Executors.newFixedThreadPool(4)
        repeat(4) {
            requesters.execute {
                start.await()
                repeat(2_500) {
                    deferreds +=
                        HermesWsClient.request(
                            method = "test.concurrent",
                            timeoutMs = 60_000,
                        )
                }
            }
        }
        val rejecter =
            Thread {
                start.await()
                while (keepRejecting.get()) {
                    HermesWsClient.rejectAllPending()
                }
            }

        rejecter.start()
        start.countDown()
        requesters.shutdown()
        assertTrue(requesters.awaitTermination(30, TimeUnit.SECONDS))
        keepRejecting.set(false)
        rejecter.join(5_000)
        assertFalse("rejecter thread did not stop", rejecter.isAlive)

        HermesWsClient.rejectAllPending()

        val orphanCount = deferreds.count { !it.isCompleted }
        assertEquals("Every deferred must complete after the final drain", 0, orphanCount)
    }

    @Test
    fun testActiveSocketCloseRejectsPendingRpcCallsWithoutCollector() {
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } returns true
        val listener = installActiveListener(socket)
        val deferred = HermesWsClient.request("test.close")

        listener.onClosed(socket, 1000, "test close")

        assertTrue(deferred.isCompleted)
        try {
            runBlocking { deferred.await() }
            fail("Expected HermesRpcException")
        } catch (e: HermesWsClient.HermesRpcException) {
            assertTrue(e.message?.contains("cancelled") == true)
        }
    }

    @Test
    fun testActiveSocketFailureRejectsPendingRpcCallsWithoutCollector() {
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } returns true
        val listener = installActiveListener(socket)
        val deferred = HermesWsClient.request("test.failure")

        listener.onFailure(socket, java.io.IOException("test failure"), null)

        assertTrue(deferred.isCompleted)
        try {
            runBlocking { deferred.await() }
            fail("Expected HermesRpcException")
        } catch (e: HermesWsClient.HermesRpcException) {
            assertTrue(e.message?.contains("cancelled") == true)
        }
    }

    @Test
    fun testDisconnectInvalidatesSocketSetupAlreadyInFlight() {
        val ticketServer = MockWebServer()
        ticketServer.start()
        val releaseOldSetup = CountDownLatch(1)
        val oldSetupReachedUrlBuild = CountDownLatch(1)
        val oldSocketAttempted = CountDownLatch(1)
        val ticketCount = AtomicInteger(0)

        try {
            every { AuthManager.serverStore } returns
                mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                    every { it.getLatestState() } returns
                        com.m57.hermescontrol.data.config.ServerStoreState(
                            wsAuthParam = "ticket",
                        )
                }
            every { AuthManager.endpointForBuild() } returns
                ServerEndpoint.parse(
                    ticketServer.url("/").toString(),
                    CleartextPolicy.ALLOW_WITH_WARNING,
                )
            every { AuthManager.getSelectedProfileId() } returns "profile-a"
            every { AuthManager.wsUrlWithCredential(any(), any()) } answers {
                val ticket = firstArg<String>()
                if (ticket == "old-ticket") {
                    oldSetupReachedUrlBuild.countDown()
                    releaseOldSetup.await(5, TimeUnit.SECONDS)
                }
                mockWebServer.url("/?ticket=$ticket").toString().replace("http://", "ws://")
            }
            ticketServer.dispatcher =
                object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                        val ticket =
                            if (ticketCount.incrementAndGet() == 1) {
                                "old-ticket"
                            } else {
                                "new-ticket"
                            }
                        return MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"ticket":"$ticket"}""")
                    }
                }
            mockWebServer.dispatcher =
                object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                        if (request.requestUrl?.queryParameter("ticket") == "old-ticket") {
                            oldSocketAttempted.countDown()
                        }
                        return MockResponse().withWebSocketUpgrade(
                            object : WebSocketListener() {},
                        )
                    }
                }

            val oldConnect = Thread { HermesWsClient.connect() }
            oldConnect.start()
            assertTrue(oldSetupReachedUrlBuild.await(5, TimeUnit.SECONDS))

            HermesWsClient.disconnect(clearPendingMessages = true)
            val replacementConnect = Thread { HermesWsClient.connect() }
            replacementConnect.start()
            replacementConnect.join(5_000)
            assertFalse("replacement connect did not finish", replacementConnect.isAlive)
            runBlocking {
                withTimeout(5_000) {
                    HermesWsClient.connectionStatus.first {
                        it == ConnectionStatus.CONNECTED
                    }
                }
            }

            releaseOldSetup.countDown()
            oldConnect.join(5_000)
            assertFalse("old connect did not finish", oldConnect.isAlive)
            assertFalse(
                "Superseded setup opened a socket",
                oldSocketAttempted.await(1, TimeUnit.SECONDS),
            )
        } finally {
            releaseOldSetup.countDown()
            ticketServer.shutdown()
        }
    }

    @Test
    fun testSendMessage() {
        var serverWebSocket: WebSocket? = null
        val serverLatch = CountDownLatch(1)
        val messageLatch = CountDownLatch(1)
        var receivedMessage: String? = null

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverWebSocket = webSocket
                        serverLatch.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        receivedMessage = text
                        messageLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertTrue("Server failed to accept connection", serverLatch.await(5, TimeUnit.SECONDS))

        // Use the convenience method
        HermesWsClient.sendMessage("test_session_id", "Hello Hermes!")

        // Verify message received by server
        assertTrue("Message not received", messageLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(receivedMessage)
        val msg = receivedMessage ?: ""
        assertTrue(msg.contains(WsMethods.PROMPT_SUBMIT))
        assertTrue(msg.contains("test_session_id"))
        assertTrue(msg.contains("Hello Hermes!"))
        assertFalse(msg.contains("\"queued\""))
    }

    @Test
    fun testAutoReconnect() {
        every { AuthManager.isAutoReconnect() } returns true

        var serverSocket1: WebSocket? = null
        var serverSocket2: WebSocket? = null

        val connect1Latch = CountDownLatch(1)
        val connect2Latch = CountDownLatch(1)

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverSocket1 = webSocket
                        connect1Latch.countDown()
                    }
                },
            ),
        )

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverSocket2 = webSocket
                        connect2Latch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()

        assertTrue("Failed initial connection", connect1Latch.await(5, TimeUnit.SECONDS))
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)

        // Force server to close socket 1 to trigger reconnect
        serverSocket1?.close(1001, "Server shutting down")

        // Wait for status to become RECONNECTING
        runBlocking {
            withTimeout(
                5000,
            ) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.RECONNECTING } }
        }

        // The client should now attempt to reconnect after initial backoff (1000ms)
        // Wait for the second connection to hit the server
        assertTrue("Failed to reconnect", connect2Latch.await(6, TimeUnit.SECONDS))
    }

    // ── TEST-10: WS reconnect state recovery ────────────────────────────

    @Test
    fun testBackoffResetsOnSuccessfulConnect() {
        every { AuthManager.isAutoReconnect() } returns true

        val serverLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }

        // After connect, backoff should be back to initial
        val backoffField = HermesWsClient::class.java.getDeclaredField("currentBackoff")
        backoffField.isAccessible = true
        assertEquals(
            "Backoff should reset to initial after successful connect",
            1000L,
            backoffField.getLong(HermesWsClient),
        )
    }

    @Test
    fun testIntentionalClosePreventsReconnect() {
        every { AuthManager.isAutoReconnect() } returns true

        val serverLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }

        // Disconnect — this sets intentionalClose = true and cancels reconnect
        HermesWsClient.disconnect()

        assertFalse(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)
    }

    @Test
    fun testDoubleConnect_ignoresSecondCallWhenConnected() {
        val serverLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertTrue(HermesWsClient.isConnected)

        // Second connect call should be a no-op
        HermesWsClient.connect()
        assertTrue(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)
    }

    @Test
    fun testStatusTransitionOnConnect() {
        val serverLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverLatch.countDown()
                    }
                },
            ),
        )

        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)

        HermesWsClient.connect()

        // After connect(), status should be CONNECTING
        var status: ConnectionStatus
        val deadline = System.currentTimeMillis() + 2000
        do {
            status = HermesWsClient.connectionStatus.value
            if (status == ConnectionStatus.CONNECTING) break
            Thread.sleep(10)
        } while (System.currentTimeMillis() < deadline)
        assertEquals(ConnectionStatus.CONNECTING, status)

        // Wait for actual connection
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)
    }

    @Test
    fun testDisconnectWhileReconnecting_transitionsToDisconnected() {
        every { AuthManager.isAutoReconnect() } returns true

        val connectLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        connectLatch.countDown()
                    }
                },
            ),
        )

        // Enqueue a second response for reconnect attempt
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        // No-op — should be cancelled
                    }
                },
            ),
        )

        HermesWsClient.connect()
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS))
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }

        // Disconnect (sets intentionalClose) — after this, reconnect should be prevented
        HermesWsClient.disconnect()
        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)
        assertFalse(HermesWsClient.isConnected)
    }

    @Test
    fun testStaleTerminalCallbacksDoNotClobberFreshConnection() {
        val activeSocket = mockk<WebSocket>(relaxed = true)
        val staleSocket = mockk<WebSocket>(relaxed = true)

        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, activeSocket)

        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)

        val statusField = HermesWsClient::class.java.getDeclaredField("_connectionStatus")
        statusField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val status = statusField.get(HermesWsClient) as MutableStateFlow<ConnectionStatus>
        status.value = ConnectionStatus.CONNECTED

        val listenerClass =
            HermesWsClient::class.java.declaredClasses.first {
                it.simpleName == "WsListenerImpl"
            }
        val constructor =
            listenerClass.getDeclaredConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
            )
        constructor.isAccessible = true
        val staleListener =
            constructor.newInstance(
                "profile-a",
                connectionGeneration(),
            ) as WebSocketListener

        staleListener.onClosed(staleSocket, 4401, "auth: ticket_invalid")
        staleListener.onFailure(staleSocket, java.io.IOException("late failure"), null)

        assertTrue(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)
        assertTrue(socketField.get(HermesWsClient) === activeSocket)
    }

    // ── Issue #635: gated-mode WS ticket fetch must not be blocked by a
    // missing bare-name session cookie (HTTPS deployments prefix it with
    // __Host- / __Secure-). ────────────────────────────────────────────────

    @Test
    fun testGatedMode_attemptsTicketFetchWithoutBareCookie() {
        // Force gated mode (ws auth via ticket, not loopback token).
        every { AuthManager.serverStore } returns
            mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                every { it.getLatestState() } returns
                    com.m57.hermescontrol.data.config.ServerStoreState(wsAuthParam = "ticket")
            }
        // No bare-name session cookie present (the prefixed one is server-side).
        every { AuthManager.getSessionCookie() } returns null

        // Separate server for the ticket endpoint so its queue can't interleave
        // with the WebSocket upgrade on the main mockWebServer.
        val ticketServer = MockWebServer()
        ticketServer.start()
        every { AuthManager.endpointForBuild() } returns
            ServerEndpoint.parse(
                ticketServer.url("/").toString(),
                CleartextPolicy.ALLOW_WITH_WARNING,
            )
        ticketServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ticket":"refreshed-ticket"}"""),
        )

        val connectLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        connectLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()

        // Before the fix, a null bare cookie short-circuited to AUTH_EXPIRED and
        // the ticket endpoint was NEVER called. After the fix it is attempted,
        // so the connection reaches CONNECTED.
        assertTrue(
            "Gated WS ticket fetch should be attempted even without a bare cookie",
            connectLatch.await(5, TimeUnit.SECONDS),
        )
        // The server-side onOpen latch fires a hair before the client receives
        // the 101 handshake and WsListenerImpl sets CONNECTED — await the real
        // status transition (as every other connect test does) instead of a
        // racy read that can observe CONNECTING.
        runBlocking {
            withTimeout(5000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }
        }
        assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)

        ticketServer.shutdown()
    }

    @Test
    fun testGatedMode_transientTicketFailureReconnectsWithoutRelogin() {
        every { AuthManager.serverStore } returns
            mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                every { it.getLatestState() } returns
                    com.m57.hermescontrol.data.config.ServerStoreState(wsAuthParam = "ticket")
            }
        every { AuthManager.isAutoReconnect() } returns true

        val ticketServer = MockWebServer()
        ticketServer.start()
        try {
            every { AuthManager.endpointForBuild() } returns
                ServerEndpoint.parse(
                    ticketServer.url("/").toString(),
                    CleartextPolicy.ALLOW_WITH_WARNING,
                )
            ticketServer.enqueue(MockResponse().setResponseCode(503))
            ticketServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"ticket":"refreshed-ticket"}"""),
            )

            mockWebServer.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {},
                ),
            )

            HermesWsClient.connect()

            runBlocking {
                withTimeout(6_000) {
                    HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED }
                }
            }
            assertEquals(2, ticketServer.requestCount)
        } finally {
            ticketServer.shutdown()
        }
    }

    @Test
    fun testGatedMode_unauthorizedTicketRequiresRelogin() {
        every { AuthManager.serverStore } returns
            mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                every { it.getLatestState() } returns
                    com.m57.hermescontrol.data.config.ServerStoreState(wsAuthParam = "ticket")
            }
        every { AuthManager.isAutoReconnect() } returns true

        val ticketServer = MockWebServer()
        ticketServer.start()
        try {
            every { AuthManager.endpointForBuild() } returns
                ServerEndpoint.parse(
                    ticketServer.url("/").toString(),
                    CleartextPolicy.ALLOW_WITH_WARNING,
                )
            ticketServer.enqueue(MockResponse().setResponseCode(401))

            HermesWsClient.connect()

            runBlocking {
                withTimeout(5_000) {
                    HermesWsClient.connectionStatus.first { it == ConnectionStatus.AUTH_EXPIRED }
                }
            }
            Thread.sleep(1_500)
            assertEquals(1, ticketServer.requestCount)
        } finally {
            ticketServer.shutdown()
        }
    }

    @Test
    fun testGatedMode_rejectedWebSocketTicketRetriesOnceWithFreshTicket() {
        every { AuthManager.serverStore } returns
            mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                every { it.getLatestState() } returns
                    com.m57.hermescontrol.data.config.ServerStoreState(wsAuthParam = "ticket")
            }

        val ticketServer = MockWebServer()
        ticketServer.start()
        try {
            every { AuthManager.endpointForBuild() } returns
                ServerEndpoint.parse(
                    ticketServer.url("/").toString(),
                    CleartextPolicy.ALLOW_WITH_WARNING,
                )
            repeat(2) { index ->
                ticketServer.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"ticket":"fresh-ticket-$index"}"""),
                )
            }

            mockWebServer.enqueue(MockResponse().setResponseCode(401))
            mockWebServer.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}),
            )

            HermesWsClient.connect()

            runBlocking {
                withTimeout(5_000) {
                    HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED }
                }
            }
            assertEquals(2, ticketServer.requestCount)
            verify(exactly = 0) { AuthManager.setToken(any()) }
        } finally {
            ticketServer.shutdown()
        }
    }

    @Test
    fun testGatedMode_webSocketClose4401RetriesOnceWithFreshTicket() {
        every { AuthManager.serverStore } returns
            mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                every { it.getLatestState() } returns
                    com.m57.hermescontrol.data.config.ServerStoreState(wsAuthParam = "ticket")
            }

        val ticketServer = MockWebServer()
        ticketServer.start()
        try {
            every { AuthManager.endpointForBuild() } returns
                ServerEndpoint.parse(
                    ticketServer.url("/").toString(),
                    CleartextPolicy.ALLOW_WITH_WARNING,
                )
            repeat(2) { index ->
                ticketServer.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"ticket":"close-ticket-$index"}"""),
                )
            }

            mockWebServer.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: okhttp3.Response,
                        ) {
                            webSocket.close(4401, "auth: ticket_invalid")
                        }
                    },
                ),
            )
            val secondOpen = CountDownLatch(1)
            mockWebServer.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: okhttp3.Response,
                        ) {
                            secondOpen.countDown()
                        }
                    },
                ),
            )

            HermesWsClient.connect()

            assertTrue(
                "4401 should trigger one fresh-ticket WebSocket handshake",
                secondOpen.await(5, TimeUnit.SECONDS),
            )
            assertEquals(2, ticketServer.requestCount)
        } finally {
            ticketServer.shutdown()
        }
    }

    @Test
    fun testGatedMode_secondRejectedWebSocketTicketRequiresRelogin() {
        every { AuthManager.serverStore } returns
            mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                every { it.getLatestState() } returns
                    com.m57.hermescontrol.data.config.ServerStoreState(wsAuthParam = "ticket")
            }

        val ticketServer = MockWebServer()
        ticketServer.start()
        try {
            every { AuthManager.endpointForBuild() } returns
                ServerEndpoint.parse(
                    ticketServer.url("/").toString(),
                    CleartextPolicy.ALLOW_WITH_WARNING,
                )
            repeat(2) { index ->
                ticketServer.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"ticket":"rejected-ticket-$index"}"""),
                )
                mockWebServer.enqueue(MockResponse().setResponseCode(401))
            }

            HermesWsClient.connect()

            runBlocking {
                withTimeout(5_000) {
                    HermesWsClient.connectionStatus.first { it == ConnectionStatus.AUTH_EXPIRED }
                }
            }
            assertEquals(2, ticketServer.requestCount)
        } finally {
            ticketServer.shutdown()
        }
    }

    // ── Outbound queue is credential-scoped ─────────────────────────────
    // A queued frame was composed under whatever credentials were live when
    // the user typed it. Flushing it after a logout or a fresh login would
    // deliver it into whichever profile's session opens next.

    @Test
    fun testPlainDisconnect_retainsQueuedFrames() {
        HermesWsClient.send("queued_method", mapOf("param" to "value"))
        assertEquals(1, outboundQueue().size)

        HermesWsClient.disconnect()

        assertEquals(
            "A plain disconnect must keep the queue — offline sends are the reason it exists",
            1,
            outboundQueue().size,
        )
    }

    @Test
    fun testClearingDisconnect_dropsQueuedFrames() {
        HermesWsClient.send("queued_method", mapOf("param" to "value"))
        assertEquals(1, outboundQueue().size)

        HermesWsClient.disconnect(clearPendingMessages = true)

        assertTrue("Credential-clearing disconnect must empty the queue", outboundQueue().isEmpty())
    }

    @Test
    fun testSendRacingClearingDisconnect_isNotQueued() {
        HermesWsClient.disconnect(clearPendingMessages = true)

        HermesWsClient.send("late_method", mapOf("param" to "value"))

        assertTrue(
            "A send racing a credential-clearing disconnect must not repopulate the queue",
            outboundQueue().isEmpty(),
        )
    }

    @Test
    fun testQueuedFrameFlushesOnNextConnect() {
        val serverLatch = CountDownLatch(1)
        val messageLatch = CountDownLatch(1)
        var receivedMessage: String? = null

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverLatch.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        receivedMessage = text
                        messageLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.send("queued_method", mapOf("param" to "value"))
        assertEquals(1, outboundQueue().size)

        HermesWsClient.connect()

        assertTrue("Server failed to accept connection", serverLatch.await(5, TimeUnit.SECONDS))
        assertTrue("Queued message was not flushed on reconnect", messageLatch.await(5, TimeUnit.SECONDS))
        assertTrue((receivedMessage ?: "").contains("queued_method"))
        assertTrue("A flushed frame must leave the queue", awaitOutboundQueueEmpty())
    }

    @Test
    fun testFrameQueuedUnderPreviousCredentialsNeverReachesNextSession() {
        val serverLatch = CountDownLatch(1)
        val freshFrameLatch = CountDownLatch(1)
        val received = mutableListOf<String>()

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverLatch.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        synchronized(received) { received.add(text) }
                        if (text.contains("new_profile_prompt")) freshFrameLatch.countDown()
                    }
                },
            ),
        )

        // Compose a frame while offline under the first identity, then log out.
        HermesWsClient.send("previous_profile_prompt", mapOf("text" to "value"))
        HermesWsClient.disconnect(clearPendingMessages = true)

        // Sign in again — possibly against a different profile.
        HermesWsClient.connect()
        assertTrue("Server failed to accept connection", serverLatch.await(5, TimeUnit.SECONDS))
        HermesWsClient.send("new_profile_prompt", mapOf("text" to "value"))
        assertTrue("New-session frame was not delivered", freshFrameLatch.await(5, TimeUnit.SECONDS))
        // Give a leaked flush time to land before asserting its absence.
        Thread.sleep(300)

        val frames = synchronized(received) { received.toList() }
        assertFalse(
            "Frame queued under the previous credentials must never reach the new session",
            frames.any { it.contains("previous_profile_prompt") },
        )
    }
}
