package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemTimelineEventTest {
    @Test
    fun everyTaggedMessageIsTimelineEventIncludingUnknownKind() {
        val event = classifySystemTimelineEvent(message("hello", displayKind = "future_kind"))
        assertEquals("future_kind", event?.kind)
    }

    @Test
    fun maxIterationsNudgeIsTimelineEvent() {
        val content =
            "You've reached the maximum number of tool-calling iterations allowed. " +
                "Please provide a final response."
        assertEquals(
            "max_iterations_reached",
            classifySystemTimelineEvent(message(content))?.kind,
        )
    }

    @Test
    fun genuineUserMessageRemainsUserMessage() {
        assertNull(classifySystemTimelineEvent(message("Please explain [CONTEXT clues]")))
        assertNull(classifySystemTimelineEvent(message("[System: quoted text]")))
    }

    @Test
    fun nonUserPrefixIsNotReclassifiedWithoutTag() {
        assertNull(
            classifySystemTimelineEvent(
                message("[System: quoted text]", role = MessageRole.ASSISTANT),
            ),
        )
    }

    private fun message(
        content: String,
        role: MessageRole = MessageRole.USER,
        displayKind: String? = null,
    ) = ChatMessage(role = role, content = content, displayKind = displayKind)
}
