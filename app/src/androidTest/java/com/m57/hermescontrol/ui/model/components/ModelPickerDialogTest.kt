package com.m57.hermescontrol.ui.model.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.data.model.ModelCapabilities
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ModelPickerDialogTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capabilityHintsAppearForPinnedAndOrdinaryRows() {
        val provider =
            ModelProvider(
                slug = "openai-codex",
                name = "OpenAI Codex",
                models = listOf("reasoning-model", "fast-model"),
                capabilities =
                    mapOf(
                        "reasoning-model" to
                            ModelCapabilities(
                                reasoning = true,
                                can_disable_reasoning = false,
                            ),
                        "fast-model" to
                            ModelCapabilities(reasoning = false),
                    ),
            )

        composeTestRule.setContent {
            ModelPickerDialog(
                providers = listOf(provider),
                title = "Switch model",
                pinnedModels =
                    listOf(
                        PinnedModel(
                            providerSlug = "openai-codex",
                            modelName = "reasoning-model",
                        ),
                    ),
                onSelect = { _, _ -> },
                onDismiss = {},
            )
        }

        composeTestRule
            .onAllNodesWithText("Reasoning always on")
            .assertCountEquals(2)
        composeTestRule.onNodeWithText("No reasoning").assertExists()
    }

    @Test
    fun imeInsetDoesNotCoverDialogActions() {
        val imeHeight = 320.dp
        composeTestRule.setContent {
            ModelPickerDialog(
                providers =
                    listOf(
                        ModelProvider(
                            slug = "fireworks",
                            name = "Fireworks AI",
                            models =
                                (1..20).map {
                                    "accounts/fireworks/models/model-$it"
                                },
                        ),
                    ),
                title = "Switch model",
                onSelect = { _, _ -> },
                onDismiss = {},
                imeInsets = WindowInsets(bottom = imeHeight),
            )
        }

        val imeBottom =
            imeHeight.value * composeTestRule.activity.resources.displayMetrics.density
        val keyboardTop = composeTestRule.activity.window.decorView.height - imeBottom
        val closeButtonBottom =
            composeTestRule
                .onNodeWithTag("model_picker_close")
                .fetchSemanticsNode()
                .boundsInRoot
                .bottom

        assertTrue(
            "Close button bottom ($closeButtonBottom) must stay above keyboard top ($keyboardTop)",
            closeButtonBottom <= keyboardTop,
        )
    }
}
