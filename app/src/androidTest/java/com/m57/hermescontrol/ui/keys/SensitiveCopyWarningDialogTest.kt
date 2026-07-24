package com.m57.hermescontrol.ui.keys

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensitiveCopyWarningDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun confirmationExplainsGlobalClipboardRisk_beforeCopying() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var copyCount = 0
        composeTestRule.setContent {
            MaterialTheme {
                SensitiveCopyWarningDialog(
                    secretName = "EXAMPLE_API_KEY",
                    onConfirm = { copyCount += 1 },
                    onDismiss = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText(
                context.getString(
                    R.string.keys_copy_warning_message,
                    "EXAMPLE_API_KEY",
                ),
            ).assertIsDisplayed()
        assertEquals(0, copyCount)

        composeTestRule
            .onNodeWithText(context.getString(R.string.keys_copy_warning_confirm))
            .performClick()

        assertEquals(1, copyCount)
    }
}
