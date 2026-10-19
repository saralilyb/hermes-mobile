package com.m57.hermescontrol.data.remote

import android.util.Log
import com.m57.hermescontrol.data.config.ServerStore
import com.m57.hermescontrol.data.config.ServerStoreState
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.AuthSessionState
import com.m57.hermescontrol.data.model.ConfigUpdateRequest
import com.m57.hermescontrol.data.remote.NetworkError.AuthExpired
import com.m57.hermescontrol.data.remote.NetworkResult.Failure
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Disposable protocol peer, not a production dashboard. The peer's status/config bodies,
 * allowlisted profile, login cookie, and single-use tickets are synthetic; only the real
 * Retrofit/OkHttp transport, cookie jar, auth recovery, and WebSocket client are exercised.
 * No production credentials, storage, or server are involved.
 */
class AuthenticatedDisposableFixtureTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoint: ServerEndpoint
    private lateinit var jar: PersistentCookieJar
    private val tickets = AtomicInteger()
    private val issued = ConcurrentHashMap.newKeySet<String>()
    private val consumed = ConcurrentHashMap.newKeySet<String>()
    private val accepted = LinkedBlockingQueue<String>()
    private val frames = LinkedBlockingQueue<String>()
    private val peerSockets = LinkedBlockingQueue<WebSocket>()
    private val requests = LinkedBlockingQueue<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.offer(request)
                    val path = request.requestUrl!!.encodedPath
                    if (path == "/dashboard/api/auth/login") {
                        return MockResponse().setBody("{}")
                            .addHeader("Set-Cookie", "session=fixture; Path=/dashboard/; HttpOnly")
                    }
                    if (request.getHeader(
                            "Cookie",
                        ) != "session=fixture" || request.getHeader("Authorization") != null
                    ) {
                        return MockResponse().setResponseCode(401)
                    }
                    return when (path) {
                        "/dashboard/api/auth/ws-ticket" -> {
                            val ticket = "ticket-${tickets.incrementAndGet()}"
                            issued.add(ticket)
                            MockResponse().setBody("{\"ticket\":\"$ticket\"}")
                        }
                        "/dashboard/api/ws" -> {
                            val ticket = request.requestUrl!!.queryParameter("ticket")
                            if (ticket == null || !issued.contains(ticket) || !consumed.add(ticket)) {
                                MockResponse().setResponseCode(401)
                            } else {
                                accepted.offer(ticket)
                                MockResponse().withWebSocketUpgrade(
                                    object : WebSocketListener() {
                                        override fun onOpen(
                                            webSocket: WebSocket,
                                            response: okhttp3.Response,
                                        ) {
                                            webSocket.send(
                                                """{"method":"event","params":{"type":"gateway.ready"}}""",
                                            )
                                            peerSockets.offer(webSocket)
                                        }

                                        override fun onMessage(
                                            webSocket: WebSocket,
                                            text: String,
                                        ) {
                                            frames.offer(text)
                                        }
                                    },
                                )
                            }
                        }
                        "/dashboard/api/status" ->
                            MockResponse().setBody(
                                "{\"version\":\"fixture\",\"auth_required\":true}",
                            )
                        "/dashboard/api/config" -> {
                            // No implicit default: only the allowlisted, caller-supplied profile can read or write.
                            if (request.requestUrl!!.queryParameter("profile") != "default" ||
                                request.requestUrl!!.queryParameterNames != setOf("profile")
                            ) {
                                MockResponse().setResponseCode(403)
                                    .setBody("{\"error\":\"explicit recognized profile required\"}")
                            } else if (request.method == "GET") {
                                MockResponse().setBody("{\"fixture\":true}")
                            } else if (request.method == "PUT") {
                                MockResponse().setBody("{}")
                            } else {
                                MockResponse().setResponseCode(405)
                            }
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        server.start()
        endpoint = ServerEndpoint.parse(server.url("/dashboard/").toString(), CleartextPolicy.ALLOW_WITH_WARNING)
        CookieManager.setJarForTest(buildFakePersistentCookieJar())
        // OkHttpProvider clients are process-wide lazy singletons; a prior test may have
        // initialized them with another jar. Exercise the jar actually bound to transport.
        jar = OkHttpProvider.base.cookieJar as PersistentCookieJar
        jar.clearAll()
        CookieManager.setJarForTest(jar)
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.isLoggable(any<String>(), any<Int>()) } returns false
        mockkObject(AuthManager)
        val store = mockk<ServerStore>()
        every { store.getLatestState() } returns ServerStoreState(wsAuthParam = "ticket")
        every { AuthManager.serverStore } returns store
        every { AuthManager.isGatedMode() } returns true
        every { AuthManager.endpointForBuild() } returns endpoint
        every { AuthManager.wsUrl() } returns endpoint.webSocketUrl("ticket", "unused")
        every { AuthManager.wsUrlWithCredential(any(), any()) } answers {
            endpoint.webSocketUrl(secondArg(), firstArg())
        }
        every { AuthManager.getSelectedProfileId() } returns "local-id"
        every { AuthManager.getToken() } returns "stale-bearer"
        every { AuthManager.isAutoReconnect() } returns false
        every { AuthManager.getSessionCookie() } returns null
        HermesWsClient.disconnect(clearPendingMessages = true)
        HermesWsClient.setAppForeground(true)
        AuthSessionState.markAuthenticated()
        ApiClient.rebuild()
    }

    @After
    fun tearDown() {
        HermesWsClient.disconnect(clearPendingMessages = true)
        ApiClient.rebuild()
        CookieManager.resetForTest()
        unmockkAll()
        peerSockets.forEach { it.close(1000, "fixture complete") }
        server.shutdown()
    }

    @Test
    fun loginCookieAndFreshTicketOnEachConnection() =
        runBlocking {
            // Retrofit's real gated transport maps a 401 into the app-wide sign-in recovery latch.
            assertEquals(Failure(AuthExpired()), safeApiCall(retries = 0) { ApiClient.hermesApi.getStatus() })
            assertTrue(AuthSessionState.signInRequired.value)
            jar.beginAuthentication()
            assertEquals(
                200,
                OkHttpProvider.probe.newCall(
                    okhttp3.Request.Builder().url(endpoint.resolve("api/auth/login"))
                        .post("{}".toRequestBody()).build(),
                ).execute().use { it.code },
            )
            AuthSessionState.markAuthenticated() // successful login clears the recovery latch
            assertEquals(false, AuthSessionState.signInRequired.value)
            val status = ApiClient.hermesApi.getStatus()
            assertEquals(
                requests.map { "${it.method} ${it.path} cookie=${it.getHeader("Cookie")}" }.toString(),
                200,
                status.code(),
            )
            assertEquals("fixture", status.body()?.version)
            assertEquals(403, ApiClient.hermesApi.getConfig().code())
            assertEquals(403, ApiClient.hermesApi.updateConfig(ConfigUpdateRequest(config = emptyMap())).code())

            // Config has no explicit scope argument on this service revision. Use the production
            // OkHttp gated client with a caller-built URL; do not rely on unfinished global interception.
            fun scoped(
                method: String,
                profile: String,
            ): Int {
                val url =
                    endpoint.resolve("api/config").newBuilder()
                        .addQueryParameter("profile", profile).build()
                val builder = Request.Builder().url(url)
                if (method == "PUT") builder.put("{\"config\":{}}".toRequestBody())
                return OkHttpProvider.probe.newCall(builder.build()).execute().use { it.code }
            }
            assertEquals(200, scoped("GET", "default"))
            assertEquals(200, scoped("PUT", "default"))
            assertEquals(403, scoped("GET", "unknown"))
            assertEquals(403, scoped("PUT", "unknown"))
            assertEquals(false, AuthSessionState.signInRequired.value)
            val mutations = requests.filter { it.method == "PUT" }
            assertEquals(3, mutations.size)
            assertEquals(1, mutations.count { it.requestUrl!!.queryParameter("profile") == "default" })
            assertEquals(1, mutations.count { it.requestUrl!!.queryParameter("profile") == "unknown" })
            assertEquals(1, mutations.count { it.requestUrl!!.queryParameter("profile") == null })
            assertTrue(
                mutations.all { it.getHeader("Cookie") == "session=fixture" && it.getHeader("Authorization") == null },
            )
            assertEquals(0, requests.count { it.requestUrl!!.encodedPath.contains("refresh") })
            assertEquals(
                "session=fixture",
                requests.last {
                    it.requestUrl!!.encodedPath == "/dashboard/api/config"
                }.getHeader("Cookie"),
            )
            HermesWsClient.connect()
            withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } }
            assertEquals("ticket-1", accepted.poll(5, TimeUnit.SECONDS))
            assertEquals(401, replay("ticket-1"))
            assertEquals(401, replay("never-issued"))
            assertEquals(setOf("ticket-1"), consumed.toSet())
            assertTrue(accepted.isEmpty())
            assertTrue(
                "Capability must precede the turn",
                frames.poll(5, TimeUnit.SECONDS)?.contains("client.capabilities") == true,
            )
            HermesWsClient.sendMessage("runtime-session", "fixture prompt")
            assertTrue(
                "WebSocket frame not received",
                frames.poll(5, TimeUnit.SECONDS)?.contains("fixture prompt") == true,
            )
            HermesWsClient.disconnect(clearPendingMessages = true)
            HermesWsClient.connect()
            withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } }
            assertEquals("ticket-2", accepted.poll(5, TimeUnit.SECONDS))
            assertTrue(
                "Reconnect must advertise again",
                frames.poll(5, TimeUnit.SECONDS)?.contains("client.capabilities") == true,
            )
            assertEquals(2, tickets.get())
            assertEquals(setOf("ticket-1", "ticket-2"), consumed.toSet())
            assertEquals(2, issued.size)
            val ticketRequests = requests.filter { it.requestUrl!!.encodedPath == "/dashboard/api/auth/ws-ticket" }
            assertEquals(2, ticketRequests.size)
            assertTrue(
                ticketRequests.all {
                    it.getHeader(
                        "Cookie",
                    ) == "session=fixture" && it.getHeader("Authorization") == null
                },
            )
            assertTrue(
                requests.filter { it.requestUrl!!.encodedPath == "/dashboard/api/ws" }
                    .all { it.getHeader("Cookie") == "session=fixture" && it.getHeader("Authorization") == null },
            )
        }

    private fun replay(ticket: String): Int =
        OkHttpProvider.probe.newCall(
            okhttp3.Request.Builder().url(endpoint.webSocketUrl("ticket", ticket)).build(),
        ).execute().use { it.code }
}
