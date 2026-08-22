package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageSyncTest {
    @Test
    fun sameNameTools_matchByCallIdWhenServerResultsArriveOutOfOrder() {
        val current =
            listOf(
                runningTool(id = "local-a", callId = "call-a", content = "same-content"),
                runningTool(id = "local-b", callId = "call-b", content = "same-content"),
            )
        val incoming =
            listOf(
                completedTool(id = "rest-session-10", callId = "call-b", content = "same-content"),
                completedTool(id = "rest-session-11", callId = "call-a", content = "same-content"),
            )

        val merged =
            mergeSyncedMessages(
                current = current,
                incoming = incoming,
                isServerMessage = { it.startsWith("rest-session-") },
            )

        assertEquals(listOf("call-a", "call-b"), merged.map { it.toolCallId })
        assertEquals(listOf(ToolStatus.COMPLETED, ToolStatus.COMPLETED), merged.map { it.toolStatus })
    }

    @Test
    fun ambiguousSameNameTools_withoutCallIdsAreNotCrossPaired() {
        val current =
            listOf(
                runningTool(id = "local-a", callId = null),
                runningTool(id = "local-b", callId = null),
            )
        val incoming = listOf(completedTool(id = "rest-session-10", callId = null))

        val merged =
            mergeSyncedMessages(
                current = current,
                incoming = incoming,
                isServerMessage = { it.startsWith("rest-session-") },
            )

        assertEquals(listOf("local-a", "local-b", "rest-session-10"), merged.map { it.id })
    }

    @Test
    fun stableRestRowReplacesCachedRowWithDisplayKind() {
        val current =
            listOf(
                ChatMessage(
                    id = "rest-session-1",
                    role = MessageRole.USER,
                    content = "model changed",
                ),
            )
        val incoming = listOf(current.single().copy(displayKind = "model_switch"))

        val merged =
            mergeSyncedMessages(
                current = current,
                incoming = incoming,
                isServerMessage = { it.startsWith("rest-session-") },
            )

        assertEquals("model_switch", merged.single().displayKind)
    }

    @Test
    fun attachmentEnrichedRestRowReplacesOptimisticUserMessage() {
        val current =
            listOf(
                userMessage(
                    id = "local-1",
                    content = "summarize this document",
                ),
            )
        val incoming =
            listOf(
                userMessage(
                    id = "rest-session-1",
                    content =
                        "@file:report.pdf\n\n" +
                            "summarize this document",
                ),
            )

        val merged = merge(current, incoming)

        assertEquals(listOf("rest-session-1"), merged.map { it.id })
    }

    @Test
    fun attachmentEnrichmentDoesNotCollapseDifferentCaptions() {
        val current = listOf(userMessage(id = "local-1", content = "first"))
        val incoming =
            listOf(
                userMessage(
                    id = "rest-session-1",
                    content = "@image:/tmp/a.jpg\n[screenshot]\n\nsecond",
                ),
            )

        val merged = merge(current, incoming)

        assertEquals(
            listOf("local-1", "rest-session-1"),
            merged.map { it.id },
        )
    }

    @Test
    fun attachmentMarkersInAssistantTextAreNotNormalized() {
        val current =
            listOf(
                ChatMessage(
                    id = "local-1",
                    role = MessageRole.ASSISTANT,
                    content = "caption",
                ),
            )
        val incoming =
            listOf(
                ChatMessage(
                    id = "rest-session-1",
                    role = MessageRole.ASSISTANT,
                    content = "@file:report.pdf\ncaption",
                ),
            )

        val merged = merge(current, incoming)

        assertEquals(
            listOf("local-1", "rest-session-1"),
            merged.map { it.id },
        )
    }

    @Test
    fun twoAttachmentOnlyMessagesRemainDistinct() {
        val current =
            listOf(
                userMessage(id = "local-1", content = ""),
                userMessage(id = "local-2", content = ""),
            )
        val incoming =
            listOf(
                userMessage(id = "rest-session-1", content = "@file:a.pdf"),
                userMessage(id = "rest-session-2", content = "@file:b.pdf"),
            )

        val merged = merge(current, incoming)

        assertEquals(
            listOf("rest-session-1", "rest-session-2"),
            merged.map { it.id },
        )
    }

    private fun merge(
        current: List<ChatMessage>,
        incoming: List<ChatMessage>,
    ): List<ChatMessage> =
        mergeSyncedMessages(
            current = current,
            incoming = incoming,
            isServerMessage = { it.startsWith("rest-session-") },
        )

    private fun userMessage(
        id: String,
        content: String,
    ) = ChatMessage(
        id = id,
        role = MessageRole.USER,
        content = content,
    )

    private fun runningTool(
        id: String,
        callId: String?,
        content: String = "starting $id",
    ) = ChatMessage(
        id = id,
        role = MessageRole.TOOL,
        content = content,
        toolName = "terminal",
        toolCallId = callId,
        toolStatus = ToolStatus.RUNNING,
    )

    private fun completedTool(
        id: String,
        callId: String?,
        content: String = "finished $id",
    ) = ChatMessage(
        id = id,
        role = MessageRole.TOOL,
        content = content,
        toolName = "terminal",
        toolCallId = callId,
        toolStatus = ToolStatus.COMPLETED,
    )
}
