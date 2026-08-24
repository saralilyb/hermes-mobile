package com.m57.hermescontrol.ui.common

import android.media.MediaDataSource
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.data.model.ManagedFileEntry
import com.m57.hermescontrol.data.remote.GatewayMediaRangeResult
import com.m57.hermescontrol.data.remote.SeekableGatewayMediaReader
import java.io.IOException
import java.net.URLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

enum class SecureGatewayMediaKind {
    AUDIO,
    VIDEO,
}

data class SecureGatewayMediaRequest(
    val path: String,
    val title: String,
    val mimeType: String,
    val kind: SecureGatewayMediaKind,
) {
    val isVideo: Boolean = kind == SecureGatewayMediaKind.VIDEO
}

internal sealed interface SecureMediaOpenRoute {
    data class Player(val request: SecureGatewayMediaRequest) : SecureMediaOpenRoute

    data object DownloadAndOpen : SecureMediaOpenRoute

    data object OpenLocal : SecureMediaOpenRoute
}

internal fun routeAttachmentOpen(attachment: Attachment): SecureMediaOpenRoute {
    if (attachment.source == AttachmentSource.LOCAL) return SecureMediaOpenRoute.OpenLocal
    val path = attachment.gatewayPath ?: attachment.uri
    val kind = mediaKind(attachment.mimeType, path)
        ?: return SecureMediaOpenRoute.DownloadAndOpen
    return SecureMediaOpenRoute.Player(SecureGatewayMediaRequest(path, attachment.name, attachment.mimeType, kind))
}

internal fun routeManagedFileOpen(entry: ManagedFileEntry): SecureMediaOpenRoute {
    val mimeType =
        entry.mimeType?.takeIf { it.isNotBlank() }
            ?: URLConnection.guessContentTypeFromName(entry.name).orEmpty()
    val kind = mediaKind(mimeType, entry.path)
    return if (kind != null) {
        SecureMediaOpenRoute.Player(SecureGatewayMediaRequest(entry.path, entry.name, mimeType, kind))
    } else {
        SecureMediaOpenRoute.DownloadAndOpen
    }
}

private fun mediaKind(mimeType: String, path: String): SecureGatewayMediaKind? =
    when {
        mimeType.startsWith("audio/", ignoreCase = true) -> SecureGatewayMediaKind.AUDIO
        mimeType.startsWith("video/", ignoreCase = true) -> SecureGatewayMediaKind.VIDEO
        else -> when (path.substringBefore('?').substringAfterLast('.').lowercase()) {
            "mp3", "m4a", "aac", "ogg", "oga", "wav", "flac", "opus" -> SecureGatewayMediaKind.AUDIO
            "mp4", "m4v", "webm", "3gp", "mkv" -> SecureGatewayMediaKind.VIDEO
            else -> null
        }
    }

/** Blocking range mapper used only on MediaPlayer's data-source worker threads. */
internal class GatewayMediaDataReader(
    private val session: SeekableGatewayMediaReader,
) {
    private val closed = AtomicBoolean(false)
    private val knownSize = AtomicLong(-1L)
    private val activeRead = AtomicReference<Job?>(null)

    fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        if (closed.get()) throw IOException("Media data source is closed")
        if (position < 0 || offset < 0 || size < 0 || offset > buffer.size - size) {
            throw IOException("Invalid media read")
        }
        if (size == 0) return 0
        if (knownSize.get() >= 0 && position >= knownSize.get()) return -1
        val requested = size.coerceAtMost(SeekableGatewayMediaReader.MAX_RANGE_BYTES)
        val readJob = Job()
        if (!activeRead.compareAndSet(null, readJob)) throw IOException("Concurrent media read")
        if (closed.get()) readJob.cancel()
        val result =
            try {
                runBlocking(Dispatchers.IO + readJob) { session.read(position, requested) }
            } catch (_: CancellationException) {
                throw IOException("Media data source is closed")
            } finally {
                activeRead.compareAndSet(readJob, null)
            }
        return when (result) {
            is GatewayMediaRangeResult.Success -> {
                if (closed.get()) throw IOException("Media data source is closed")
                result.totalLength?.let(knownSize::set)
                if (result.bytes.isEmpty()) {
                    -1
                } else {
                    val count = result.bytes.size.coerceAtMost(size)
                    result.bytes.copyInto(buffer, offset, 0, count)
                    count
                }
            }

            GatewayMediaRangeResult.Stale -> throw IOException("Playback credentials changed")
            GatewayMediaRangeResult.Unauthorized -> throw IOException("Playback session expired")
            GatewayMediaRangeResult.Forbidden -> throw IOException("Playback access denied")
            GatewayMediaRangeResult.NotFound -> throw IOException("Media was not found")
            GatewayMediaRangeResult.TooLarge -> throw IOException("Media range was too large")
            is GatewayMediaRangeResult.Failure -> throw IOException("Media read failed", result.throwable)
        }
    }

    fun size(): Long = knownSize.get()

    fun close() {
        if (closed.compareAndSet(false, true)) activeRead.get()?.cancel()
    }

    fun isCurrent(): Boolean = !closed.get() && session.isCurrent()
}

internal class GatewayMediaDataSource(
    session: SeekableGatewayMediaReader,
) : MediaDataSource() {
    private val reader = GatewayMediaDataReader(session)

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        reader.readAt(position, buffer, offset, size)

    override fun getSize(): Long = reader.size()

    override fun close() = reader.close()

    fun isCurrent(): Boolean = reader.isCurrent()
}
