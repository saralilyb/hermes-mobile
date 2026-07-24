package com.m57.hermescontrol.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReasoningCardPlacementTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun reasoningCard_isInsideAssistantBubble() {
        composeTestRule.setContent {
            MaterialTheme {
                ChatBubble(
                    message =
                        ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = "Answer",
                            reasoningText = "Reasoning",
                        ),
                    isDarkTheme = true,
                )
            }
        }

        composeTestRule
            .onNode(
                hasTestTag("reasoning_card") and
                    hasAnyAncestor(hasTestTag("chat_bubble_assistant")),
            ).assertExists()
    }
}
