package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthSessionState
import kotlinx.coroutines.CancellationException
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.IOException

/**
 * A seekable gateway-media transport captured at one profile credential boundary.
 *
 * This is deliberately a transport seam rather than a URL factory: callers can
 * request bounded ranges, but credentials remain inside the endpoint-bound
 * Retrofit service and never appear in a URI. Every request is fenced before
 * dispatch and again before its response can be consumed.
 */
internal fun interface SeekableGatewayMediaReader {
    suspend fun read(position: Long, byteCount: Int): GatewayMediaRangeResult

    fun isCurrent(): Boolean = true

    companion object {
        const val MAX_RANGE_BYTES = 1024 * 1024
    }
}

internal class SeekableGatewayMediaSession(
    rawPath: String,
    private val service: HermesApiService,
    private val scope: MediaCacheScope,
    private val currentScope: () -> MediaCacheScope,
) : SeekableGatewayMediaReader {
    private val path = GatewayFileClient.normalizePath(rawPath)

    override suspend fun read(
        position: Long,
        byteCount: Int,
    ): GatewayMediaRangeResult {
        if (path == null) {
            return GatewayMediaRangeResult.Failure(
                IllegalArgumentException("not an absolute gateway path"),
            )
        }
        if (position < 0 || byteCount !in 1..SeekableGatewayMediaReader.MAX_RANGE_BYTES) {
            return GatewayMediaRangeResult.Failure(IllegalArgumentException("invalid media byte range"))
        }
        if (!isCurrent()) return GatewayMediaRangeResult.Stale

        val end =
            runCatching { Math.addExact(position, byteCount.toLong() - 1L) }
                .getOrElse {
                    return GatewayMediaRangeResult.Failure(
                        IllegalArgumentException("media byte range overflow"),
                    )
                }
        var response: Response<ResponseBody>? = null
        return try {
            response = service.streamManagedFileRange(path, "bytes=$position-$end")
            if (!isCurrent()) return GatewayMediaRangeResult.Stale
            when (response.code()) {
                401 -> {
                    AuthSessionState.requireSignIn()
                    GatewayMediaRangeResult.Unauthorized
                }

                403 -> GatewayMediaRangeResult.Forbidden
                404 -> GatewayMediaRangeResult.NotFound
                413 -> GatewayMediaRangeResult.TooLarge
                206 -> response.toRangeSuccess(position, byteCount)
                else -> GatewayMediaRangeResult.Failure(IOException("HTTP ${response.code()}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            GatewayMediaRangeResult.Failure(e)
        } finally {
            response?.body()?.close()
            response?.errorBody()?.close()
        }
    }

    override fun isCurrent(): Boolean = currentScope() == scope

    private fun Response<ResponseBody>.toRangeSuccess(
        requestedPosition: Long,
        requestedCount: Int,
    ): GatewayMediaRangeResult {
        val body = body() ?: return GatewayMediaRangeResult.Failure(IOException("Empty range response body"))
        val bytes =
            body.readBytesLimited(requestedCount.toLong())
                ?: return GatewayMediaRangeResult.TooLarge
        val contentRange = headers()["Content-Range"]
        val match =
            contentRange?.let { CONTENT_RANGE.matchEntire(it.trim()) }
                ?: return GatewayMediaRangeResult.Failure(IOException("Invalid Content-Range"))
        val start = match.groupValues[1].toLongOrNull()
        val end = match.groupValues[2].toLongOrNull()
        if (start != requestedPosition || end == null || end < start || end - start + 1L != bytes.size.toLong()) {
            return GatewayMediaRangeResult.Failure(IOException("Mismatched Content-Range"))
        }
        val totalLength = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        if (totalLength != null && totalLength <= end) {
            return GatewayMediaRangeResult.Failure(IOException("Mismatched Content-Range total"))
        }
        val mimeType = headers()["Content-Type"]?.substringBefore(';')?.trim()?.takeIf(String::isNotBlank)
        return GatewayMediaRangeResult.Success(bytes, totalLength, mimeType)
    }

    companion object {
        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
    }
}

internal sealed interface GatewayMediaRangeResult {
    data class Success(
        val bytes: ByteArray,
        val totalLength: Long?,
        val mimeType: String?,
    ) : GatewayMediaRangeResult

    data object Stale : GatewayMediaRangeResult

    data object Unauthorized : GatewayMediaRangeResult

    data object Forbidden : GatewayMediaRangeResult

    data object NotFound : GatewayMediaRangeResult

    data object TooLarge : GatewayMediaRangeResult

    data class Failure(val throwable: Throwable) : GatewayMediaRangeResult
}
