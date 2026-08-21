package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthSessionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class GatewayFileClientTest {
    private val scope = MediaCacheScope("profile-a", "https://example.test/proxy/", "direct-token", "cred-a")

    @Test
    fun `download endpoint exposes only the path query parameter`() {
        val method = HermesApiService::class.java.declaredMethods.single { it.name == "downloadManagedFile" }
        val get = checkNotNull(method.getAnnotation(GET::class.java))
        val queries = method.parameterAnnotations.flatMap { it.asIterable() }.filterIsInstance<Query>()

        assertEquals("api/files/download", get.value)
        assertEquals(listOf("path"), queries.map { it.value })
    }

    @Test
    fun `cache key isolates profile endpoint auth mode and credential`() {
        val cache = GatewayMediaCache(tempDir())
        val base = cache.key(scope, "/tmp/a.png")
        assertNotEquals(base, cache.key(scope.copy(profileId = "profile-b"), "/tmp/a.png"))
        assertNotEquals(base, cache.key(scope.copy(canonicalEndpoint = "https://other.test/"), "/tmp/a.png"))
        assertNotEquals(base, cache.key(scope.copy(authMode = "gated-cookie"), "/tmp/a.png"))
        assertNotEquals(base, cache.key(scope.copy(credentialFingerprint = "cred-b"), "/tmp/a.png"))
    }

    @Test
    fun `fetch streams authenticated response to cache and reuses same key`() =
        runTest {
            val service = mockk<HermesApiService>()
            val cache = GatewayMediaCache(tempDir())
            val headers =
                Headers.headersOf(
                    "Content-Disposition",
                    "attachment; filename=photo.png",
                    "Content-Type",
                    "image/png",
                )
            coEvery { service.downloadManagedFile("/tmp/a.png") } returns
                Response.success("image bytes".toResponseBody("image/png".toMediaType()), headers)

            val first = GatewayFileClient.fetch("/tmp/a.png", service, cache, scope) as GatewayFileResult.Success
            val second = GatewayFileClient.fetch("/tmp/a.png", service, cache, scope) as GatewayFileResult.Success

            assertArrayEquals("image bytes".toByteArray(), first.file.cacheFile.readBytes())
            assertEquals(first.file.cacheFile, second.file.cacheFile)
            coVerify(exactly = 1) { service.downloadManagedFile("/tmp/a.png") }
        }

    @Test
    fun `same-key concurrent fetches share one request`() =
        runTest {
            val service = mockk<HermesApiService>()
            val cache = GatewayMediaCache(tempDir())
            val gate = CompletableDeferred<Unit>()
            coEvery { service.downloadManagedFile(any()) } coAnswers {
                gate.await()
                Response.success("one".toResponseBody())
            }
            val first = async { GatewayFileClient.fetch("/tmp/a.bin", service, cache, scope) }
            val second = async { GatewayFileClient.fetch("/tmp/a.bin", service, cache, scope) }
            gate.complete(Unit)
            assertTrue(first.await() is GatewayFileResult.Success)
            assertTrue(second.await() is GatewayFileResult.Success)
            coVerify(exactly = 1) { service.downloadManagedFile("/tmp/a.bin") }
        }

    @Test
    fun `scope transition while waiting for key lock does not return obsolete cached bytes`() =
        runTest {
            val service = mockk<HermesApiService>()
            val cache = GatewayMediaCache(tempDir())
            val downloadStarted = CompletableDeferred<Unit>()
            val releaseDownload = CompletableDeferred<Unit>()
            coEvery { service.downloadManagedFile("/tmp/locked.bin") } coAnswers {
                downloadStarted.complete(Unit)
                releaseDownload.await()
                Response.success("obsolete-scope".toResponseBody())
            }

            val lockHolder = async { GatewayFileClient.fetch("/tmp/locked.bin", service, cache, scope) }
            downloadStarted.await()
            var waitingContextIsCurrent = true
            val waiterStarted = CompletableDeferred<Unit>()
            val waiter =
                async {
                    waiterStarted.complete(Unit)
                    GatewayFileClient.fetch("/tmp/locked.bin", service, cache, scope) {
                        waitingContextIsCurrent
                    }
                }
            waiterStarted.await()
            waitingContextIsCurrent = false
            releaseDownload.complete(Unit)

            val (holderResult, result) =
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(5_000) { lockHolder.await() to waiter.await() }
                }
            assertTrue(holderResult is GatewayFileResult.Success)
            assertTrue(result is GatewayFileResult.Failure)
            assertTrue((result as GatewayFileResult.Failure).throwable is StaleMediaBoundaryException)
            coVerify(exactly = 1) { service.downloadManagedFile("/tmp/locked.bin") }
        }

    @Test
    fun `failed refresh preserves prior target and removes temp`() =
        runTest {
            val dir = tempDir()
            val cache = GatewayMediaCache(dir, now = { 1_000_000L })
            val key = cache.key(scope, "/tmp/a.bin")
            cache.write(key, "good".toResponseBody())
            val target = cache.fresh(key)!!
            target.setLastModified(0)
            val service = mockk<HermesApiService>()
            coEvery { service.downloadManagedFile(any()) } throws IOException("failed")

            assertTrue(GatewayFileClient.fetch("/tmp/a.bin", service, cache, scope) is GatewayFileResult.Failure)
            assertEquals("good", target.readText())
            assertFalse(dir.listFiles().orEmpty().any { it.name.startsWith(".") })
        }

    @Test
    fun `eviction is bounded and deterministic`() =
        runTest {
            var clock = 1L
            val dir = tempDir()
            val cache = GatewayMediaCache(dir, now = { clock }, maxBytes = 6, maxFiles = 2)
            repeat(3) { index ->
                clock++
                cache.write(cache.key(scope, "/$index"), "abc".toResponseBody())
                dir.listFiles().orEmpty().forEach { if (!it.name.startsWith(".")) it.setLastModified(clock) }
            }
            val files = dir.listFiles().orEmpty().filter { !it.name.startsWith(".") }
            assertEquals(2, files.size)
            assertTrue(files.sumOf(File::length) <= 6)
        }

    @Test
    fun `copy cancellation is rethrown`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val input =
                object : ByteArrayInputStream(ByteArray(200_000)) {
                    var reads = 0

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        reads++
                        if (reads == 2) kotlinx.coroutines.runBlocking { gate.await() }
                        return super.read(b, off, len)
                    }
                }
            val job =
                launch(Dispatchers.Default) {
                    GatewayFileClient.copyChunked(input, java.io.ByteArrayOutputStream())
                }
            while (input.reads < 2) kotlinx.coroutines.yield()
            job.cancel()
            gate.complete(Unit)
            job.join()
            assertTrue(job.isCancelled)
        }

    @Test
    fun `oversized single file is rejected without replacing target`() =
        runTest {
            val dir = tempDir()
            val cache = GatewayMediaCache(dir, maxBytes = 4)
            val key = cache.key(scope, "/large")
            cache.write(key, "good".toResponseBody())
            val target = cache.fresh(key)!!
            target.setLastModified(0)
            val service = mockk<HermesApiService>()
            coEvery { service.downloadManagedFile(any()) } returns Response.success("oversized".toResponseBody())

            assertEquals(GatewayFileResult.TooLarge, GatewayFileClient.fetch("/large", service, cache, scope))
            assertEquals("good", target.readText())
            assertTrue(dir.listFiles().orEmpty().filter { !it.name.startsWith(".") }.sumOf(File::length) <= 4)
            assertFalse(dir.listFiles().orEmpty().any { it.name.startsWith(".") })
        }

    @Test
    fun `stale boundary after download does not publish`() =
        runTest {
            val dir = tempDir()
            val cache = GatewayMediaCache(dir)
            val service = mockk<HermesApiService>()
            coEvery { service.downloadManagedFile(any()) } returns Response.success("new".toResponseBody())

            val result = GatewayFileClient.fetch("/stale", service, cache, scope) { false }

            assertTrue(result is GatewayFileResult.Failure)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `atomic move failure preserves prior target and removes temp`() =
        runTest {
            val dir = tempDir()
            val normal = GatewayMediaCache(dir)
            val key = normal.key(scope, "/atomic")
            normal.write(key, "old".toResponseBody())
            val target = normal.fresh(key)!!
            target.setLastModified(0)
            val failing = GatewayMediaCache(dir, atomicMover = { _, _ -> throw IOException("atomic unavailable") })

            runCatching { failing.write(key, "new".toResponseBody()) }

            assertEquals("old", target.readText())
            assertFalse(dir.listFiles().orEmpty().any { it.name.startsWith(".") })
        }

    @Test
    fun `transition from atomic move callback removes published stale target`() =
        runTest {
            val dir = tempDir()
            var current = true
            val cache =
                GatewayMediaCache(
                    dir,
                    atomicMover = { source, target ->
                        java.nio.file.Files.move(
                            source.toPath(),
                            target.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                        current = false
                    },
                )
            val service = mockk<HermesApiService>()
            coEvery { service.downloadManagedFile(any()) } returns Response.success("old-scope".toResponseBody())

            val result = GatewayFileClient.fetch("/transition", service, cache, scope) { current }

            assertTrue(result is GatewayFileResult.Failure)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `cancelling cache write mid-stream removes temp and preserves target`() =
        runTest {
            val dir = tempDir()
            val cache = GatewayMediaCache(dir)
            val key = cache.key(scope, "/cancel")
            cache.write(key, "old".toResponseBody())
            val target = cache.fresh(key)!!
            val gate = CompletableDeferred<Unit>()
            val body =
                object : okhttp3.ResponseBody() {
                    override fun contentType() = null

                    override fun contentLength() = -1L

                    override fun source(): okio.BufferedSource =
                        object : ByteArrayInputStream(ByteArray(200_000)) {
                            var reads = 0

                            override fun read(
                                b: ByteArray,
                                off: Int,
                                len: Int,
                            ): Int {
                                reads++
                                if (reads == 2) kotlinx.coroutines.runBlocking { gate.await() }
                                return super.read(b, off, len)
                            }
                        }.source().buffer()
                }
            val job = launch(Dispatchers.Default) { cache.write(key, body) }
            while (!dir.listFiles().orEmpty().any { it.name.startsWith(".") }) kotlinx.coroutines.yield()
            job.cancel()
            gate.complete(Unit)
            job.join()

            assertTrue(job.isCancelled)
            assertEquals("old", target.readText())
            assertFalse(dir.listFiles().orEmpty().any { it.name.startsWith(".") })
        }

    @Test
    fun `unauthorized response requires sign in`() =
        runTest {
            AuthSessionState.resetForTest()
            val service = mockk<HermesApiService>()
            coEvery { service.downloadManagedFile(any()) } returns Response.error(401, ByteArray(0).toResponseBody())
            assertEquals(
                GatewayFileResult.Unauthorized,
                GatewayFileClient.fetch("/tmp/x", service, GatewayMediaCache(tempDir()), scope),
            )
            assertTrue(AuthSessionState.signInRequired.value)
            AuthSessionState.resetForTest()
        }

    @Test
    fun `normalize accepts gateway absolute and tilde paths but rejects relative paths`() {
        assertEquals("~/foo.png", GatewayFileClient.normalizePath("~/foo.png"))
        assertEquals("/tmp/x.png", GatewayFileClient.normalizePath("'/tmp/x.png'"))
        assertNull(GatewayFileClient.normalizePath("relative.png"))
        assertNull(GatewayFileClient.normalizePath("MEDIA:relative.png"))
    }

    @Test
    fun `classifyStatus maps managed file errors`() {
        assertEquals(GatewayFileResult.NotFound, GatewayFileClient.classifyStatus(404))
        assertEquals(GatewayFileResult.Forbidden, GatewayFileClient.classifyStatus(403))
        assertEquals(GatewayFileResult.TooLarge, GatewayFileClient.classifyStatus(413))
        assertEquals(GatewayFileResult.Unauthorized, GatewayFileClient.classifyStatus(401))
        assertNull(GatewayFileClient.classifyStatus(200))
        assertNull(GatewayFileClient.classifyStatus(500))
    }

    @Test
    fun `bounded readers reject oversized data`() {
        assertNull("abcd".toResponseBody().readBytesLimited(maxBytes = 3))
        assertArrayEquals("abc".toByteArray(), "abc".toResponseBody().readBytesLimited(maxBytes = 3))
        assertNull(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)).readBytesLimited(maxBytes = 3))
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            ByteArrayInputStream(byteArrayOf(1, 2, 3)).readBytesLimited(maxBytes = 3),
        )
    }

    @Test fun `filename parser decodes utf8`() =
        assertEquals("a b.png", GatewayFileClient.parseFilename("filename*=UTF-8''a%20b.png"))

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "media-${System.nanoTime()}").apply {
            mkdirs()
        }
}
