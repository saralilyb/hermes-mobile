package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PersistentCookieJar] — the issue #470 cookie stack.
 *
 * Uses [FakeEncryptedCookieStore] (no Android deps) and exercises:
 * - Set-Cookie capture + re-attachment on matching requests
 * - host/path/session-cookie matching rules
 * - atomic server-scope switching (multi-server isolation)
 * - async persistence round-trip through the fake store
 * - pruning of expired (but not session) cookies
 */
class PersistentCookieJarTest {
    private fun makeJar(): PersistentCookieJar =
        PersistentCookieJar(
            store = FakeEncryptedCookieStore(),
            storeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )

    private fun sessionCookie(
        host: String,
        value: String,
        name: String = SESSION_COOKIE_NAME,
    ): Cookie =
        Cookie
            .Builder()
            .name(name)
            .value(value)
            .expiresAt(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)
            .hostOnlyDomain(host)
            .path("/")
            .httpOnly()
            .build()

    @Test
    fun saveFromResponse_thenLoadForRequest_attachesCookie() =
        runTest {
            val jar = makeJar()
            jar.useStore("server-a")
            val url = "http://dashboard.local/".toHttpUrl()
            jar.saveFromResponse(url, listOf(sessionCookie("dashboard.local", "abc123")))

            val loaded = jar.loadForRequest("http://dashboard.local/api/status".toHttpUrl())
            assertEquals(1, loaded.size)
            assertEquals("abc123", loaded[0].value)
            assertEquals(SESSION_COOKIE_NAME, loaded[0].name)
        }

    @Test
    fun cookie_doesNotLeakToOtherHost() =
        runTest {
            val jar = makeJar()
            jar.useStore("server-a")
            jar.saveFromResponse(
                "http://dashboard.local/".toHttpUrl(),
                listOf(sessionCookie("dashboard.local", "abc123")),
            )

            val loaded = jar.loadForRequest("http://evil.local/".toHttpUrl())
            assertTrue(loaded.isEmpty())
        }

    @Test
    fun useStore_isolatesServers() =
        runTest {
            val jar = makeJar()
            jar.useStore("server-a")
            jar.saveFromResponse(
                "http://a.local/".toHttpUrl(),
                listOf(sessionCookie("a.local", "cookie-a")),
            )
            jar.useStore("server-b")
            jar.saveFromResponse(
                "http://b.local/".toHttpUrl(),
                listOf(sessionCookie("b.local", "cookie-b")),
            )

            // Active scope is server-b now — only b's cookie should load.
            val fromB = jar.loadForRequest("http://b.local/x".toHttpUrl())
            assertEquals("cookie-b", fromB.firstOrNull()?.value)

            // Switch back to server-a.
            jar.useStore("server-a")
            val fromA = jar.loadForRequest("http://a.local/x".toHttpUrl())
            assertEquals("cookie-a", fromA.firstOrNull()?.value)
        }

    @Test
    fun persistence_roundTripThroughStore() =
        runTest {
            val store = FakeEncryptedCookieStore()
            val jar =
                PersistentCookieJar(
                    store = store,
                    storeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
                )
            jar.useStore("persist-scope")
            jar.saveFromResponse(
                "http://p.local/".toHttpUrl(),
                listOf(sessionCookie("p.local", "persisted-value")),
            )

            // The fake store should now hold the serialized cookie.
            assertTrue(store.storedHolders("persist-scope").any { it.value == "persisted-value" })

            // A fresh jar sharing the same store must reload it.
            val jar2 =
                PersistentCookieJar(
                    store = store,
                    storeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
                )
            jar2.useStore("persist-scope")
            val reloaded = jar2.loadForRequest("http://p.local/".toHttpUrl())
            assertEquals("persisted-value", reloaded.firstOrNull()?.value)
        }

    @Test
    fun pruneServerCache_removesExpiredKeepsSession() =
        runTest {
            val jar = makeJar()
            jar.useStore("prune-scope")
            val url = "http://p.local/".toHttpUrl()
            val expired =
                Cookie
                    .Builder()
                    .name("expired_cookie")
                    .value("x")
                    .expiresAt(System.currentTimeMillis() - 1000)
                    .hostOnlyDomain("p.local")
                    .path("/")
                    .build()
            jar.saveFromResponse(url, listOf(expired, sessionCookie("p.local", "live")))

            jar.pruneServerCache()

            val remaining = jar.loadForRequest(url)
            assertEquals(1, remaining.size)
            assertEquals(SESSION_COOKIE_NAME, remaining[0].name)
        }

