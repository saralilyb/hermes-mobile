package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.AuthSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern
import kotlin.coroutines.coroutineContext

/** Authenticated, profile-safe access to gateway-managed files. */
object GatewayFileClient {
    internal const val CACHE_TTL_MS = 10 * 60 * 1000L
    internal const val MAX_CACHE_BYTES = 256L * 1024L * 1024L
    internal const val MAX_CACHE_FILES = 64

    // A fixed stripe set avoids both duplicate same-key downloads and an
    // unbounded process-lifetime map of hashed gateway paths.
    private val keyLocks = Array(64) { Mutex() }

    internal fun normalizePath(raw: String): String? {
        val trimmed = raw.trim().removeSurrounding("`").removeSurrounding("\"").removeSurrounding("'")
        if (trimmed == "~" || trimmed.startsWith("~/")) return trimmed
        if (!trimmed.startsWith("/") && !Pattern.compile("^[A-Za-z]:[/\\\\]").matcher(trimmed).find()) return null
        return trimmed
    }

    internal fun classifyStatus(code: Int): GatewayFileResult? =
        when (code) {
            401 -> GatewayFileResult.Unauthorized
            403 -> GatewayFileResult.Forbidden
            404 -> GatewayFileResult.NotFound
            413 -> GatewayFileResult.TooLarge
            else -> null
        }

    /** Snapshot the complete authentication boundary before starting IO. */
    internal fun currentScope(): MediaCacheScope {
        val gated = AuthManager.isGatedMode()
        return MediaCacheScope(
            profileId = AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
            canonicalEndpoint = AuthManager.endpointForBuild().baseUrl.toString(),
            authMode = if (gated) "gated-cookie" else "direct-token",
            credentialFingerprint =
                fingerprint(
                    if (gated) AuthManager.getSessionCookie().orEmpty() else AuthManager.getToken().orEmpty(),
                ),
        )
    }

    suspend fun fetch(
        path: String,
        cacheDir: File,
    ): GatewayFileResult = fetch(path, ApiClient.hermesApi, GatewayMediaCache(cacheDir), currentScope())

    internal suspend fun fetch(
        path: String,
        service: HermesApiService,
        cache: GatewayMediaCache,
        scope: MediaCacheScope,
    ): GatewayFileResult =
        withContext(Dispatchers.IO) {
            val normalized =
                normalizePath(path)
                    ?: return@withContext GatewayFileResult.Failure(
                        IllegalArgumentException("not an absolute gateway path"),
                    )
            val key = cache.key(scope, normalized)
            val lock = keyLocks[(key.hashCode() and Int.MAX_VALUE) % keyLocks.size]
            lock.withLock {
                cache.fresh(key)?.let { cached ->
                    return@withLock GatewayFileResult.Success(
                        GatewayFile(fileNameFromPath(normalized), mimeFor(normalized), cached),
                    )
                }
                fetchNetwork(normalized, service, cache, key)
            }
        }

    private suspend fun fetchNetwork(
        path: String,
        service: HermesApiService,
        cache: GatewayMediaCache,
        key: String,
    ): GatewayFileResult {
        var response: Response<ResponseBody>? = null
        return try {
            response = service.downloadManagedFile(path)
            classifyStatus(response.code())?.let {
                response.errorBody()?.close()
                if (it is GatewayFileResult.Unauthorized) AuthSessionState.requireSignIn()
                return it
            }
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                return GatewayFileResult.Failure(IOException("HTTP ${response.code()}"))
            }
            val body = response.body() ?: return GatewayFileResult.Failure(IOException("Empty response body"))
            val name = response.headers()["Content-Disposition"]?.let(::parseFilename) ?: fileNameFromPath(path)
            val mime =
                response.headers()["Content-Type"]?.substringBefore(';')?.trim()?.takeIf(String::isNotBlank)
                    ?: mimeFor(path)
            val file = cache.write(key, body)
            GatewayFileResult.Success(GatewayFile(name, mime, file))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            GatewayFileResult.Failure(e)
        } finally {
            response?.body()?.close()
            response?.errorBody()?.close()
        }
    }

    internal suspend fun copyChunked(
        input: java.io.InputStream,
        output: java.io.OutputStream,
    ) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }

    internal fun parseFilename(header: String): String? {
        val match = FILENAME_RE.find(header) ?: return null
        val raw = match.groupValues[1].takeIf(String::isNotBlank) ?: return null
        return runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrDefault(raw)
    }

    private fun fileNameFromPath(path: String): String =
        path.split('/', '\\').lastOrNull()?.takeIf(String::isNotBlank) ?: "file"

    private fun mimeFor(path: String): String =
        URLConnection.guessContentTypeFromName(fileNameFromPath(path)) ?: "application/octet-stream"

    internal fun fingerprint(value: String): String = sha256(value).take(24)

    internal fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") {
            "%02x".format(it)
        }

    private val FILENAME_RE = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)
}

