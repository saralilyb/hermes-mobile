package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Client for the gateway's managed-files download endpoint
 * (`GET /api/files/download?path=<enc>`), which streams the raw
 * bytes of any file that lives on the *gateway* host (images, audio, video,
 * CSV, PDF, arbitrary attachments).
 *
 * This is the mobile equivalent of the desktop app's
 * `mediaExternalUrl()` (`apps/desktop/src/lib/media.ts`): a gateway-local
 * path is fetched through [ApiClient]'s authenticated Retrofit service. This
 * keeps credentials in the Bearer header or dashboard cookie, never the URL.
 *
 * Server-side guards still apply: path resolution (`_resolve_managed_path`), a
 * sensitive-path denylist (403), and a size cap
 * (`_MANAGED_FILE_MAX_BYTES`, 413).
 *
 * Mobile-only, backend untouched.
 */
object GatewayFileClient {
    /**
     * Strip surrounding quotes/backticks and require an absolute host path or
     * `~/...`. Tilde paths stay unexpanded so the gateway, not Android, resolves
     * the gateway user's home directory.
     */
    internal fun normalizePath(raw: String): String? {
        val trimmed =
            raw
                .trim()
                .removeSurrounding("`")
                .removeSurrounding("\"")
                .removeSurrounding("'")
        if (trimmed == "~" || trimmed.startsWith("~/")) return trimmed
        if (!trimmed.startsWith("/") &&
            !Pattern.compile("^[A-Za-z]:[/\\\\]").matcher(trimmed).find()
        ) {
            return null
        }
        return trimmed
    }

    /** Map an HTTP status to a non-success result; `null` means "let the
     * caller treat the body as a successful file." */
    internal fun classifyStatus(code: Int): GatewayFileResult? =
        when (code) {
            401 -> GatewayFileResult.Unauthorized
            403 -> GatewayFileResult.Forbidden
            404 -> GatewayFileResult.NotFound
            413 -> GatewayFileResult.TooLarge
            else -> null
        }

    /** Fetch through the current authenticated Retrofit service. */
    suspend fun fetch(path: String): GatewayFileResult = fetch(path, ApiClient.hermesApi)

    /** Fetch through an explicit service for deterministic tests. */
    internal suspend fun fetch(
        path: String,
        service: HermesApiService,
    ): GatewayFileResult =
        withContext(Dispatchers.IO) {
            fetchOnIo(path, service)
        }

    private suspend fun fetchOnIo(
        path: String,
        service: HermesApiService,
    ): GatewayFileResult {
        val normalized =
            normalizePath(path)
                ?: return GatewayFileResult.Failure(
                    IllegalArgumentException("not an absolute gateway path: $path"),
                )
        return try {
            val response = service.downloadManagedFile(normalized)
            classifyStatus(response.code())?.let {
                response.errorBody()?.close()
                if (it is GatewayFileResult.Unauthorized) {
                    AuthSessionState.requireSignIn()
                }
                return it
            }
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                return GatewayFileResult.Failure(IOException("HTTP ${response.code()}"))
            }
            val body =
                response.body()
                    ?: return GatewayFileResult.Failure(IOException("Empty response body"))
            val name =
                response.headers()["Content-Disposition"]?.let { parseFilename(it) }
                    ?: fileNameFromPath(normalized)
            val mime =
                response.headers()["Content-Type"]
                    ?.substringBefore(';')
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "application/octet-stream"
            val bytes = body.readBytesLimited() ?: return GatewayFileResult.TooLarge
            GatewayFileResult.Success(GatewayFile(name, mime, bytes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            GatewayFileResult.Failure(e)
        }
    }

    /** Best-effort filename pull from a `Content-Disposition: ...; filename="x"`
     * or `filename*=UTF-8''<pct-enc>` header. The captured value is URL-decoded
     * so `filename*=UTF-8''a%20b.png` yields `a b.png`. */
    internal fun parseFilename(header: String): String? {
        val m = FILENAME_RE.find(header) ?: return null
        val raw = m.groupValues[1].takeIf { it.isNotBlank() } ?: return null
        return runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrDefault(raw)
    }

    private fun fileNameFromPath(path: String): String =
        path
            .split('/', '\\')
            .lastOrNull()
            ?.takeIf { it.isNotBlank() } ?: "file"

    private val FILENAME_RE = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)
}

internal const val MAX_IN_MEMORY_MEDIA_BYTES = 25L * 1024L * 1024L

internal fun ResponseBody.readBytesLimited(maxBytes: Long = MAX_IN_MEMORY_MEDIA_BYTES): ByteArray? {
    val declaredLength = contentLength()
    if (declaredLength > maxBytes) {
        close()
        return null
    }

    val initialCapacity =
        declaredLength
            .takeIf { it in 1..maxBytes }
            ?.toInt()
            ?: DEFAULT_BUFFER_SIZE
    val output = ByteArrayOutputStream(initialCapacity)
    byteStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) return null
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}

internal fun InputStream.readBytesLimited(maxBytes: Long = MAX_IN_MEMORY_MEDIA_BYTES): ByteArray? {
    val output = ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) return null
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/** A file fetched from the gateway. */
data class GatewayFile(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GatewayFile) return false
        return name == other.name && mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var r = name.hashCode()
        r = 31 * r + mimeType.hashCode()
        r = 31 * r + bytes.contentHashCode()
        return r
    }
}

/** Outcome of [GatewayFileClient.fetch]. */
sealed interface GatewayFileResult {
    data class Success(
        val file: GatewayFile,
    ) : GatewayFileResult

    data object NotFound : GatewayFileResult // 404 — file missing on gateway

    data object Forbidden : GatewayFileResult // 403 — sensitive path denied

    data object TooLarge : GatewayFileResult // 413 — exceeds managed-file cap

    data object Unauthorized : GatewayFileResult // 401 — bad/expired token

    data class Failure(
        val throwable: Throwable,
    ) : GatewayFileResult // network / unexpected
}
