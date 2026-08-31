package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal const val MAX_PENDING_ATTACHMENTS = 10

/**
 * Owns pending-attachment state for [ChatViewModel], extracted to keep the
 * god-object focused on messaging/session/streaming concerns.
 *
 * Behavior is identical to the original inline implementation: the three
 * methods only mutate `_uiState.pendingAttachments`. Read sites (e.g. sendMessage)
 * continue to read `pendingAttachments` straight off the shared uiState flow.
 */
class ChatAttachmentsDelegate(
    private val uiState: MutableStateFlow<ChatUiState>,
) {
    fun addAttachment(
        uri: String,
        name: String,
        mimeType: String,
        size: Long,
    ) = addAttachments(
        listOf(
            Attachment(
                uri = uri,
                name = name,
                mimeType = mimeType,
                size = size,
            ),
        ),
    )

    fun addAttachments(attachments: List<Attachment>) {
        if (attachments.isEmpty()) return
        uiState.update { state ->
            val seenUris = state.pendingAttachments.mapTo(mutableSetOf(), Attachment::uri)
            val remainingCapacity = (MAX_PENDING_ATTACHMENTS - state.pendingAttachments.size).coerceAtLeast(0)
            val additions = attachments.filter { seenUris.add(it.uri) }.take(remainingCapacity)
            if (additions.isEmpty()) state else state.copy(pendingAttachments = state.pendingAttachments + additions)
        }
    }

    fun removeAttachment(index: Int) {
        uiState.update { state ->
            state.copy(
                pendingAttachments =
                    state.pendingAttachments.toMutableList().apply {
                        if (index in indices) removeAt(index)
                    },
            )
        }
    }

    fun clearAttachments() {
        uiState.update { it.copy(pendingAttachments = emptyList()) }
    }
}