data class MediaCacheScope(
    val profileId: String,
    val canonicalEndpoint: String,
    val authMode: String,
    val credentialFingerprint: String,
)

/** Small deterministic cache seam; all artifacts live below a hashed auth scope. */
internal class GatewayMediaCache(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = GatewayFileClient.MAX_CACHE_BYTES,
    private val maxFiles: Int = GatewayFileClient.MAX_CACHE_FILES,
) {
    fun key(
        scope: MediaCacheScope,
        path: String,
    ): String =
        GatewayFileClient.sha256(
            listOf(
                scope.profileId,
                scope.canonicalEndpoint,
                scope.authMode,
                scope.credentialFingerprint,
                path,
            ).joinToString("\u0000"),
        )

    fun fresh(key: String): File? =
        target(key).takeIf { it.isFile && now() - it.lastModified() < GatewayFileClient.CACHE_TTL_MS }

    suspend fun write(
        key: String,
        body: ResponseBody,
    ): File {
        root.mkdirs()
        val target = target(key)
        val temp = File(root, ".$key.tmp-${UUID.randomUUID()}")
        try {
            body.byteStream().use {
                    input ->
                temp.outputStream().use { output -> GatewayFileClient.copyChunked(input, output) }
            }
            moveAtomically(temp, target)
            evict(target)
            return target
        } catch (e: CancellationException) {
            temp.delete()
            throw e
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    private fun target(key: String) = File(root, "$key.media")

    private fun moveAtomically(
        source: File,
        target: File,
    ) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun evict(protected: File) {
        root.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sortedWith(
            compareBy<File> { it.lastModified() }.thenBy { it.name },
        )?.let { files ->
            var bytes = files.sumOf(File::length)
            var count = files.size
            files.forEach { file ->
                if (file != protected && (bytes > maxBytes || count > maxFiles)) {
                    val removedBytes = file.length()
                    if (file.delete()) {
                        bytes -= removedBytes
                        count--
                    }
                }
            }
        }
        val staleTempCutoff = now() - GatewayFileClient.CACHE_TTL_MS
        root.listFiles()
            ?.filter { it.isFile && it.name.startsWith(".") && it.lastModified() < staleTempCutoff }
            ?.forEach(File::delete)
    }
}

data class GatewayFile(val name: String, val mimeType: String, val cacheFile: File)

internal const val MAX_IN_MEMORY_MEDIA_BYTES = 25L * 1024L * 1024L

internal fun ResponseBody.readBytesLimited(maxBytes: Long = MAX_IN_MEMORY_MEDIA_BYTES): ByteArray? =
    byteStream().use { it.readBytesLimited(maxBytes) }

internal fun InputStream.readBytesLimited(maxBytes: Long = MAX_IN_MEMORY_MEDIA_BYTES): ByteArray? {
    val output = java.io.ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
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

sealed interface GatewayFileResult {
    data class Success(val file: GatewayFile) : GatewayFileResult

    data object NotFound : GatewayFileResult

    data object Forbidden : GatewayFileResult

    data object TooLarge : GatewayFileResult

    data object Unauthorized : GatewayFileResult

    data class Failure(val throwable: Throwable) : GatewayFileResult
}
