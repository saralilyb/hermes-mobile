package com.m57.hermescontrol.ui.common

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.data.model.ManagedFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureGatewayMediaRoutingTest {
    @Test
    fun `gateway audio and video route to secure player`() {
        listOf("audio/mpeg", "video/mp4").forEach { mime ->
            val attachment = Attachment("/tmp/media", "media", mime, source = AttachmentSource.GATEWAY)

            assertTrue(routeAttachmentOpen(attachment) is SecureMediaOpenRoute.Player)
        }
    }

    @Test
    fun `gateway images and documents retain downloaded open route`() {
        listOf("image/png", "application/pdf").forEach { mime ->
            val attachment = Attachment("/tmp/file", "file", mime, source = AttachmentSource.GATEWAY)

            assertEquals(SecureMediaOpenRoute.DownloadAndOpen, routeAttachmentOpen(attachment))
        }
    }

    @Test
    fun `local audio and video retain external open route`() {
        listOf("audio/ogg", "video/webm").forEach { mime ->
            val attachment = Attachment("content://picked/media", "media", mime, source = AttachmentSource.LOCAL)

            assertEquals(SecureMediaOpenRoute.OpenLocal, routeAttachmentOpen(attachment))
        }
    }

    @Test
    fun `gateway generic mime falls back to extension and keeps video surface`() {
        val attachment =
            Attachment(
                uri = "/tmp/movie.mp4",
                name = "movie.mp4",
                mimeType = "application/octet-stream",
                source = AttachmentSource.GATEWAY,
            )

        val route = routeAttachmentOpen(attachment) as SecureMediaOpenRoute.Player
        assertEquals(SecureGatewayMediaKind.VIDEO, route.request.kind)
        assertTrue(route.request.isVideo)
    }

    @Test
    fun `managed gateway audio and video route to secure player while other files download`() {
        val audio = ManagedFileEntry(name = "song.mp3", path = "/tmp/song.mp3", mimeType = "audio/mpeg")
        val video = ManagedFileEntry(name = "movie.mp4", path = "/tmp/movie.mp4", mimeType = "video/mp4")
        val image = ManagedFileEntry(name = "image.png", path = "/tmp/image.png", mimeType = "image/png")

        assertTrue(routeManagedFileOpen(audio) is SecureMediaOpenRoute.Player)
        assertTrue(routeManagedFileOpen(video) is SecureMediaOpenRoute.Player)
        assertEquals(SecureMediaOpenRoute.DownloadAndOpen, routeManagedFileOpen(image))
    }

    @Test
    fun `managed media extension routes securely when server omits mime type`() {
        val entry = ManagedFileEntry(name = "movie.mp4", path = "/tmp/movie.mp4", mimeType = null)

        assertTrue(routeManagedFileOpen(entry) is SecureMediaOpenRoute.Player)
    }

    @Test
    fun `generic mime falls back to extension and preserves video kind`() {
        listOf(null, "", "application/octet-stream").forEach { mime ->
            val entry = ManagedFileEntry(name = "movie.mp4", path = "/tmp/movie.mp4", mimeType = mime)

            val route = routeManagedFileOpen(entry) as SecureMediaOpenRoute.Player
            assertEquals(SecureGatewayMediaKind.VIDEO, route.request.kind)
            assertTrue(route.request.isVideo)
        }
    }
}
