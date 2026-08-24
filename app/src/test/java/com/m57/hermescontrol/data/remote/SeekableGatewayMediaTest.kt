package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthSessionState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class SeekableGatewayMediaTest {
    private val scope = MediaCacheScope("profile-a", "https://example.test/proxy/", "direct-token", "cred-a")

    @Test
    fun `range request returns partial bytes and metadata`() =
        runTest {
            val service = mockk<HermesApiService>()
            every { service.streamManagedFileRange("/tmp/movie.mp4", "bytes=100-199") } returns
                immediateCall(
                    Response.success(
                        "chunk".toResponseBody(),
                        okhttp3.Response.Builder()
                            .request(okhttp3.Request.Builder().url("https://example.test/api/files/stream").build())
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(206)
                            .message("Partial Content")
                            .headers(
                                Headers.headersOf(
                                    "Content-Range",
                                    "bytes 100-104/1000",
                                    "Content-Type",
                                    "video/mp4",
                                ),
                            )
                            .build(),
                    ),
                )
            val session = SeekableGatewayMediaSession("/tmp/movie.mp4", service, scope) { scope }

            val result = session.read(100, 100) as GatewayMediaRangeResult.Success

            assertArrayEquals("chunk".toByteArray(), result.bytes)
            assertEquals(1000L, result.totalLength)
            assertEquals("video/mp4", result.mimeType)
            verify(exactly = 1) { service.streamManagedFileRange("/tmp/movie.mp4", "bytes=100-199") }
        }

    @Test
    fun `profile switch during request rejects stale response`() =
        runTest {
            val service = mockk<HermesApiService>()
            val started = CompletableDeferred<Unit>()
            var current = scope
            lateinit var callback: Callback<ResponseBody>
            val call = mockk<Call<ResponseBody>>(relaxed = true)
            every { call.enqueue(any()) } answers {
                callback = firstArg()
                started.complete(Unit)
            }
            every { service.streamManagedFileRange(any(), any()) } returns call
            val session = SeekableGatewayMediaSession("/tmp/movie.mp4", service, scope) { current }
            val result = async { session.read(0, 32) }
            started.await()
            current = scope.copy(profileId = "profile-b", credentialFingerprint = "cred-b")
            callback.onResponse(call, Response.success("old-profile".toResponseBody()))

            assertTrue(result.await() is GatewayMediaRangeResult.Stale)
        }

    @Test
    fun `mismatched content range is rejected`() =
        runTest {
            val service = mockk<HermesApiService>()
            every { service.streamManagedFileRange(any(), any()) } returns
                immediateCall(
                    Response.success(
                        "chunk".toResponseBody(),
                        okhttp3.Response.Builder()
                            .request(okhttp3.Request.Builder().url("https://example.test/api/files/stream").build())
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(206)
                            .message("Partial Content")
                            .headers(Headers.headersOf("Content-Range", "bytes 0-4/1000"))
                            .build(),
                    ),
                )
            val session = SeekableGatewayMediaSession("/tmp/movie.mp4", service, scope) { scope }

            assertTrue(session.read(100, 5) is GatewayMediaRangeResult.Failure)
        }

    @Test
    fun `range response body larger than requested is rejected and closed`() =
        runTest {
            val service = mockk<HermesApiService>()
            var bodyClosed = false
            val body =
                object : ResponseBody() {
                    private val source =
                        object : ForwardingSource(Buffer().writeUtf8("chunks")) {
                            override fun close() {
                                bodyClosed = true
                                super.close()
                            }
                        }.buffer()

                    override fun contentType() = null

                    override fun contentLength() = 6L

                    override fun source() = source
                }
            every { service.streamManagedFileRange("/tmp/movie.mp4", "bytes=100-104") } returns
                immediateCall(
                    Response.success(
                        body,
                        okhttp3.Response.Builder()
                            .request(okhttp3.Request.Builder().url("https://example.test/api/files/stream").build())
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(206)
                            .message("Partial Content")
                            .headers(Headers.headersOf("Content-Range", "bytes 100-105/1000"))
                            .build(),
                    ),
                )
            val session = SeekableGatewayMediaSession("/tmp/movie.mp4", service, scope) { scope }

            assertEquals(GatewayMediaRangeResult.TooLarge, session.read(100, 5))
            assertTrue(bodyClosed)
        }

    @Test
    fun `stale session rejects before issuing another range request`() =
        runTest {
            val service = mockk<HermesApiService>(relaxed = true)
            val session =
                SeekableGatewayMediaSession("/tmp/movie.mp4", service, scope) {
                    scope.copy(canonicalEndpoint = "https://replacement.test/", credentialFingerprint = "cred-b")
                }

            assertTrue(session.read(0, 64) is GatewayMediaRangeResult.Stale)
            verify(exactly = 0) { service.streamManagedFileRange(any(), any()) }
        }

    @Test
    fun `stale 401 cannot expire replacement profile`() =
        runTest {
            AuthSessionState.resetForTest()
            val service = mockk<HermesApiService>()
            val started = CompletableDeferred<Unit>()
            var current = scope
            lateinit var callback: Callback<ResponseBody>
            val call = mockk<Call<ResponseBody>>(relaxed = true)
            every { call.enqueue(any()) } answers {
                callback = firstArg()
                started.complete(Unit)
            }
            every { service.streamManagedFileRange(any(), any()) } returns call
            val session = SeekableGatewayMediaSession("/tmp/movie.mp4", service, scope) { current }
            val result = async { session.read(0, 64) }
            started.await()
            current = scope.copy(profileId = "profile-b", credentialFingerprint = "cred-b")
            callback.onResponse(call, Response.error(401, ByteArray(0).toResponseBody()))

            assertEquals(GatewayMediaRangeResult.Stale, result.await())
            assertTrue(!AuthSessionState.signInRequired.value)
            AuthSessionState.resetForTest()
        }

    @Test
    fun `range 401 requires sign in`() =
        runTest {
            AuthSessionState.resetForTest()
            val service = mockk<HermesApiService>()
            every { service.streamManagedFileRange(any(), any()) } returns
                immediateCall(Response.error(401, ByteArray(0).toResponseBody()))
            val session = SeekableGatewayMediaSession("/tmp/movie.mp4", service, scope) { scope }

            assertEquals(GatewayMediaRangeResult.Unauthorized, session.read(0, 64))
            assertTrue(AuthSessionState.signInRequired.value)
            AuthSessionState.resetForTest()
        }

    @Test
    fun `invalid media path is rejected without request`() =
        runTest {
            val service = mockk<HermesApiService>(relaxed = true)
            val session =
                SeekableGatewayMediaSession("https://evil.test/movie.mp4?token=secret", service, scope) { scope }

            val result = session.read(0, 64)

            assertTrue(result is GatewayMediaRangeResult.Failure)
            verify(exactly = 0) { service.streamManagedFileRange(any(), any()) }
        }

    @Test
    fun `range endpoint has no credential query parameters`() {
        val method = HermesApiService::class.java.declaredMethods.single { it.name == "streamManagedFileRange" }
        val queryNames =
            method.parameterAnnotations
                .flatMap { it.asIterable() }
                .filterIsInstance<retrofit2.http.Query>()
                .map { it.value }
        val headerNames =
            method.parameterAnnotations
                .flatMap { it.asIterable() }
                .filterIsInstance<retrofit2.http.Header>()
                .map { it.value }

        assertEquals(listOf("path"), queryNames)
        assertEquals(listOf("Range"), headerNames)
        assertNull(method.getAnnotation(retrofit2.http.Url::class.java))
    }

    private fun immediateCall(response: Response<ResponseBody>): Call<ResponseBody> {
        val call = mockk<Call<ResponseBody>>(relaxed = true)
        every { call.enqueue(any()) } answers { firstArg<Callback<ResponseBody>>().onResponse(call, response) }
        return call
    }
}
