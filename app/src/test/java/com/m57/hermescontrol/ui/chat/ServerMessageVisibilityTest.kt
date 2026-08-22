package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerMessageVisibilityTest {
    @Test
    fun emptyAssistantToolPlaceholderIsNotDisplayable() {
        assertFalse(
            isDisplayableServerMessage(
                SessionMessage(
                    role = "assistant",
                    content = "",
                ),
            ),
        )
    }

    @Test
    fun reasoningOnlyAssistantRemainsDisplayable() {
        assertTrue(
            isDisplayableServerMessage(
                SessionMessage(
                    role = "assistant",
                    content = "",
                    reasoning = "thinking trace",
                ),
            ),
        )
    }

    @Test
    fun assistantContentAndNonAssistantRowsRemainDisplayable() {
        assertTrue(
            isDisplayableServerMessage(
                SessionMessage(
                    role = "assistant",
                    content = "answer",
                ),
            ),
        )
        assertTrue(
            isDisplayableServerMessage(
                SessionMessage(
                    role = "user",
                    content = "",
                ),
            ),
        )
    }
}
