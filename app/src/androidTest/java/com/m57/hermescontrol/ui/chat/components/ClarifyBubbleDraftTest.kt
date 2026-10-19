package com.m57.hermescontrol.ui.chat.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.m57.hermescontrol.ui.chat.ClarifyQuestionUi
import com.m57.hermescontrol.ui.chat.ClarifyUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ClarifyBubbleDraftTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun partialFailureRetainsUnsentDraftButNewBindingClearsIt() {
        val first = ClarifyQuestionUi("q0", "First?")
        val unsent = ClarifyQuestionUi("q1", "Second?", choices = listOf("Keep"))
        var request by
            mutableStateOf(
                ClarifyUi(
                    text = "",
                    options = emptyList(),
                    clarifyId = "clarify-1",
                    questions = listOf(first, unsent),
                    sessionId = "session-1",
                    sourceProfileId = "profile-a",
                    connectionGeneration = 4,
                ),
            )
        composeTestRule.setContent {
            ClarifyBubble(request, onSubmit = { _, _ -> }, onDismiss = {})
        }

        composeTestRule.onNodeWithTag("clarify_choice_q1_Keep").performClick()
        composeTestRule.onNodeWithTag("clarify_text_q1").performTextInput("draft answer")
        composeTestRule.runOnIdle { request = request.copy(questions = listOf(unsent)) }

        composeTestRule.onNodeWithTag("clarify_choice_q1_Keep").assertIsSelected()
        composeTestRule.onNodeWithTag("clarify_text_q1").assertTextContains("draft answer")

        composeTestRule.runOnIdle { request = request.copy(connectionGeneration = 5) }

        composeTestRule.onNodeWithTag("clarify_choice_q1_Keep").assertIsNotSelected()
        val resetText =
            composeTestRule
                .onNodeWithTag("clarify_text_q1")
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text
        assertEquals("", resetText)
    }

    @Test
    fun skippedLockedAnswerIsReadOnly() {
        val request =
            ClarifyUi(
                text = "",
                options = emptyList(),
                clarifyId = "skipped",
                questions = listOf(ClarifyQuestionUi("q1", "Skipped?", choices = listOf("Yes", "No"))),
                lockedAnswers = mapOf("q1" to null),
            )
        composeTestRule.setContent { ClarifyBubble(request, onSubmit = { _, _ -> }, onDismiss = {}) }
        composeTestRule.onNodeWithTag("clarify_locked_q1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("clarify_text_q1").assertDoesNotExist()
        composeTestRule.onNodeWithTag("clarify_choice_q1_Yes").assertDoesNotExist()
    }

    @Test
    fun lockedAnswerIsReadOnly() {
        val request =
            ClarifyUi(
                text = "",
                options = emptyList(),
                clarifyId = "clarify-1",
                questions = listOf(ClarifyQuestionUi("q1", "Already answered?", choices = listOf("Yes", "No"))),
                lockedAnswers = mapOf("q1" to "Yes"),
            )
        composeTestRule.setContent {
            ClarifyBubble(request, onSubmit = { _, _ -> }, onDismiss = {})
        }

        composeTestRule.onNodeWithTag("clarify_locked_q1").assertIsDisplayed().assertTextContains("Yes")
        composeTestRule.onNodeWithTag("clarify_text_q1").assertDoesNotExist()
        composeTestRule.onNodeWithTag("clarify_choice_q1_Yes").assertDoesNotExist()
    }
}
