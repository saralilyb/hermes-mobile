package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSearchControllerTest {
    private val controller = ChatSearchController()

    @Test
    fun `search includes visible prose occurrences and excludes hidden payloads`() {
        val messages =
            listOf(
                ChatMessage(role = MessageRole.USER, content = "Deploy deploy"),
                ChatMessage(role = MessageRole.TOOL, content = "deploy secret payload"),
                ChatMessage(role = MessageRole.SYSTEM, content = "deploy status"),
                ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = "Done",
                    reasoningText = "deploy reasoning",
                ),
            )

        assertEquals(listOf(0, 0), controller.findMatches(messages, "deploy"))
        assertEquals(listOf(3), controller.findMatches(messages, "done"))
    }

    @Test
    fun `search caps degenerate occurrence lists`() {
        val message = ChatMessage(role = MessageRole.USER, content = "a ".repeat(2_000))

        assertEquals(
            ChatSearchController.MAX_SEARCH_MATCHES,
            controller.findMatches(listOf(message), "a").size,
        )
    }
}
