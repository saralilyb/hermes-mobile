package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ApiClientMediaServiceTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        CookieManager.setJarForTest(buildFakePersistentCookieJar())
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        CookieManager.resetForTest()
    }

    @Test
    fun `direct service keeps captured bearer and endpoint`() =
        runTest {
            server.enqueue(MockResponse().setBody("bytes"))
            val endpoint = ServerEndpoint.parseForBuild(server.url("/captured/").toString())
            val service = ApiClient.createMediaService(endpoint, gated = false, token = "snapshot-token")

            service.streamManagedFileRange("/tmp/a", "bytes=10-19").body()?.close()

            val request = server.takeRequest()
            assertEquals("Bearer snapshot-token", request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            assertEquals("bytes=10-19", request.getHeader("Range"))
            assertEquals("/captured/api/files/stream?path=%2Ftmp%2Fa", request.path)
            assertFalse(request.path.orEmpty().contains("snapshot-token"))
        }

    @Test
    fun `gated service keeps captured cookie and 401 performs one request`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401))
            val endpoint = ServerEndpoint.parseForBuild(server.url("/snapshot/").toString())
            val service =
                ApiClient.createMediaService(
                    endpoint = endpoint,
                    gated = true,
                    token = null,
                    cookieHeader = "hermes_session=captured; secondary=fixed",
                )

            val response = service.streamManagedFileRange("/tmp/a", "bytes=0-63")
            response.errorBody()?.close()

            val request = server.takeRequest()
            assertEquals("hermes_session=captured; secondary=fixed", request.getHeader("Cookie"))
            assertNull(request.getHeader("Authorization"))
            assertEquals("bytes=0-63", request.getHeader("Range"))
            assertEquals(1, server.requestCount)
            assertEquals("/snapshot/api/files/stream?path=%2Ftmp%2Fa", request.path)
            assertFalse(request.path.orEmpty().contains("captured"))
        }
}
