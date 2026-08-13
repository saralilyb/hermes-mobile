package com.m57.hermescontrol.data.remote

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class OkHttpProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var jar: PersistentCookieJar

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        jar = buildFakePersistentCookieJar()
        CookieManager.setJarForTest(jar)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun publicMediaSetCookieCannotMutateCredentialJar() {
        server.enqueue(
            MockResponse()
                .setHeader("Set-Cookie", "hermes_session_at=attacker; Path=/; HttpOnly")
                .setBody("image"),
        )

        OkHttpProvider.publicMedia
            .newCall(Request.Builder().url(server.url("/image")).build())
            .execute()
            .use { it.body.string() }

        assertNull(jar.getSessionCookieValue())
        assertEquals(0, OkHttpProvider.publicMedia.interceptors.size)
        assertEquals(0, OkHttpProvider.publicMedia.networkInterceptors.size)
    }
}
