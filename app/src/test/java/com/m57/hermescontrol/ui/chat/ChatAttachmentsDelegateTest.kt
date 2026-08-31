package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAttachmentsDelegateTest {
    private val uiState = MutableStateFlow(ChatUiState())
    private val delegate = ChatAttachmentsDelegate(uiState)

    @Test
    fun addAttachmentsPreservesOrderAndSkipsDuplicateUris() {
        delegate.addAttachment("content://existing", "existing", "image/png", 1L)

        delegate.addAttachments(
            listOf(
                attachment("content://second", "second"),
                attachment("content://existing", "replacement"),
                attachment("content://third", "third"),
                attachment("content://second", "duplicate"),
            ),
        )

        assertEquals(
            listOf("content://existing", "content://second", "content://third"),
            uiState.value.pendingAttachments.map(Attachment::uri),
        )
    }

    @Test
    fun addAttachmentsKeepsFirstMetadataForDuplicateUri() {
        delegate.addAttachments(
            listOf(
                attachment("content://same", "first", size = 10L),
                attachment("content://same", "later", size = 20L),
            ),
        )

        assertEquals("first", uiState.value.pendingAttachments.single().name)
        assertEquals(10L, uiState.value.pendingAttachments.single().size)
    }

    @Test
    fun addAttachmentsWithEmptyBatchIsNoOp() {
        delegate.addAttachment("content://existing", "existing", "image/png", 1L)
        val before = uiState.value

        delegate.addAttachments(emptyList())

        assertTrue(uiState.value === before)
    }

    @Test
    fun addAttachmentUsesBatchDedupe() {
        delegate.addAttachment("content://same", "first", "image/png", 10L)
        delegate.addAttachment("content://same", "later", "image/jpeg", 20L)

        assertEquals(listOf("first"), uiState.value.pendingAttachments.map(Attachment::name))
    }

    private fun attachment(
        uri: String,
        name: String,
        size: Long = 1L,
    ) = Attachment(uri = uri, name = name, mimeType = "image/png", size = size)
}