    @Test
    fun saveReplacesCookieWithSameNameAndPath() =
        runTest {
            val jar = makeJar()
            jar.useStore("dup-scope")
            val url = "http://d.local/".toHttpUrl()
            jar.saveFromResponse(url, listOf(sessionCookie("d.local", "v1")))
            jar.saveFromResponse(url, listOf(sessionCookie("d.local", "v2")))

            val loaded = jar.loadForRequest(url)
            assertEquals(1, loaded.size)
            assertEquals("v2", loaded[0].value)
        }

    @Test
    fun clearActive_dropsScopeCookies() =
        runTest {
            val jar = makeJar()
            jar.useStore("clear-scope")
            jar.saveFromResponse(
                "http://c.local/".toHttpUrl(),
                listOf(sessionCookie("c.local", "bye")),
            )
            jar.clearActive()

            assertTrue(jar.loadForRequest("http://c.local/".toHttpUrl()).isEmpty())
        }

    @Test
    fun clearActiveCannotBeOvertakenByEarlierSave() =
        runTest {
            val saveStarted = CompletableDeferred<Unit>()
            val releaseSave = CompletableDeferred<Unit>()
            val clearFinished = CompletableDeferred<Unit>()
            val store =
                object : CookieStore {
                    val values = mutableMapOf<String, List<Cookie>>()

                    override suspend fun save(
                        serverId: String,
                        cookies: List<Cookie>,
                    ) {
                        saveStarted.complete(Unit)
                        releaseSave.await()
                        values[serverId] = cookies
                    }

                    override suspend fun load(serverId: String): List<Cookie> = values[serverId].orEmpty()

                    override suspend fun clear(serverId: String) {
                        values.remove(serverId)
                        clearFinished.complete(Unit)
                    }

                    override suspend fun clearAll() {
                        values.clear()
                    }
                }
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            val jar = PersistentCookieJar(store = store, storeScope = scope)
            jar.useStore("ordered-scope")
            jar.saveFromResponse(
                "http://ordered.local/".toHttpUrl(),
                listOf(sessionCookie("ordered.local", "old")),
            )
            saveStarted.await()

            jar.clearActive()
            releaseSave.complete(Unit)
            clearFinished.await()

            assertTrue(store.values.isEmpty())
        }

    @Test
    fun clearActiveCannotReloadPersistedCookieBeforeAsyncDelete() =
        runTest {
            val clearStarted = CompletableDeferred<Unit>()
            val releaseClear = CompletableDeferred<Unit>()
            val persisted = sessionCookie("clear.local", "old")
            val store =
                object : CookieStore {
                    override suspend fun save(
                        serverId: String,
                        cookies: List<Cookie>,
                    ) = Unit

                    override suspend fun load(serverId: String): List<Cookie> = listOf(persisted)

                    override suspend fun clear(serverId: String) {
                        clearStarted.complete(Unit)
                        releaseClear.await()
                    }

                    override suspend fun clearAll() = Unit
                }
            val jar =
                PersistentCookieJar(
                    store = store,
                    storeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
                )
            jar.useStore("clear-scope")
            assertEquals("old", jar.getSessionCookieValue())

            jar.clearActive()
            clearStarted.await()

            assertNull(jar.getSessionCookieValue())
            assertTrue(jar.loadForRequest("http://clear.local/".toHttpUrl()).isEmpty())
            releaseClear.complete(Unit)
        }

    @Test
    fun credentialBoundaryRejectsResponseCapturedBeforeClear() =
        runTest {
            val jar = makeJar()
            jar.useStore("clear-scope")
            val staleBoundary = jar.captureCredentialBoundary()

            jar.clearActive()

            assertTrue(!jar.acceptsCredentialBoundary(staleBoundary))
            assertTrue(!jar.captureCredentialBoundary().acceptsCookies)
        }

    @Test
    fun explicitAuthenticationCreatesFreshAcceptedBoundary() =
        runTest {
            val jar = makeJar()
            jar.useStore("clear-scope")
            val oldBoundary = jar.captureCredentialBoundary()
            jar.clearActive()

            jar.beginAuthentication()
            val loginBoundary = jar.captureCredentialBoundary()

            assertTrue(!jar.acceptsCredentialBoundary(oldBoundary))
            assertTrue(jar.acceptsCredentialBoundary(loginBoundary))
            assertTrue(loginBoundary.generation > oldBoundary.generation)
        }

    @Test
    fun expiredSessionCookieDeletesStoredCredential() =
        runTest {
            val jar = makeJar()
            val url = "https://dashboard.local/".toHttpUrl()
            jar.useStore("server-a")
            jar.saveFromResponse(url, listOf(sessionCookie("dashboard.local", "active")))
            val deletion =
                Cookie
                    .Builder()
                    .name(SESSION_COOKIE_NAME)
                    .value("")
                    .expiresAt(0)
                    .hostOnlyDomain("dashboard.local")
                    .path("/")
                    .build()

            jar.saveFromResponse(url, listOf(deletion))

            assertNull(jar.getSessionCookieValue())
            assertTrue(jar.loadForRequest(url).isEmpty())
        }

