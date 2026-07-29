package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthSessionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.ByteArrayInputStream

class GatewayFileClientTest {
    @Test
    fun `download endpoint exposes only the path query parameter`() {
        val method =
            HermesApiService::class.java.declaredMethods.single {
                it.name == "downloadManagedFile"
            }
        val get = checkNotNull(method.getAnnotation(GET::class.java))
        val queries =
            method.parameterAnnotations
                .flatMap { it.asIterable() }
                .filterIsInstance<Query>()

        assertEquals("api/files/download", get.value)
        assertEquals(listOf("path"), queries.map { it.value })
    }

    @Test
    fun `fetch streams through authenticated service without URL credentials`() =
        runTest {
            val service = mockk<HermesApiService>()
            val bytes = "image bytes".toByteArray()
            val headers =
                Headers.headersOf(
                    "Content-Disposition",
                    "attachment; filename=\"photo.png\"",
                    "Content-Type",
                    "image/png; charset=binary",
                )
            coEvery {
                service.downloadManagedFile("/tmp/a b.png")
            } returns Response.success(bytes.toResponseBody("image/png".toMediaType()), headers)

            val result = GatewayFileClient.fetch("\"/tmp/a b.png\"", service)

            assertTrue(result is GatewayFileResult.Success)
            result as GatewayFileResult.Success
            assertEquals("photo.png", result.file.name)
            assertEquals("image/png", result.file.mimeType)
            assertArrayEquals(bytes, result.file.bytes)
            coVerify(exactly = 1) { service.downloadManagedFile("/tmp/a b.png") }
        }

    @Test
    fun `fetch maps unauthorized response`() =
        runTest {
            AuthSessionState.resetForTest()
            try {
                val service = mockk<HermesApiService>()
                coEvery {
                    service.downloadManagedFile("/tmp/x.png")
                } returns Response.error(401, ByteArray(0).toResponseBody())

                assertEquals(
                    GatewayFileResult.Unauthorized,
                    GatewayFileClient.fetch("/tmp/x.png", service),
                )
                assertTrue(AuthSessionState.signInRequired.value)
            } finally {
                AuthSessionState.resetForTest()
            }
        }

    @Test
    fun `fetch rejects relative paths without making a request`() =
        runTest {
            val service = mockk<HermesApiService>(relaxed = true)

            val result = GatewayFileClient.fetch("relative/path.png", service)

            assertTrue(result is GatewayFileResult.Failure)
            coVerify(exactly = 0) { service.downloadManagedFile(any()) }
        }

    @Test
    fun `normalizePath preserves gateway tilde path`() {
        assertEquals("~/foo.png", GatewayFileClient.normalizePath("~/foo.png"))
    }

    @Test
    fun `normalizePath strips surrounding quotes`() {
        assertEquals("/tmp/x.png", GatewayFileClient.normalizePath("'/tmp/x.png'"))
        assertEquals("/tmp/x.png", GatewayFileClient.normalizePath("`/tmp/x.png`"))
    }

    @Test
    fun `normalizePath requires absolute or tilde path`() {
        assertNull(GatewayFileClient.normalizePath("relative.png"))
        assertNull(GatewayFileClient.normalizePath("MEDIA:relative.png"))
    }

    @Test
    fun `classifyStatus maps known codes`() {
        assertEquals(GatewayFileResult.NotFound, GatewayFileClient.classifyStatus(404))
        assertEquals(GatewayFileResult.Forbidden, GatewayFileClient.classifyStatus(403))
        assertEquals(GatewayFileResult.TooLarge, GatewayFileClient.classifyStatus(413))
        assertEquals(GatewayFileResult.Unauthorized, GatewayFileClient.classifyStatus(401))
        assertNull(GatewayFileClient.classifyStatus(200))
        assertNull(GatewayFileClient.classifyStatus(500))
    }

    @Test
    fun `bounded response read rejects oversized body`() {
        assertNull("abcd".toResponseBody().readBytesLimited(maxBytes = 3))
        assertArrayEquals(
            "abc".toByteArray(),
            "abc".toResponseBody().readBytesLimited(maxBytes = 3),
        )
    }

    @Test
    fun `bounded input stream read rejects oversized data`() {
        assertNull(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)).readBytesLimited(maxBytes = 3))
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            ByteArrayInputStream(byteArrayOf(1, 2, 3)).readBytesLimited(maxBytes = 3),
        )
    }

    @Test
    fun `parseFilename extracts from content-disposition`() {
        assertEquals("report.pdf", GatewayFileClient.parseFilename("attachment; filename=\"report.pdf\""))
        assertEquals("a b.png", GatewayFileClient.parseFilename("inline; filename*=UTF-8''a%20b.png"))
    }
}
