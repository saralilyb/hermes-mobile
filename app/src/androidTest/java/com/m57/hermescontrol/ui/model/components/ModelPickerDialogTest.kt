package com.m57.hermescontrol.ui.model.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.data.model.ModelProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ModelPickerDialogTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

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
        val actionBottom =
            composeTestRule
                .onNodeWithTag("model_picker_cancel")
                .fetchSemanticsNode()
                .boundsInRoot
                .bottom

        assertTrue(
            "Cancel action bottom ($actionBottom) must stay above keyboard top ($keyboardTop)",
            actionBottom <= keyboardTop,
        )
    }
}
