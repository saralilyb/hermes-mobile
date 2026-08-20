package com.m57.hermescontrol.ui.settings.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.util.LocaleContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceSectionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun languageDropdown_listsEveryOption_andReportsChangedSelection() {
        var selected: String? = null

        composeTestRule.setContent {
            MaterialTheme {
                LanguageSection(
                    appLanguage = LocaleContextWrapper.SYSTEM_LANGUAGE,
                    onAppLanguageChange = { selected = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("language_picker").performClick()
        composeTestRule
            .onNodeWithTag("language_option_system")
            .assertIsSelected()
        SUPPORTED_LANGUAGE_CODES.forEach { code ->
            composeTestRule.onNodeWithTag("language_option_$code").assertIsDisplayed()
        }
        composeTestRule.onNodeWithTag("language_option_ja").performClick()
        composeTestRule.runOnIdle { assertEquals("ja", selected) }
    }

    @Test
    fun unknownLanguageSummary_fallsBackToSystem() {
        val systemLabel =
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.language_system)

        composeTestRule.setContent {
            MaterialTheme { Text(languageLabel("retired-code")) }
        }

        composeTestRule.onNodeWithText(systemLabel).assertIsDisplayed()
    }

    @Test
    fun selectingCurrentLanguage_doesNotPersistOrRecreate() {
        var changeCount = 0
        composeTestRule.setContent {
            MaterialTheme {
                LanguageSection(
                    appLanguage = LocaleContextWrapper.SYSTEM_LANGUAGE,
                    onAppLanguageChange = { changeCount++ },
                )
            }
        }

        composeTestRule.onNodeWithTag("language_picker").performClick()
        composeTestRule.onNodeWithTag("language_option_system").performClick()
        composeTestRule.runOnIdle { assertEquals(0, changeCount) }
    }
}