    @Test
    fun staleHttpResponseCannotRestoreCookieAfterClear() {
        val server = MockWebServer()
        val responseStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val store = FakeEncryptedCookieStore()
        val jar =
            PersistentCookieJar(
                store = store,
                storeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            )
        CookieManager.setJarForTest(jar)
        val client =
            okhttp3.OkHttpClient
                .Builder()
                .cookieJar(jar)
                .addInterceptor { chain ->
                    val boundary = jar.captureCredentialBoundary()
                    val request =
                        chain.request().newBuilder().tag(CookieCredentialBoundary::class.java, boundary).build()
                    chain.proceed(request)
                }.addNetworkInterceptor { chain ->
                    responseStarted.complete(Unit)
                    kotlinx.coroutines.runBlocking { releaseResponse.await() }
                    val response = chain.proceed(chain.request())
                    val boundary = chain.request().tag(CookieCredentialBoundary::class.java)
                    if (boundary != null) {
                        jar.saveFromResponse(
                            boundary,
                            chain.request().url,
                            okhttp3.Cookie.parseAll(chain.request().url, response.headers),
                        )
                    }
                    response.newBuilder().removeHeader("Set-Cookie").build()
                }.build()
        server.enqueue(
            MockResponse()
                .setHeader("Set-Cookie", "hermes_session_at=stale; Path=/; HttpOnly")
                .setBody("ok"),
        )
        server.start()
        val call =
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                client.newCall(okhttp3.Request.Builder().url(server.url("/")).build()).execute().use { response ->
                    response.body.string()
                }
            }
        kotlinx.coroutines.runBlocking {
            responseStarted.await()
            jar.clearActive()
            jar.beginAuthentication()
            releaseResponse.complete(Unit)
            call.join()
        }

        assertNull(jar.getSessionCookieValue())
        server.shutdown()
    }

    @Test
    fun getSessionCookieValue_acceptsServerCookieNameVariants() =
        runTest {
            val variants =
                listOf(
                    SESSION_COOKIE_NAME,
                    "__Host-$SESSION_COOKIE_NAME",
                    "__Secure-$SESSION_COOKIE_NAME",
                )

            variants.forEachIndexed { index, name ->
                val jar = makeJar()
                val serverId = "variant-$index"
                val host = "variant-$index.local"
                jar.useStore(serverId)
                jar.saveFromResponse(
                    "https://$host/".toHttpUrl(),
                    listOf(
                        sessionCookie(
                            host = host,
                            value = "value-$index",
                            name = name,
                        ),
                    ),
                )

                assertEquals("value-$index", jar.getSessionCookieValue())
            }
        }

    @Test
    fun loadForRequest_returnsEmptyWhenNoCookies() {
        val jar = makeJar()
        assertTrue(jar.loadForRequest("http://empty.local/".toHttpUrl()).isEmpty())
        assertNull(jar.getSessionCookieValue())
    }

    @Test
    fun getSessionCookieValue_findsPrefixedHostCookie() =
        runTest {
            val jar = makeJar()
            jar.useStore("prefixed-scope")
            val url = "https://dash.local/".toHttpUrl()
            val prefixed =
                Cookie
                    .Builder()
                    .name("__Host-hermes_session_at")
                    .value("host-prefixed-value")
                    .expiresAt(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)
                    .hostOnlyDomain("dash.local")
                    .path("/")
                    .secure()
                    .httpOnly()
                    .build()
            jar.saveFromResponse(url, listOf(prefixed))

            assertEquals("host-prefixed-value", jar.getSessionCookieValue())
        }

    @Test
    fun getSessionCookieValue_prefersStrictestVariant() =
        runTest {
            val jar = makeJar()
            jar.useStore("multi-scope")
            val url = "https://dash.local/".toHttpUrl()
            // Both a bare and a __Secure- prefixed cookie present; the strictest
            // (__Host- / __Secure- first in SESSION_COOKIE_NAMES) must win.
            val bare =
                Cookie
                    .Builder()
                    .name(SESSION_COOKIE_NAME)
                    .value("bare-value")
                    .expiresAt(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)
                    .hostOnlyDomain("dash.local")
                    .path("/")
                    .httpOnly()
                    .build()
            val secure =
                Cookie
                    .Builder()
                    .name("__Secure-hermes_session_at")
                    .value("secure-value")
                    .expiresAt(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)
                    .hostOnlyDomain("dash.local")
                    .path("/")
                    .secure()
                    .httpOnly()
                    .build()
            jar.saveFromResponse(url, listOf(bare, secure))

            assertEquals("secure-value", jar.getSessionCookieValue())
        }
}
