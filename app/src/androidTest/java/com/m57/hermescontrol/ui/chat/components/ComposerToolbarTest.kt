package com.m57.hermescontrol.ui.chat.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class ComposerToolbarTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun modelWithoutReasoningDisablesReasoningChip() {
        setToolbar(supportsReasoning = false)

        composeTestRule.onNodeWithTag("reasoning_chip").assertIsNotEnabled()
        composeTestRule.onNodeWithText("No reasoning").assertExists()
    }

    @Test
    fun modelWithRequiredReasoningDisablesNoneOption() {
        setToolbar(canDisableReasoning = false)

        composeTestRule.onNodeWithTag("reasoning_chip").performClick()
        composeTestRule.onNodeWithText("reasoning always on").assertExists()
        composeTestRule.onNodeWithText("None").assertIsNotEnabled()
    }

    @Test
    fun missingCapabilitiesKeepReasoningOptionsAvailable() {
        setToolbar()

        composeTestRule.onNodeWithTag("reasoning_chip").performClick()
        composeTestRule.onNodeWithText("None").assertIsEnabled()
    }

    private fun setToolbar(
        canDisableReasoning: Boolean? = null,
        supportsReasoning: Boolean? = null,
    ) {
        composeTestRule.setContent {
            ComposerToolbar(
                isConnected = true,
                currentSessionModel = "provider/model",
                reasoningLevel = "medium",
                isListening = false,
                onAttachTap = {},
                onModelTap = {},
                onReasoningSelected = {},
                onMicTap = {},
                canDisableReasoning = canDisableReasoning,
                supportsReasoning = supportsReasoning,
            )
        }
    }
}
