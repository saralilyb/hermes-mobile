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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

        val readiness = HermesWsClient::class.java.getDeclaredField("awaitingGatewayReady")
        readiness.isAccessible = true
        readiness.setBoolean(HermesWsClient, false)

        // The singleton's outbound queue survives a plain disconnect by design,
        // so clear it explicitly or a frame queued by one test leaks into the
        // next one's connection.
        outboundQueue().clear()
        pendingPromptSessions().clear()
        pendingReplayResponses().clear()
        val pendingReplyField = HermesWsClient::class.java.getDeclaredField("pendingReply")
        pendingReplyField.isAccessible = true
        pendingReplyField.setBoolean(HermesWsClient, false)
        ActiveSessionHolder.clear()

        HermesWsClient.disconnect() // Ensure it starts clean
        HermesWsClient.setAppForeground(true)
    }

    @Test
    fun capabilityIsAdvertisedOncePerSocketAndAgainAfterReconnect() {
        repeat(2) {
            val frames = java.util.concurrent.LinkedBlockingQueue<String>()
            var peer: WebSocket? = null
            mockWebServer.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: okhttp3.Response,
                        ) {
                            peer = webSocket
                            webSocket.send(READY_FRAME)
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            frames.add(text)
                        }
                    },
                ),
            )
            HermesWsClient.connect()
            val frame = Json.parseToJsonElement(requireNotNull(frames.poll(5, TimeUnit.SECONDS))).jsonObject
            assertEquals("client.capabilities", frame["method"]?.jsonPrimitive?.content)
            assertEquals("true", frame["params"]?.jsonObject?.get("server_requests")?.jsonPrimitive?.content)
            peer!!.send(READY_FRAME)
            assertNull("Duplicate gateway.ready must not advertise twice", frames.poll(200, TimeUnit.MILLISECONDS))
            HermesWsClient.disconnect()
        }
    }

    @Test
    fun unsupportedRequestsAreAnsweredWithoutViewModelAndWindowDeclinesRequireNegotiation() {
        val frames = java.util.concurrent.LinkedBlockingQueue<String>()
        val opened = CountDownLatch(1)
        var peer: WebSocket? = null
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        peer = webSocket
                        opened.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        frames.add(text)
                    }
                },
            ),
        )
        HermesWsClient.connect()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        fun request(method: String) {
            peer!!.send(
                """{"jsonrpc":"2.0","id":"srq-exact-id","method":"$method","params":{"session_id":"runtime"}}""",
            )
        }

        fun response(code: Int) {
            val frame = Json.parseToJsonElement(requireNotNull(frames.poll(5, TimeUnit.SECONDS))).jsonObject
            assertEquals("srq-exact-id", frame["id"]?.jsonPrimitive?.content)
            assertNull(frame["method"])
            assertEquals(code.toString(), frame["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
        }
        request("preview.read")
        assertNull("Unknown negotiation must not settle Desktop work", frames.poll(200, TimeUnit.MILLISECONDS))
        for (method in listOf("setup_beat", "theme.pick", "unknown.method")) {
            request(method)
            response(-32601)
        }
        peer!!.send(READY_FRAME)
        val capability = Json.parseToJsonElement(requireNotNull(frames.poll(5, TimeUnit.SECONDS))).jsonObject
        val id = capability["id"]!!.jsonPrimitive.content
        peer!!.send(
            """{"jsonrpc":"2.0","id":"$id","result":{"server_requests":["approval"],"declines_not_shown":true}}""",
        )
        for (method in listOf("preview.read", "preview.act", "terminal.read", "window.read", "tour", "theme.apply")) {
            request(method)
            response(4404)
        }
        for (method in listOf(
            "approval",
            "clarify",
            "sudo",
            "secret",
            "vault.unlock_prompt",
            "vault.save_login",
            "vault.code",
        )) {
            request(method)
            assertNull("Handled requests belong to the ViewModel", frames.poll(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun falseOrRejectedCapabilitiesNeverDeclineWindowRequestsAndResponsesStayPrivate() =
        runBlocking {
            for (result in listOf(
                "\"result\":{\"declines_not_shown\":false}",
                "\"error\":{\"code\":-32601,\"message\":\"PRIVATE PAYLOAD\"}",
            )) {
                val frames = mutableListOf<String>()
                val socket = mockk<WebSocket>(relaxed = true)
                every { socket.send(any<String>()) } answers {
                    frames.add(firstArg())
                    true
                }
                val listener = installActiveListener(socket)
                listener.onMessage(socket, READY_FRAME)
                val id = Json.parseToJsonElement(frames.single()).jsonObject["id"]!!.jsonPrimitive.content
                val published =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeoutOrNull(
                            100,
                        ) { HermesWsClient.events.first { it is WsEvent.RpcResult || it is WsEvent.RpcError } }
                    }
                listener.onMessage(socket, """{"jsonrpc":"2.0","id":"$id",$result}""")
                assertNull("Capability reply must not reach UI", published.await())
                frames.clear()
                listener.onMessage(socket, """{"jsonrpc":"2.0","id":"window","method":"preview.read","params":{}}""")
                assertTrue("False/rejected negotiation must not settle Desktop request", frames.isEmpty())
                verify(exactly = 0) { Log.d(any<String>(), match<String> { it.contains("PRIVATE PAYLOAD") }) }
            }
        }

    @Test
    fun staleCapabilityResultCannotEnableDeclinesOnReplacementSocket() {
        val oldFrames = mutableListOf<String>()
        val old = mockk<WebSocket>(relaxed = true)
        every { old.send(any<String>()) } answers {
            oldFrames.add(firstArg())
            true
        }
        val oldListener = installActiveListener(old)
        oldListener.onMessage(old, READY_FRAME)
        val id = Json.parseToJsonElement(oldFrames.single()).jsonObject["id"]!!.jsonPrimitive.content
        val fresh = mockk<WebSocket>(relaxed = true)
        val freshListener = installActiveListener(fresh)
        oldListener.onMessage(old, """{"jsonrpc":"2.0","id":"$id","result":{"declines_not_shown":true}}""")
        val field = oldListener.javaClass.getDeclaredField("declinesNotShown").apply { isAccessible = true }
        assertFalse("Stale generation must not even mutate its retired negotiation", field.getBoolean(oldListener))
        freshListener.onMessage(fresh, """{"jsonrpc":"2.0","id":"window","method":"preview.read","params":{}}""")
        verify(exactly = 0) { fresh.send(any<String>()) }
    }

    @Test
    fun approvalNotificationProvenanceRetainsSourceProfileAndStoredSession() =
        runBlocking {
            val socket = mockk<WebSocket>(relaxed = true)
            val listener = installActiveListener(socket)
            ActiveSessionHolder.set("runtime-a", "stored-a")
            val received =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5000) { HermesWsClient.sourcedEvents.first { it.event is WsEvent.ServerRequest } }
                }
            listener.onMessage(
                socket,
                """{"jsonrpc":"2.0","id":"approval-a","method":"approval","params":{"session_id":"runtime-a"}}""",
            )
            val sourced = received.await()
            assertEquals("profile-a", sourced.profileId)
            assertEquals("stored-a", sourced.storedSessionId)
            assertEquals(activeConnectionGeneration(), sourced.connectionGeneration)
        }

    private companion object {
        const val READY_FRAME = """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}"""
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
    fun testNoOpConnectKeepsBackgroundIdleCloseScheduled() {
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
        assertTrue(HermesWsClient.hasScheduledBackgroundIdleCloseForTest())

        HermesWsClient.connect()

        assertTrue(HermesWsClient.hasScheduledBackgroundIdleCloseForTest())
        HermesWsClient.expireBackgroundIdleGraceForTest()
        assertFalse(HermesWsClient.isConnected)
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
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready"}}""")
                    }

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
                        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready"}}""")
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
        val eventSocketGeneration = activeConnectionGeneration()
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
        assertEquals(eventSocketGeneration, completion.connectionGeneration)
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

    // ── Gateway server-request replies ───────────────────────────────────

    private fun serverBinding(
        requestId: String = "srq-1",
        sessionId: String = "session-a",
        profileId: String = "profile-a",
        generation: Int = activeConnectionGeneration(),
    ): ServerRequestBinding {
        ActiveSessionHolder.set(sessionId)
        return ServerRequestBinding(requestId, sessionId, profileId, generation)
    }

    @Test
    fun serverRequestResponsePreservesExactIdAndHasNoMethod() {
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } returns true
        installActiveListener(socket)

        assertTrue(
            HermesWsClient.respondToServerRequest(
                serverBinding(requestId = "srq-exact"),
                buildJsonObject { put("value", "accepted") },
            ),
        )

        verify {
            socket.send(
                match<String> { raw ->
                    val frame = Json.parseToJsonElement(raw).jsonObject
                    frame["id"]?.jsonPrimitive?.content == "srq-exact" &&
                        frame["result"]?.jsonObject?.get("value")?.jsonPrimitive?.content == "accepted" &&
                        !frame.containsKey("method")
                },
            )
        }
    }

    @Test
    fun serverRequestResponseRejectsStaleBindingsAndDisconnect() {
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } returns true
        installActiveListener(socket)
        val current = serverBinding()

        assertFalse(
            HermesWsClient.respondToServerRequest(
                current.copy(connectionGeneration = current.connectionGeneration - 1),
                buildJsonObject {},
            ),
        )
        every { AuthManager.getSelectedProfileId() } returns "profile-b"
        assertFalse(HermesWsClient.respondToServerRequest(current, buildJsonObject {}))
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        ActiveSessionHolder.set("session-b")
        assertFalse(HermesWsClient.respondToServerRequest(current, buildJsonObject {}))
        HermesWsClient.disconnect()
        assertFalse(HermesWsClient.respondToServerRequest(current, buildJsonObject {}))

        verify(exactly = 0) { socket.send(any<String>()) }
        assertTrue(outboundQueue().isEmpty())
    }

    @Test
    fun resumeReplayUsesReturnedRuntimeAndArrivesAfterResultForNullRuntime() =
        runBlocking {
            for (runtimeId in listOf("runtime-a", "stored-a")) {
                every { AuthManager.getSelectedProfileId() } returns "profile-a"
                val socket = mockk<WebSocket>(relaxed = true)
                val sent = mutableListOf<String>()
                every { socket.send(any<String>()) } answers {
                    sent.add(firstArg())
                    true
                }
                val listener = installActiveListener(socket)
                ActiveSessionHolder.set("stored-a")
                val id = HermesWsClient.send(WsMethods.SESSION_RESUME, mapOf("session_id" to "stored-a"))
                var installedRuntime: String? = null
                val received =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeoutOrNull(1_000) {
                            HermesWsClient.events.first { event ->
                                if (event is WsEvent.RpcResult && event.id == id) {
                                    installedRuntime = (event.result as Map<*, *>)["session_id"] as String
                                    ActiveSessionHolder.set(installedRuntime, "stored-a")
                                }
                                event is WsEvent.ServerRequest && event.replayed
                            }
                        }
                    }
                listener.onMessage(
                    socket,
                    """{"jsonrpc":"2.0","id":"$id","result":{"session_id":"$runtimeId","open_requests":[""" +
                        """{"id":"pending-a","method":"secret","params":{"session_id":"$runtimeId"}},""" +
                        """{"id":"foreign","method":"secret","params":{"session_id":"other"}}]}}""",
                )
                val replay = received.await() as? WsEvent.ServerRequest
                assertEquals("Replay must use the resumed runtime", "pending-a", replay?.id)
                assertEquals("Resume result must install runtime before replay", runtimeId, installedRuntime)
                assertTrue(
                    HermesWsClient.respondToServerRequest(
                        ServerRequestBinding(replay!!.id, runtimeId, "profile-a", activeConnectionGeneration()),
                        kotlinx.serialization.json.buildJsonObject {
                            put(
                                "value",
                                kotlinx.serialization.json.JsonPrimitive("fixture"),
                            )
                        },
                    ),
                )
                assertTrue(sent.last().contains("pending-a"))
            }
        }

    @Test
    fun queuedTurnWaitsForReadyAndCapabilityPrecedesIt() {
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        val socket = mockk<WebSocket>(relaxed = true)
        val sent = mutableListOf<String>()
        every { socket.send(any<String>()) } answers {
            sent.add(firstArg())
            true
        }
        val listener = installActiveListener(socket)
        outboundQueue().add("""{"jsonrpc":"2.0","id":"queued-turn","method":"prompt.submit","params":{}}""")
        listener.onOpen(
            socket,
            okhttp3.Response.Builder().request(okhttp3.Request.Builder().url("http://localhost").build())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(101).message("upgrade").build(),
        )
        assertTrue("No queued turn may precede capability registration", sent.isEmpty())
        HermesWsClient.sendMessage("session-a", "between open and ready")
        assertTrue("New sends must wait for capability registration too", sent.isEmpty())
        listener.onMessage(socket, """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready"}}""")
        assertEquals(
            listOf("client.capabilities", "prompt.submit", "prompt.submit"),
            sent.map {
                Json.parseToJsonElement(it).jsonObject["method"]!!.jsonPrimitive.content
            },
        )
        assertTrue(outboundQueue().isEmpty())
    }

    @Test
    fun rejectedCapabilityRetainsQueuedTurnWithoutSendingIt() {
        val socket = mockk<WebSocket>(relaxed = true)
        val sent = mutableListOf<String>()
        every { socket.send(any<String>()) } answers {
            sent.add(firstArg())
            false
        }
        val listener = installActiveListener(socket)
        val retryField = HermesWsClient::class.java.getDeclaredField("ticketAuthRetryUsed")
        retryField.isAccessible = true
        val ticketRetry = retryField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean
        ticketRetry.set(true)
        val turn = """{"id":"turn","method":"prompt.submit","params":{}}"""
        outboundQueue().add(turn)
        listener.onMessage(socket, """{"method":"event","params":{"type":"gateway.ready"}}""")
        assertEquals("Rejected capability must not release queued work", listOf(turn), outboundQueue().toList())
        assertEquals(1, sent.size)
        assertTrue(sent.single().contains("client.capabilities"))
        assertFalse("Gateway ready proves the ticket usable even if a later write fails", ticketRetry.get())
        assertFalse(HermesWsClient.isConnected)
    }

    @Test
    fun queuedResumeRegistersReplayBeforeItsSynchronousReply() =
        runBlocking {
            every { AuthManager.getSelectedProfileId() } returns "profile-a"
            val socket = mockk<WebSocket>(relaxed = true)
            val listener = installActiveListener(socket)
            ActiveSessionHolder.set("stored-a")
            // Model a resume queued before the gateway is ready, on this socket.
            val readiness = HermesWsClient::class.java.getDeclaredField("awaitingGatewayReady")
            readiness.isAccessible = true
            readiness.setBoolean(HermesWsClient, true)
            val id = HermesWsClient.send(WsMethods.SESSION_RESUME, mapOf("session_id" to "stored-a"))
            val received =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(
                        1_000,
                    ) { HermesWsClient.events.first { it is WsEvent.ServerRequest && it.replayed } }
                }
            every { socket.send(any<String>()) } answers {
                if (firstArg<String>().contains("session.resume")) {
                    listener.onMessage(
                        socket,
                        """{"id":"$id","result":{"session_id":"runtime-a","open_requests":[""" +
                            """{"id":"queued-pending","method":"secret","params":{"session_id":"runtime-a"}}]}}""",
                    )
                }
                true
            }
            listener.onMessage(socket, """{"method":"event","params":{"type":"gateway.ready"}}""")
            assertEquals("queued-pending", (received.await() as? WsEvent.ServerRequest)?.id)
            assertTrue(pendingReplayResponses().isEmpty())
        }

    @Test
    fun replayedOpenRequestCarriesLiveConnectionProvenance() =
        runBlocking {
            every { AuthManager.getSelectedProfileId() } returns "profile-a"
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send(any<String>()) } returns true
            val listener = installActiveListener(socket)
            val generation = activeConnectionGeneration()
            ActiveSessionHolder.set("session-a")
            val resumeId =
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    mapOf("session_id" to "session-a", "omit_messages" to true),
                )
            val received =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5_000) {
                        HermesWsClient.events.first { it is WsEvent.ServerRequest && it.replayed }
                    }
                }

            listener.onMessage(
                socket,
                """{"jsonrpc":"2.0","id":"$resumeId","result":{"session_id":"session-a","open_requests":[""" +
                    """{"id":"srq-replay","method":"secret","params":{"session_id":"session-a"}}]}}""",
            )
            val event = received.await() as WsEvent.ServerRequest

            assertEquals("srq-replay", event.id)
            assertEquals("profile-a", event.sourceProfileId)
            assertEquals(generation, event.connectionGeneration)
            assertEquals("session-a", event.params["session_id"])
        }

    @Test
    fun replayedOpenRequestsRejectUnknownUnrelatedDuplicateStaleAndMalformedResults() =
        runBlocking {
            every { AuthManager.getSelectedProfileId() } returns "profile-a"
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send(any<String>()) } returns true
            val listener = installActiveListener(socket)
            ActiveSessionHolder.set("session-a")

            suspend fun assertNoReplay(
                id: String,
                openRequest: String,
            ) {
                val received =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeoutOrNull(100) {
                            HermesWsClient.events.first { it is WsEvent.ServerRequest && it.replayed }
                        }
                    }
                listener.onMessage(
                    socket,
                    """{"jsonrpc":"2.0","id":"$id","result":{"session_id":"session-a",""" +
                        """"open_requests":[$openRequest]}}""",
                )
                assertNull(received.await())
            }

            val valid =
                """{"id":"srq","method":"secret","params":{"session_id":"session-a"}}"""
            assertNoReplay("unknown", valid)

            val unrelatedId = HermesWsClient.send(WsMethods.SESSION_LIST)
            assertNoReplay(unrelatedId, valid)

            val duplicateId =
                HermesWsClient.send(WsMethods.SESSION_RESUME, mapOf("session_id" to "session-a"))
            listener.onMessage(
                socket,
                """{"jsonrpc":"2.0","id":"$duplicateId","result":{"session_id":"session-a","open_requests":[]}}""",
            )
            assertNoReplay(duplicateId, valid)

            val staleId =
                HermesWsClient.send(WsMethods.SESSION_RESUME, mapOf("session_id" to "session-a"))
            ActiveSessionHolder.set("session-b")
            assertNoReplay(staleId, valid)
            ActiveSessionHolder.set("session-a")

            val malformedId =
                HermesWsClient.send(WsMethods.SESSION_RESUME, mapOf("session_id" to "session-a"))
            assertNoReplay(malformedId, """{"id":"","method":"secret","params":{"session_id":"session-a"}}""")
        }

    @Test
    fun replayAdmissionExistsBeforeSocketSendCanSynchronouslyRespond() =
        runBlocking {
            every { AuthManager.getSelectedProfileId() } returns "profile-a"
            val socket = mockk<WebSocket>(relaxed = true)
            val listener = installActiveListener(socket)
            ActiveSessionHolder.set("session-a")
            val received =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5_000) {
                        HermesWsClient.events.first { it is WsEvent.ServerRequest && it.replayed }
                    }
                }
            every { socket.send(any<String>()) } answers {
                val frame = Json.parseToJsonElement(invocation.args[0] as String).jsonObject
                val id = frame["id"]!!.jsonPrimitive.content
                listener.onMessage(
                    socket,
                    """{"jsonrpc":"2.0","id":"$id","result":{"session_id":"session-a","open_requests":[""" +
                        """{"id":"fast","method":"secret","params":{"session_id":"session-a"}}]}}""",
                )
                true
            }

            HermesWsClient.send(WsMethods.SESSION_RESUME, mapOf("session_id" to "session-a"))

            assertEquals("fast", (received.await() as WsEvent.ServerRequest).id)
            assertTrue(pendingReplayResponses().isEmpty())
        }

    @Test
    fun disconnectAlwaysRetiresReplayAdmissionsButPreservesOutboundQueue() {
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } returns true
        installActiveListener(socket)
        ActiveSessionHolder.set("session-a")
        HermesWsClient.send(WsMethods.SESSION_RESUME, mapOf("session_id" to "session-a"))
        assertEquals(1, pendingReplayResponses().size)
        outboundQueue().add("queued-frame")

        HermesWsClient.disconnect(clearPendingMessages = false)

        assertTrue(pendingReplayResponses().isEmpty())
        assertEquals(1, outboundQueue().size)

        val replacementListener = installActiveListener(socket)
        HermesWsClient.send(WsMethods.SESSION_RESUME, mapOf("session_id" to "session-a"))
        assertEquals(1, pendingReplayResponses().size)
        replacementListener.onFailure(socket, java.io.IOException("test failure"), null)
        assertTrue(pendingReplayResponses().isEmpty())
    }

    @Test
    fun heterogeneousOpenRequestsSkipMalformedEntriesAndReplayValidSibling() =
        runBlocking {
            every { AuthManager.getSelectedProfileId() } returns "profile-a"
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send(any<String>()) } returns true
            val listener = installActiveListener(socket)
            ActiveSessionHolder.set("session-a")
            val resumeId = HermesWsClient.send(WsMethods.SESSION_RESUME, mapOf("session_id" to "session-a"))
            val received =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5_000) {
                        HermesWsClient.events.first { it is WsEvent.ServerRequest && it.replayed }
                    }
                }

            listener.onMessage(
                socket,
                """{"jsonrpc":"2.0","id":"$resumeId","result":{"session_id":"session-a",""" +
                    """"open_requests":[null,7,"bad",""" +
                    """{"id":"valid","method":"secret","params":{"session_id":"session-a"}}]}}""",
            )

            assertEquals("valid", (received.await() as WsEvent.ServerRequest).id)
        }

    @Test
    fun pingUsesAwaitedGatewayRequest() =
        runBlocking {
            val socket = mockk<WebSocket>(relaxed = true)
            val sent = mutableListOf<String>()
            every { socket.send(any<String>()) } answers {
                sent += invocation.args[0] as String
                true
            }
            val listener = installActiveListener(socket)

            val ping = async(start = CoroutineStart.UNDISPATCHED) { HermesWsClient.ping(timeoutMs = 1_000) }
            val frame = Json.parseToJsonElement(sent.single()).jsonObject
            assertEquals(WsMethods.GATEWAY_PING, frame["method"]?.jsonPrimitive?.content)
            listener.onMessage(
                socket,
                """{"jsonrpc":"2.0","id":"${frame["id"]!!.jsonPrimitive.content}","result":{"ok":true}}""",
            )

            assertTrue(ping.await() >= 0L)
            assertTrue(HermesWsClient.isHealthy)
        }

    @Test
    fun cancellingPingRemovesOwnedRequestAndTimeout() =
        runBlocking {
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send(any<String>()) } returns true
            installActiveListener(socket)

            val ping = launch(start = CoroutineStart.UNDISPATCHED) { HermesWsClient.ping(timeoutMs = 60_000) }
            val pending = pendingCalls().values.single()!!
            val timeoutJob = pendingTimeoutJob(pending)

            ping.cancelAndJoin()

            assertTrue("cancelled ping must remove its pending request", pendingCalls().isEmpty())
            assertTrue("cancelled ping must cancel its timeout", timeoutJob.isCancelled)
        }

    // ── Privileged sends (hermes-agent d90045be2 / a77692158) ────────────
    //
    // A privileged frame is never queued, never retried, and never replayed
    // onto a replacement connection: it goes out on the exact socket
    // generation and profile that dispatched the request, or it fails.

    private fun binding(
        requestId: String = "req-1",
        sessionId: String = "session-a",
        profileId: String = "profile-a",
        generation: Int = activeConnectionGeneration(),
    ): PrivilegedRequestBinding {
        ActiveSessionHolder.set(sessionId)
        return PrivilegedRequestBinding(requestId, sessionId, profileId, generation)
    }

    /** Connect and return a queue of every frame the server received. */
    private fun connectCapturingFrames(): java.util.Queue<String> {
        val frames: java.util.Queue<String> = java.util.concurrent.ConcurrentLinkedQueue()
        val openLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        openLatch.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        frames.add(text)
                    }
                },
            ),
        )
        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertTrue(openLatch.await(5, TimeUnit.SECONDS))
        return frames
    }

    private fun awaitFrame(
        frames: java.util.Queue<String>,
        needle: String,
    ): String? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            frames.firstOrNull { it.contains(needle) }?.let { return it }
            Thread.sleep(10)
        }
        return null
    }

    @Test
    fun privilegedRequestSendsTheBoundRequestOnTheDispatchingSocket() {
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        val frames = connectCapturingFrames()

        HermesWsClient.privilegedRequest(
            method = WsMethods.SUDO_RESPOND,
            binding = binding(requestId = "req-1", sessionId = "session-a"),
            params = mapOf("password" to "hunter2"),
        )

        val frame = awaitFrame(frames, WsMethods.SUDO_RESPOND)
        assertNotNull("privileged frame was not sent", frame)
        assertTrue(frame!!.contains("\"request_id\":\"req-1\""))
        assertTrue(frame.contains("\"session_id\":\"session-a\""))
        assertTrue(frame.contains("\"password\":\"hunter2\""))
    }

    /** Caller params can never displace the binding the gateway will match on. */
    @Test
    fun privilegedRequestParamsCannotOverrideTheBinding() {
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        val frames = connectCapturingFrames()

        HermesWsClient.privilegedRequest(
            method = WsMethods.APPROVAL_RESPOND,
            binding = binding(requestId = "real-req", sessionId = "real-session"),
            params = mapOf("choice" to "once", "request_id" to "spoofed", "session_id" to "spoofed"),
        )

        val frame = awaitFrame(frames, WsMethods.APPROVAL_RESPOND)
        assertNotNull(frame)
        assertFalse(frame!!.contains("spoofed"))
        assertTrue(frame.contains("\"request_id\":\"real-req\""))
        assertTrue(frame.contains("\"session_id\":\"real-session\""))
    }

    @Test
    fun privilegedRequestRefusesAStaleSocketGeneration() {
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        val frames = connectCapturingFrames()

        val deferred =
            HermesWsClient.privilegedRequest(
                method = WsMethods.APPROVAL_RESPOND,
                binding = binding(generation = connectionGeneration() - 1),
                params = mapOf("choice" to "once"),
            )

        assertTrue(deferred.isCompleted)
        assertNotNull(deferred.getCompletionExceptionOrNull())
        assertNull(awaitFrame(frames, WsMethods.APPROVAL_RESPOND))
        assertTrue("a refused privileged frame is never queued", outboundQueue().isEmpty())
    }

    @Test
    fun privilegedRequestRefusesAnotherProfile() {
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        val frames = connectCapturingFrames()
        every { AuthManager.getSelectedProfileId() } returns "profile-b"

        val deferred =
            HermesWsClient.privilegedRequest(
                method = WsMethods.SECRET_RESPOND,
                binding = binding(),
                params = mapOf("value" to "super-secret-token"),
            )

        assertNotNull(deferred.getCompletionExceptionOrNull())
        assertNull(awaitFrame(frames, WsMethods.SECRET_RESPOND))
        assertTrue(outboundQueue().isEmpty())
    }

    @Test
    fun privilegedRequestsRefuseAReplacedRuntimeSessionWithoutWritingOrQueueing() {
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } returns true
        installActiveListener(socket)

        listOf(
            WsMethods.APPROVAL_RESPOND to mapOf("choice" to "once"),
            WsMethods.SUDO_RESPOND to mapOf("password" to "hunter2"),
            WsMethods.SECRET_RESPOND to mapOf("value" to "secret"),
        ).forEachIndexed { index, (method, params) ->
            val binding = binding(requestId = "request-$index", sessionId = "runtime-session")
            ActiveSessionHolder.set("replacement-session")

            val deferred = HermesWsClient.privilegedRequest(method, binding, params)

            assertNotNull(deferred.getCompletionExceptionOrNull())
        }
        val blankSession = binding(requestId = "request-blank", sessionId = "")
        assertNotNull(
            HermesWsClient
                .privilegedRequest(WsMethods.APPROVAL_RESPOND, blankSession, mapOf("choice" to "once"))
                .getCompletionExceptionOrNull(),
        )

        verify(exactly = 0) { socket.send(any<String>()) }
        assertTrue(outboundQueue().isEmpty())
        assertTrue(pendingCalls().isEmpty())
    }

    /** Secrets are a foreground-only surface. */
    @Test
    fun privilegedRequestRefusesWhileBackgrounded() {
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        val frames = connectCapturingFrames()
        HermesWsClient.setAppForeground(false)

        val deferred =
            HermesWsClient.privilegedRequest(
                method = WsMethods.SUDO_RESPOND,
                binding = binding(),
                params = mapOf("password" to "hunter2"),
            )

        assertNotNull(deferred.getCompletionExceptionOrNull())
        assertNull(awaitFrame(frames, WsMethods.SUDO_RESPOND))
        assertTrue(outboundQueue().isEmpty())
        HermesWsClient.setAppForeground(true)
    }

    @Test
    fun privilegedRequestIsNeverQueuedWhileDisconnected() {
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        HermesWsClient.disconnect()

        val deferred =
            HermesWsClient.privilegedRequest(
                method = WsMethods.SUDO_CANCEL,
                binding = binding(),
            )

        assertNotNull(deferred.getCompletionExceptionOrNull())
        assertTrue(outboundQueue().isEmpty())
    }

    /** The deferred resolves on the gateway's own ack, not on a successful write. */
    @Test
    fun privilegedRequestCompletesOnlyOnTheGatewayAck() {
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        var serverSocket: WebSocket? = null
        val openLatch = CountDownLatch(1)
        val frames: java.util.Queue<String> = java.util.concurrent.ConcurrentLinkedQueue()
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverSocket = webSocket
                        openLatch.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        frames.add(text)
                    }
                },
            ),
        )
        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertTrue(openLatch.await(5, TimeUnit.SECONDS))

        val deferred =
            HermesWsClient.privilegedRequest(
                method = WsMethods.APPROVAL_RESPOND,
                binding = binding(),
                params = mapOf("choice" to "once"),
            )

        val frame = awaitFrame(frames, WsMethods.APPROVAL_RESPOND)
        assertNotNull(frame)
        assertFalse("a written frame is not an acknowledgement", deferred.isCompleted)

        val rpcId = Regex("\"id\":\"(\\d+)\"").find(frame!!)!!.groupValues[1]
        serverSocket!!.send("""{"jsonrpc":"2.0","id":"$rpcId","result":{"status":"ok"}}""")

        runBlocking { withTimeout(5000) { deferred.await() } }
        assertTrue(deferred.isCompleted)
    }

    /** Provenance comes from the delivering connection — requests *and* expiries. */
    @Test
    fun privilegedFramesAreStampedWithTheDispatchingProfileAndGeneration() {
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        val socket = mockk<WebSocket>(relaxed = true)
        val listener = installActiveListener(socket)
        val generation = activeConnectionGeneration()

        val payloads =
            listOf(
                """{"jsonrpc":"2.0","method":"event","params":{"type":"approval.request",""" +
                    """"session_id":"session-a","payload":{"request_id":"r1","timeout_seconds":300}}}""",
                """{"jsonrpc":"2.0","method":"event","params":{"type":"sudo.expire",""" +
                    """"session_id":"session-a","payload":{"request_id":"r1"}}}""",
                """{"jsonrpc":"2.0","method":"event","params":{"type":"secret.expire",""" +
                    """"session_id":"session-a","payload":{"request_id":"r2"}}}""",
                """{"jsonrpc":"2.0","method":"event","params":{"type":"clarify.expire",""" +
                    """"session_id":"session-a","payload":{"request_id":"r3"}}}""",
            )

        val received =
            runBlocking {
                withTimeout(5000) {
                    val collected = Collections.synchronizedList(mutableListOf<WsEvent>())
                    val job =
                        launch {
                            HermesWsClient.events.collect { collected.add(it) }
                        }
                    while (collected.size < payloads.size) {
                        payloads.forEach { listener.onMessage(socket, it) }
                        kotlinx.coroutines.delay(20)
                    }
                    job.cancel()
                    collected.toList()
                }
            }

        val approval = received.filterIsInstance<WsEvent.ApprovalRequest>().first()
        assertEquals("profile-a", approval.sourceProfileId)
        assertEquals(generation, approval.connectionGeneration)

        val sudoExpire = received.filterIsInstance<WsEvent.SudoExpire>().first()
        assertEquals("profile-a", sudoExpire.sourceProfileId)
        assertEquals(generation, sudoExpire.connectionGeneration)

        val secretExpire = received.filterIsInstance<WsEvent.SecretExpire>().first()
        assertEquals("profile-a", secretExpire.sourceProfileId)
        assertEquals(generation, secretExpire.connectionGeneration)

        val clarifyExpire = received.filterIsInstance<WsEvent.ClarifyExpire>().first()
        assertEquals("profile-a", clarifyExpire.sourceProfileId)
        assertEquals(generation, clarifyExpire.connectionGeneration)
    }

    @Test
    fun privilegedRequestFromAReplacedSocketIsRefusedWithinTheSameReconnectGeneration() {
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        val originalSocket = mockk<WebSocket>(relaxed = true)
        val originalListener = installActiveListener(originalSocket)
        val received =
            runBlocking {
                withTimeout(5000) {
                    launch {
                        originalListener.onMessage(
                            originalSocket,
                            """{"jsonrpc":"2.0","method":"event","params":{"type":"sudo.request",""" +
                                """"session_id":"session-a","payload":{"request_id":"req-1"}}}""",
                        )
                    }
                    HermesWsClient.events.first { it is WsEvent.SudoRequest } as WsEvent.SudoRequest
                }
            }

        val replacementSocket = mockk<WebSocket>(relaxed = true)
        every { replacementSocket.send(any<String>()) } returns true
        installActiveListener(replacementSocket)
        val deferred =
            HermesWsClient.privilegedRequest(
                WsMethods.SUDO_RESPOND,
                PrivilegedRequestBinding(
                    received.requestId,
                    requireNotNull(received.sessionId),
                    requireNotNull(received.sourceProfileId),
                    requireNotNull(received.connectionGeneration),
                ),
                mapOf("password" to "hunter2"),
            )

        assertNotNull(deferred.getCompletionExceptionOrNull())
        verify(exactly = 0) { replacementSocket.send(any<String>()) }
    }

    @Test
    fun testConnectionBoundRequestRefusesWrongProfileBeforeSocketWrite() {
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } returns true
        installActiveListener(socket)

        assertEquals(null, HermesWsClient.connectionBinding("profile-b"))
        verify(exactly = 0) { socket.send(any<String>()) }
        assertTrue(pendingCalls().isEmpty())
    }

    @Test
    fun testConnectionBoundRequestSendsOnExactLiveSocketAndCorrelatesResponse() =
        runBlocking {
            val socket = mockk<WebSocket>(relaxed = true)
            var sentPayload = ""
            every { socket.send(any<String>()) } answers {
                sentPayload = firstArg()
                true
            }
            every { AuthManager.getSelectedProfileId() } returns "profile-a"
            ActiveSessionHolder.set("runtime-session")
            val listener = installActiveListener(socket)
            val binding = requireNotNull(HermesWsClient.connectionBinding("profile-a"))

            val deferred =
                HermesWsClient.requestForConnection(
                    binding,
                    "test.method",
                    mapOf("session_id" to "runtime-session"),
                )
            val id = Regex("\\\"id\\\":\\\"([^\\\"]+)\\\"").find(sentPayload)!!.groupValues[1]
            listener.onMessage(socket, """{"jsonrpc":"2.0","id":"$id","result":{"ok":true}}""")

            assertEquals(mapOf("ok" to true), deferred.await())
            verify(exactly = 1) { socket.send(any<String>()) }
            assertTrue(outboundQueue().isEmpty())
            assertTrue(pendingCalls().isEmpty())
        }

    @Test
    fun testProfileBoundRequestDoesNotRequireActiveChatSession() =
        runBlocking {
            val socket = mockk<WebSocket>(relaxed = true)
            var sentPayload = ""
            every { socket.send(any<String>()) } answers {
                sentPayload = firstArg()
                true
            }
            every { AuthManager.getSelectedProfileId() } returns "profile-a"
            ActiveSessionHolder.clear()
            val listener = installActiveListener(socket)
            val binding = requireNotNull(HermesWsClient.connectionBinding("profile-a"))

            val deferred =
                HermesWsClient.requestForProfileConnection(
                    binding,
                    WsMethods.SESSION_ACTIVE_LIST,
                )
            val id = Regex("\\\"id\\\":\\\"([^\\\"]+)\\\"").find(sentPayload)!!.groupValues[1]
            listener.onMessage(socket, """{"jsonrpc":"2.0","id":"$id","result":{"sessions":[]}}""")

            assertEquals(mapOf("sessions" to emptyList<Any>()), deferred.await())
            verify(exactly = 1) { socket.send(any<String>()) }
            assertTrue(pendingCalls().isEmpty())
        }

    @Test
    fun testCancelledAwaitedProfileRequestRemovesPendingCall() =
        runBlocking {
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send(any<String>()) } returns true
            every { AuthManager.getSelectedProfileId() } returns "profile-a"
            installActiveListener(socket)
            val binding = requireNotNull(HermesWsClient.connectionBinding("profile-a"))

            val request =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    HermesWsClient.requestForProfileConnectionAwaited(
                        binding,
                        WsMethods.SESSION_ACTIVE_LIST,
                    )
                }
            assertEquals(1, pendingCalls().size)

            request.cancelAndJoin()

            assertTrue(pendingCalls().isEmpty())
        }

    @Test
    fun testProfileBoundRequestRefusesReplacementSocketBeforeWrite() =
        runBlocking {
            supervisorScope {
                val originalSocket = mockk<WebSocket>(relaxed = true)
                every { originalSocket.send(any<String>()) } returns true
                installActiveListener(originalSocket)
                val binding = requireNotNull(HermesWsClient.connectionBinding("profile-a"))
                val request =
                    async(start = CoroutineStart.LAZY) {
                        HermesWsClient
                            .requestForProfileConnection(binding, WsMethods.SESSION_ACTIVE_LIST)
                            .await()
                    }

                incrementConnectionGeneration()
                val replacementSocket = mockk<WebSocket>(relaxed = true)
                every { replacementSocket.send(any<String>()) } returns true
                installActiveListener(replacementSocket)

                val failure = runCatching { request.await() }.exceptionOrNull()

                assertTrue(failure is HermesWsClient.HermesRpcException)
                verify(exactly = 0) { originalSocket.send(any<String>()) }
                verify(exactly = 0) { replacementSocket.send(any<String>()) }
                assertTrue(pendingCalls().isEmpty())
            }
        }

    @Test
    fun testConnectionBoundRequestRejectsInBackgroundWithoutSocketWrite() {
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } returns true
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        ActiveSessionHolder.set("runtime-session")
        installActiveListener(socket)
        val binding = requireNotNull(HermesWsClient.connectionBinding("profile-a"))
        HermesWsClient.setAppForeground(false)

        val deferred =
            HermesWsClient.requestForConnection(
                binding,
                "test.method",
                mapOf("session_id" to "runtime-session"),
            )

        assertNotNull(deferred.getCompletionExceptionOrNull())
        verify(exactly = 0) { socket.send(any<String>()) }
        assertTrue(outboundQueue().isEmpty())
        assertTrue(pendingCalls().isEmpty())
    }

    @Test
    fun testConnectionBoundRequestSendFailureNeverQueuesOrReplays() {
        val failedSocket = mockk<WebSocket>(relaxed = true)
        every { failedSocket.send(any<String>()) } returns false
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        ActiveSessionHolder.set("runtime-session")
        installActiveListener(failedSocket)
        val binding = requireNotNull(HermesWsClient.connectionBinding("profile-a"))

        val deferred =
            HermesWsClient.requestForConnection(
                binding,
                "test.method",
                mapOf("session_id" to "runtime-session"),
            )

        assertNotNull(deferred.getCompletionExceptionOrNull())
        assertTrue(outboundQueue().isEmpty())
        assertTrue(pendingCalls().isEmpty())

        val replacementSocket = mockk<WebSocket>(relaxed = true)
        every { replacementSocket.send(any<String>()) } returns true
        installActiveListener(replacementSocket)
        verify(exactly = 0) { replacementSocket.send(any<String>()) }
    }

    @Test
    fun testConnectionBoundRequestRejectsStaleRuntimeSession() {
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } returns true
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        ActiveSessionHolder.set("replacement-session")
        installActiveListener(socket)
        val binding = requireNotNull(HermesWsClient.connectionBinding("profile-a"))

        val deferred =
            HermesWsClient.requestForConnection(
                binding,
                "test.method",
                mapOf("session_id" to "runtime-session"),
            )

        assertNotNull(deferred.getCompletionExceptionOrNull())
        verify(exactly = 0) { socket.send(any<String>()) }
        assertTrue(outboundQueue().isEmpty())
        assertTrue(pendingCalls().isEmpty())
    }

    @Test
    fun testConnectionBoundRequestRefusesReplacementSocketBeforeCoroutineWrite() =
        runBlocking {
            supervisorScope {
                val originalSocket = mockk<WebSocket>(relaxed = true)
                every { originalSocket.send(any<String>()) } returns true
                installActiveListener(originalSocket)
                val binding = requireNotNull(HermesWsClient.connectionBinding("profile-a"))
                val request =
                    async(start = CoroutineStart.LAZY) {
                        HermesWsClient.requestForConnection(binding, "test.method").await()
                    }

                incrementConnectionGeneration()
                val replacementSocket = mockk<WebSocket>(relaxed = true)
                every { replacementSocket.send(any<String>()) } returns true
                installActiveListener(replacementSocket)

                val failure = runCatching { request.await() }.exceptionOrNull()

                assertTrue(failure is HermesWsClient.HermesRpcException)
                verify(exactly = 0) { originalSocket.send(any<String>()) }
                verify(exactly = 0) { replacementSocket.send(any<String>()) }
                assertTrue(pendingCalls().isEmpty())
            }
        }

    @Test
    fun testClarifyResponseIsRefusedAfterBackgrounding() {
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } returns true
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        installActiveListener(socket)
        HermesWsClient.setAppForeground(false)

        assertFalse(
            HermesWsClient.respondToClarify(
                sessionId = "runtime-session",
                clarifyRequestId = "clarify-1",
                questionId = "q0",
                answer = "answer",
                sourceProfileId = "profile-a",
                sourceConnectionGeneration = activeConnectionGeneration(),
            ),
        )
        verify(exactly = 0) { socket.send(any<String>()) }
    }

    @Test
    fun clarifyResponseRefusesAReplacedRuntimeSessionWithoutWritingOrQueueing() {
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } returns true
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        installActiveListener(socket)
        listOf("runtime-session" to "replacement-session", "" to "").forEach { (boundSession, activeSession) ->
            ActiveSessionHolder.set(activeSession)
            assertFalse(
                HermesWsClient.respondToClarify(
                    sessionId = boundSession,
                    clarifyRequestId = "clarify-1",
                    questionId = null,
                    answer = "answer",
                    sourceProfileId = "profile-a",
                    sourceConnectionGeneration = activeConnectionGeneration(),
                ),
            )
        }
        verify(exactly = 0) { socket.send(any<String>()) }
        assertTrue(outboundQueue().isEmpty())
    }

    @Test
    fun clarifyResponseRejectsBlankRequestIdentityWithoutWritingOrQueueing() {
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } returns true
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        installActiveListener(socket)
        ActiveSessionHolder.set("runtime-session")

        listOf("", "   ").forEach { requestId ->
            assertFalse(
                HermesWsClient.respondToClarify(
                    sessionId = "runtime-session",
                    clarifyRequestId = requestId,
                    questionId = null,
                    answer = "answer",
                    sourceProfileId = "profile-a",
                    sourceConnectionGeneration = activeConnectionGeneration(),
                ),
            )
        }
        verify(exactly = 0) { socket.send(any<String>()) }
        assertTrue(outboundQueue().isEmpty())
    }

    private fun outboundQueue(): java.util.Queue<String> {
        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return queueField.get(HermesWsClient) as java.util.Queue<String>
    }

    private fun pendingCalls(): MutableMap<String, *> {
        val field = HermesWsClient::class.java.getDeclaredField("pendingCalls")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(HermesWsClient) as MutableMap<String, *>
    }

    private fun pendingTimeoutJob(pendingCall: Any): Job {
        val field = pendingCall.javaClass.getDeclaredField("timeoutJob")
        field.isAccessible = true
        return field.get(pendingCall) as Job
    }

    private fun pendingPromptSessions(): MutableMap<*, *> {
        val field = HermesWsClient::class.java.getDeclaredField("pendingPromptSessions")
        field.isAccessible = true
        return field.get(HermesWsClient) as MutableMap<*, *>
    }

    private fun pendingReplayResponses(): MutableMap<String, *> {
        val field = HermesWsClient::class.java.getDeclaredField("pendingReplayResponses")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(HermesWsClient) as MutableMap<String, *>
    }

    private fun connectionGeneration(): Int {
        val field = HermesWsClient::class.java.getDeclaredField("connectionGeneration")
        field.isAccessible = true
        return (field.get(HermesWsClient) as AtomicInteger).get()
    }

    private fun incrementConnectionGeneration() {
        val field = HermesWsClient::class.java.getDeclaredField("connectionGeneration")
        field.isAccessible = true
        (field.get(HermesWsClient) as AtomicInteger).incrementAndGet()
    }

    private fun activeConnectionGeneration(): Int {
        val field = HermesWsClient::class.java.getDeclaredField("activeConnectionGeneration")
        field.isAccessible = true
        return field.getInt(HermesWsClient)
    }

    private fun installActiveListener(socket: WebSocket): WebSocketListener {
        val socketGenerationField = HermesWsClient::class.java.getDeclaredField("socketGeneration")
        socketGenerationField.isAccessible = true
        val eventSocketGeneration = (socketGenerationField.get(HermesWsClient) as AtomicInteger).incrementAndGet()

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

        val profileField = HermesWsClient::class.java.getDeclaredField("activeConnectionProfileId")
        profileField.isAccessible = true
        profileField.set(HermesWsClient, "profile-a")
        val generationField = HermesWsClient::class.java.getDeclaredField("activeConnectionGeneration")
        generationField.isAccessible = true
        generationField.setInt(HermesWsClient, eventSocketGeneration)

        val listenerClass =
            HermesWsClient::class.java.declaredClasses.first {
                it.simpleName == "WsListenerImpl"
            }
        val constructor =
            listenerClass.getDeclaredConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        constructor.isAccessible = true
        return constructor.newInstance(
            "profile-a",
            connectionGeneration(),
            eventSocketGeneration,
        ) as WebSocketListener
    }

    @Test
    fun clarifyResponseFromAReplacedSocketIsRefusedWithinTheSameReconnectGeneration() {
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        val originalSocket = mockk<WebSocket>(relaxed = true)
        val originalListener = installActiveListener(originalSocket)
        val logicalGeneration = connectionGeneration()
        val received =
            runBlocking {
                withTimeout(5000) {
                    launch {
                        originalListener.onMessage(
                            originalSocket,
                            """{"jsonrpc":"2.0","method":"event","params":{"type":"clarify.request",""" +
                                """"session_id":"runtime-session","payload":{"request_id":"clarify-1",""" +
                                """"prompt":"Question?"}}}""",
                        )
                    }
                    HermesWsClient.events.first { it is WsEvent.ClarifyRequest } as WsEvent.ClarifyRequest
                }
            }

        val replacementSocket = mockk<WebSocket>(relaxed = true)
        every { replacementSocket.send(any<String>()) } returns true
        installActiveListener(replacementSocket)

        assertEquals(logicalGeneration, connectionGeneration())
        assertFalse(
            HermesWsClient.respondToClarify(
                sessionId = requireNotNull(received.sessionId),
                clarifyRequestId = requireNotNull(received.clarifyId),
                questionId = received.questionId,
                answer = "stale",
                sourceProfileId = requireNotNull(received.sourceProfileId),
                sourceConnectionGeneration = requireNotNull(received.connectionGeneration),
            ),
        )
        verify(exactly = 0) { replacementSocket.send(any<String>()) }
    }

    @Test
    fun boundPromptRefusesProfileSwitchImmediatelyBeforeWriteWithoutQueueing() {
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } returns true
        installActiveListener(socket)
        ActiveSessionHolder.set("runtime-session")
        val binding = requireNotNull(HermesWsClient.connectionBinding("profile-a"))

        every { AuthManager.getSelectedProfileId() } returns "profile-b"

        assertFalse(HermesWsClient.sendMessageForConnection(binding, "runtime-session", "stale"))
        verify(exactly = 0) { socket.send(any<String>()) }
        assertTrue(outboundQueue().isEmpty())
    }

    @Test
    fun boundPromptRefusesSocketReplacementImmediatelyBeforeWriteWithoutQueueing() {
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        val original = mockk<WebSocket>(relaxed = true)
        installActiveListener(original)
        ActiveSessionHolder.set("runtime-session")
        val binding = requireNotNull(HermesWsClient.connectionBinding("profile-a"))
        val replacement = mockk<WebSocket>(relaxed = true)
        every { replacement.send(any<String>()) } returns true
        installActiveListener(replacement)

        assertFalse(HermesWsClient.sendMessageForConnection(binding, "runtime-session", "stale"))
        verify(exactly = 0) { original.send(any<String>()) }
        verify(exactly = 0) { replacement.send(any<String>()) }
        assertTrue(outboundQueue().isEmpty())
    }

    @Test
    fun testClarifyResponsePreservesAliasesAndRejectsStaleGeneration() {
        val socket = mockk<WebSocket>(relaxed = true)
        var sentPayload = ""
        every { socket.send(any<String>()) } answers {
            sentPayload = firstArg()
            true
        }
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        installActiveListener(socket)
        val generation = activeConnectionGeneration()
        ActiveSessionHolder.set("runtime-session")

        assertTrue(
            HermesWsClient.respondToClarify(
                sessionId = "runtime-session",
                clarifyRequestId = "clarify-1",
                questionId = null,
                answer = "answer",
                sourceProfileId = "profile-a",
                sourceConnectionGeneration = generation,
            ),
        )
        assertTrue(sentPayload.contains("\"clarify_id\":\"clarify-1\""))
        assertTrue(sentPayload.contains("\"request_id\":\"clarify-1\""))
        assertTrue(sentPayload.contains("\"response\":\"answer\""))
        assertTrue(sentPayload.contains("\"answer\":\"answer\""))
        assertFalse(sentPayload.contains("question_id"))

        assertFalse(
            HermesWsClient.respondToClarify(
                sessionId = "runtime-session",
                clarifyRequestId = "clarify-1",
                questionId = "q0",
                answer = "stale",
                sourceProfileId = "profile-a",
                sourceConnectionGeneration = generation - 1,
            ),
        )
        verify(exactly = 1) { socket.send(any<String>()) }
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
                        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready"}}""")
                        serverWebSocket = webSocket
                        serverLatch.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        if (text.contains("client.capabilities")) return
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
                        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready"}}""")
                        serverWebSocket = webSocket
                        serverLatch.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        if (text.contains("client.capabilities")) return
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

        val profileField = HermesWsClient::class.java.getDeclaredField("activeConnectionProfileId")
        profileField.isAccessible = true
        profileField.set(HermesWsClient, "profile-a")
        val generationField = HermesWsClient::class.java.getDeclaredField("activeConnectionGeneration")
        generationField.isAccessible = true
        generationField.setInt(HermesWsClient, activeConnectionGeneration())

        val listenerClass =
            HermesWsClient::class.java.declaredClasses.first {
                it.simpleName == "WsListenerImpl"
            }
        val constructor =
            listenerClass.getDeclaredConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        constructor.isAccessible = true
        val staleListener =
            constructor.newInstance(
                "profile-a",
                connectionGeneration(),
                activeConnectionGeneration(),
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
                        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready"}}""")
                        serverLatch.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        if (text.contains("client.capabilities")) return
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
                        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready"}}""")
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
