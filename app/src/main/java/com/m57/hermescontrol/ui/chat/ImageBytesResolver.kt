package com.m57.hermescontrol.ui.chat

import android.content.Context
import android.net.Uri
import com.m57.hermescontrol.data.remote.GatewayFileClient
import com.m57.hermescontrol.data.remote.GatewayFileResult
import com.m57.hermescontrol.data.remote.MAX_IN_MEMORY_MEDIA_BYTES
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.readBytesLimited
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume

private const val MAX_DATA_URL_METADATA_CHARS = 1024

/**
 * Resolve a chat image [ImageViewerModel.model] into raw bytes so the viewer can
 * Save/Share it *locally on the device* — never back to the Hermes server
 * (issue #723).
 *
 * Every model kind handled here is exactly what Coil already renders in the
 * bubble, so a model that displays is also resolvable here:
 * - `data:image/...;base64,...` — agent-delivered inline media,
 * - `content://...` — a locally-picked user image,
 * - an explicit gateway path — fetched through the authenticated gateway
 *   client without URL credentials,
 * - `http(s)://...` — an ordinary remote image URL.
 *
 * All I/O runs on [Dispatchers.IO].
 */
object ImageBytesResolver {
    /** Outcome of [resolve]. */
    sealed interface Result {
        /** Resolved bytes plus the concrete MIME type and file extension. */
        data class Bytes(
            val bytes: ByteArray,
            val mimeType: String,
            val extension: String,
        ) : Result

        /** Could not load the image; [message] is safe to surface to the user. */
        data class Error(
            val message: String,
        ) : Result
    }

    suspend fun resolve(
        context: Context,
        model: String,
        fallbackMime: String,
        gatewayPath: String? = null,
    ): Result =
        withContext(Dispatchers.IO) {
            runCatching {
                when {
                    gatewayPath != null -> fetchGatewayPath(gatewayPath, fallbackMime)
                    model.startsWith("data:") -> decodeDataUrl(model, fallbackMime)
                    model.startsWith("content://") -> readContentUri(context, model, fallbackMime)
                    model.startsWith("https://") || model.startsWith("http://") -> fetchHttp(model, fallbackMime)
                    else -> Result.Error("Unsupported image source")
                }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                Result.Error(e.message ?: "Failed to load image")
            }
        }

    private suspend fun fetchGatewayPath(
        path: String,
        fallbackMime: String,
    ): Result =
        when (val result = GatewayFileClient.fetch(path)) {
            is GatewayFileResult.Success -> {
                val mime = result.file.mimeType.ifBlank { fallbackMime }
                Result.Bytes(result.file.bytes, mime, extensionForMime(mime))
            }

            GatewayFileResult.NotFound -> Result.Error("Image not found on gateway")
            GatewayFileResult.Forbidden -> Result.Error("Access to gateway image denied")
            GatewayFileResult.TooLarge -> Result.Error("Gateway image is too large")
            GatewayFileResult.Unauthorized -> Result.Error("Gateway session expired")
            is GatewayFileResult.Failure ->
                Result.Error(result.throwable.message ?: "Could not load gateway image")
        }

    internal fun decodeDataUrl(
        model: String,
        fallbackMime: String,
        maxBytes: Long = MAX_IN_MEMORY_MEDIA_BYTES,
    ): Result {
        val comma = model.indexOf(',')
        if (comma < 0) return Result.Error("Malformed data URL")
        if (comma > MAX_DATA_URL_METADATA_CHARS) return Result.Error("Malformed data URL")
        val maxEncodedLength = ((maxBytes + 2L) / 3L) * 4L
        val encodedLength = model.length - comma - 1
        if (encodedLength.toLong() > maxEncodedLength) {
            return Result.Error("Image is too large")
        }
        val meta = model.substring(0, comma)
        val data = model.substring(comma + 1).replace(Regex("\\s+"), "")
        val mime =
            meta
                .substringAfter("data:", "image/*")
                .substringBefore(";")
                .ifBlank { fallbackMime }
        if (!meta.contains(";base64", ignoreCase = true)) {
            return Result.Error("Only base64 data URLs are supported")
        }
        val bytes =
            runCatching { Base64.getDecoder().decode(data) }
                .getOrElse { return Result.Error("Could not decode image data") }
        if (bytes.size.toLong() > maxBytes) {
            return Result.Error("Image is too large")
        }
        return Result.Bytes(bytes, mime, extensionForMime(mime))
    }

    private fun readContentUri(
        context: Context,
        model: String,
        fallbackMime: String,
    ): Result {
        val uri = Uri.parse(model)
        val bytes =
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited() } }
                .getOrNull()
                ?: return Result.Error("Could not open local image")
        val mime = context.contentResolver.getType(uri) ?: fallbackMime
        return Result.Bytes(bytes, mime, extensionForMime(mime))
    }

    internal suspend fun fetchHttp(
        model: String,
        fallbackMime: String,
        callFactory: Call.Factory = OkHttpProvider.publicMedia,
    ): Result =
        suspendCancellableCoroutine { continuation ->
            val request =
                Request
                    .Builder()
                    .url(model)
                    .build()
            val call = callFactory.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) {
                            continuation.resume(Result.Error(e.message ?: "Could not download image"))
                        }
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val result =
                            runCatching {
                                response.use { resp ->
                                    if (!resp.isSuccessful) {
                                        return@use Result.Error("Download failed (HTTP ${resp.code})")
                                    }
                                    val bytes =
                                        resp.body.readBytesLimited()
                                            ?: return@use Result.Error("Image is too large")
                                    val mime =
                                        resp
                                            .header("Content-Type")
                                            ?.substringBefore(";")
                                            ?.ifBlank { fallbackMime }
                                            ?: fallbackMime
                                    Result.Bytes(bytes, mime, extensionForMime(mime))
                                }
                            }.getOrElse { e ->
                                Result.Error(e.message ?: "Could not download image")
                            }
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                },
            )
        }

    fun extensionForMime(mime: String): String =
        when (mime.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            "image/heic", "image/heif" -> "heic"
            "image/svg+xml" -> "svg"
            else -> "img"
        }
}
