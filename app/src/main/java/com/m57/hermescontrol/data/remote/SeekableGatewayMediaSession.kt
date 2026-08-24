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
internal class SeekableGatewayMediaSession(
    rawPath: String,
    private val service: HermesApiService,
    private val scope: MediaCacheScope,
    private val currentScope: () -> MediaCacheScope,
) {
    private val path = GatewayFileClient.normalizePath(rawPath)

    suspend fun read(
        position: Long,
        byteCount: Int,
    ): GatewayMediaRangeResult {
        if (path == null) {
            return GatewayMediaRangeResult.Failure(
                IllegalArgumentException("not an absolute gateway path"),
            )
        }
        if (position < 0 || byteCount !in 1..MAX_RANGE_BYTES) {
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
            response = service.downloadManagedFileRange(path, "bytes=$position-$end")
            if (!isCurrent()) return GatewayMediaRangeResult.Stale
            when (response.code()) {
                401 -> {
                    AuthSessionState.requireSignIn()
                    GatewayMediaRangeResult.Unauthorized
                }

                403 -> GatewayMediaRangeResult.Forbidden
                404 -> GatewayMediaRangeResult.NotFound
                413 -> GatewayMediaRangeResult.TooLarge
                206 -> response.toRangeSuccess()
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

    private fun isCurrent(): Boolean = currentScope() == scope

    private fun Response<ResponseBody>.toRangeSuccess(): GatewayMediaRangeResult {
        val body = body() ?: return GatewayMediaRangeResult.Failure(IOException("Empty range response body"))
        val bytes =
            body.readBytesLimited(MAX_RANGE_BYTES.toLong())
                ?: return GatewayMediaRangeResult.TooLarge
        val totalLength = headers()["Content-Range"]?.substringAfterLast('/')?.toLongOrNull()
        val mimeType = headers()["Content-Type"]?.substringBefore(';')?.trim()?.takeIf(String::isNotBlank)
        return GatewayMediaRangeResult.Success(bytes, totalLength, mimeType)
    }

    companion object {
        internal const val MAX_RANGE_BYTES = 1024 * 1024
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
