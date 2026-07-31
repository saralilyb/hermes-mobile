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
