package com.m57.hermescontrol.ui.chat

import android.content.Context
import com.m57.hermescontrol.data.remote.GatewayFile
import com.m57.hermescontrol.data.remote.GatewayFileClient
import com.m57.hermescontrol.data.remote.GatewayFileResult
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class ImageBytesResolverTest {
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        // resolve() runs on Dispatchers.IO; bind the main dispatcher for tests.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @Test
    fun `extensionForMime maps known types`() {
        assertEquals("png", ImageBytesResolver.extensionForMime("image/png"))
        assertEquals("jpg", ImageBytesResolver.extensionForMime("image/jpeg"))
        assertEquals("jpg", ImageBytesResolver.extensionForMime("image/JPG"))
        assertEquals("gif", ImageBytesResolver.extensionForMime("image/gif"))
        assertEquals("webp", ImageBytesResolver.extensionForMime("image/webp"))
        assertEquals("heic", ImageBytesResolver.extensionForMime("image/heic"))
        assertEquals("svg", ImageBytesResolver.extensionForMime("image/svg+xml"))
        assertEquals("img", ImageBytesResolver.extensionForMime("image/unknown"))
    }

    @Test
    fun `data URL base64 decodes to bytes with mime and extension`() =
        runTest {
            val raw = "hello-image".toByteArray()
            val b64 = Base64.getEncoder().encodeToString(raw)
            val model = "data:image/png;base64,$b64"

            val result = ImageBytesResolver.resolve(context, model, "image/*")

            assertTrue(result is ImageBytesResolver.Result.Bytes)
            result as ImageBytesResolver.Result.Bytes
            assertArrayEquals(raw, result.bytes)
            assertEquals("image/png", result.mimeType)
            assertEquals("png", result.extension)
        }

    @Test
    fun `data URL falls back to fallbackMime when meta omitted`() =
        runTest {
            val raw = byteArrayOf(1, 2, 3, 4)
            val b64 = Base64.getEncoder().encodeToString(raw)
            val model = "data:;base64,$b64"

            val result = ImageBytesResolver.resolve(context, model, "image/webp")

            assertTrue(result is ImageBytesResolver.Result.Bytes)
            result as ImageBytesResolver.Result.Bytes
            assertEquals("image/webp", result.mimeType)
            assertEquals("webp", result.extension)
        }

    @Test
    fun `HTTP cancellation cancels the OkHttp call`() =
        runTest {
            val call = mockk<Call>()
            val callFactory = mockk<Call.Factory>()
            val callback = slot<Callback>()
            every { callFactory.newCall(any()) } returns call
            every { call.enqueue(capture(callback)) } just Runs
            every { call.cancel() } just Runs

            val job =
                launch {
                    ImageBytesResolver.fetchHttp(
                        model = "https://example.com/image.png",
                        fallbackMime = "image/png",
                        callFactory = callFactory,
                    )
                }
            runCurrent()

            job.cancelAndJoin()

            verify(exactly = 1) { call.cancel() }
        }

    @Test
    fun `HTTP response resolves bytes and content type asynchronously`() =
        runTest {
            val call = mockk<Call>()
            val callFactory = mockk<Call.Factory>()
            val callback = slot<Callback>()
            every { callFactory.newCall(any()) } returns call
            every { call.enqueue(capture(callback)) } just Runs
            every { call.cancel() } just Runs
            val request = Request.Builder().url("https://example.com/image.png").build()

            val result =
                async {
                    ImageBytesResolver.fetchHttp(
                        model = request.url.toString(),
                        fallbackMime = "image/*",
                        callFactory = callFactory,
                    )
                }
            runCurrent()
            callback.captured.onResponse(
                call,
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "image/png")
                    .body(byteArrayOf(1, 2, 3).toResponseBody("image/png".toMediaType()))
                    .build(),
            )

            val resolved = result.await() as ImageBytesResolver.Result.Bytes
            assertArrayEquals(byteArrayOf(1, 2, 3), resolved.bytes)
            assertEquals("image/png", resolved.mimeType)
            assertEquals("png", resolved.extension)
        }

    @Test
    fun `data URL rejects decoded bytes over media limit`() {
        val raw = byteArrayOf(1, 2, 3, 4)
        val b64 = Base64.getEncoder().encodeToString(raw)
        val model = "data:image/png;base64,$b64"

        val result =
            ImageBytesResolver.decodeDataUrl(
                model = model,
                fallbackMime = "image/*",
                maxBytes = 3,
            )

        assertEquals(ImageBytesResolver.Result.Error("Image is too large"), result)
    }

    @Test
    fun `data URL counts whitespace toward encoded limit`() {
        val model = "data:image/png;base64,YQ== "

        val result =
            ImageBytesResolver.decodeDataUrl(
                model = model,
                fallbackMime = "image/*",
                maxBytes = 1,
            )

        assertEquals(ImageBytesResolver.Result.Error("Image is too large"), result)
    }

    @Test
    fun `data URL rejects oversized metadata before copying payload`() {
        val model = "data:${"x".repeat(1024)},YQ=="

        val result =
            ImageBytesResolver.decodeDataUrl(
                model = model,
                fallbackMime = "image/*",
                maxBytes = 1,
            )

        assertEquals(ImageBytesResolver.Result.Error("Malformed data URL"), result)
    }

    @Test
    fun `data URL accepts decoded bytes at media limit`() {
        val raw = byteArrayOf(1, 2, 3)
        val b64 = Base64.getEncoder().encodeToString(raw)
        val model = "data:image/png;base64,$b64"

        val result =
            ImageBytesResolver.decodeDataUrl(
                model = model,
                fallbackMime = "image/*",
                maxBytes = 3,
            )

        assertTrue(result is ImageBytesResolver.Result.Bytes)
        assertArrayEquals(raw, (result as ImageBytesResolver.Result.Bytes).bytes)
    }

    @Test
    fun `malformed data URL (no comma) returns Error`() =
        runTest {
            val result = ImageBytesResolver.resolve(context, "data:image/png;base64XXXX", "image/*")
            assertTrue(result is ImageBytesResolver.Result.Error)
        }

    @Test
    fun `non-base64 data URL returns Error`() =
        runTest {
            val result = ImageBytesResolver.resolve(context, "data:image/png,notbase64", "image/*")
            assertTrue(result is ImageBytesResolver.Result.Error)
        }

    @Test
    fun `unsupported model source returns Error`() =
        runTest {
            val result = ImageBytesResolver.resolve(context, "file:///sdcard/x.png", "image/*")
            assertTrue(result is ImageBytesResolver.Result.Error)
        }

    @Test
    fun `gateway path resolves through authenticated client`() =
        runTest {
            mockkObject(GatewayFileClient)
            val bytes = "gateway-image".toByteArray()
            coEvery {
                GatewayFileClient.fetch("/tmp/image.png")
            } returns
                GatewayFileResult.Success(
                    GatewayFile("image.png", "image/png", bytes),
                )

            val result =
                ImageBytesResolver.resolve(
                    context = context,
                    model = "/tmp/image.png",
                    fallbackMime = "image/*",
                    gatewayPath = "/tmp/image.png",
                )

            assertTrue(result is ImageBytesResolver.Result.Bytes)
            result as ImageBytesResolver.Result.Bytes
            assertArrayEquals(bytes, result.bytes)
            assertEquals("image/png", result.mimeType)
            assertEquals("png", result.extension)
        }
}
