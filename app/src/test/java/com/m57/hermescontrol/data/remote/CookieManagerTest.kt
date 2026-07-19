// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the encrypted, per-endpoint cookie coordinator. */
class CookieManagerTest {
    private fun fakeJar(): PersistentCookieJar {
        val jar = buildFakePersistentCookieJar()
        runTest { jar.useStore(PersistentCookieJar.DEFAULT_SERVER_ID) }
        CookieManager.setJarForTest(jar)
        return jar
    }

    private val secureEndpoint =
        ServerEndpoint.parse("https://hermes.example.com:9119/prefix/")

    @After
    fun tearDown() {
        CookieManager.resetForTest()
    }

    @Test
    fun setThenGetSessionCookie_roundTrips() {
        fakeJar()
        CookieManager.setSessionCookie("sess-xyz", secureEndpoint)

        assertEquals("sess-xyz", CookieManager.getSessionCookie())
    }

    @Test
    fun setSessionCookie_nullClearsValue() {
        fakeJar()
        CookieManager.setSessionCookie("sess-xyz", secureEndpoint)
        assertEquals("sess-xyz", CookieManager.getSessionCookie())

        CookieManager.setSessionCookie(null, secureEndpoint)
        assertNull(CookieManager.getSessionCookie())
    }

    @Test
    fun setSessionCookie_isSecureAndScopedToHttpsOrigin() =
        runTest {
            val jar = fakeJar()
            CookieManager.setSessionCookie("sess-xyz", secureEndpoint)

            val onOrigin =
                jar.loadForRequest(
                    "https://hermes.example.com:9119/prefix/api/status"
                        .toHttpUrl(),
                )
            assertEquals(1, onOrigin.size)
            assertEquals("sess-xyz", onOrigin[0].value)
            assertTrue(onOrigin[0].secure)

            val cleartext =
                jar.loadForRequest(
                    "http://hermes.example.com:9119/prefix/api/status"
                        .toHttpUrl(),
                )
            assertTrue(cleartext.isEmpty())

            val offHost =
                jar.loadForRequest("https://other.example.com/".toHttpUrl())
            assertTrue(offHost.isEmpty())
        }

    @Test
    fun pruneServerCache_delegatesToJar() =
        runTest {
            fakeJar()
            CookieManager.setSessionCookie("keep-me", secureEndpoint)
            CookieManager.pruneServerCache()
            assertEquals("keep-me", CookieManager.getSessionCookie())
        }

    @Test
    fun clearAll_wipesSession() =
        runTest {
            fakeJar()
            CookieManager.setSessionCookie("bye", secureEndpoint)
            CookieManager.clearAll()
            assertNull(CookieManager.getSessionCookie())
        }
}
