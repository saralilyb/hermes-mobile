package com.m57.hermescontrol.ui.settings.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
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
    fun languageSegments_areEqualHeight_andDoNotRepeatPageTitle() {
        val pageTitle =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext
                .getString(R.string.settings_sec_appearance)

        composeTestRule.setContent {
            MaterialTheme {
                AppearanceSection(
                    themePreference = ThemePreference.SYSTEM,
                    onThemeChange = {},
                    useDynamicColors = true,
                    onUseDynamicColorsChange = {},
                    themePreset = ThemePreset.DEFAULT,
                    onThemePresetChange = {},
                    appLanguage = LocaleContextWrapper.SYSTEM_LANGUAGE,
                    onAppLanguageChange = {},
                )
            }
        }

        val heights =
            listOf(
                "language_option_${LocaleContextWrapper.SYSTEM_LANGUAGE}",
                "language_option_en",
                "language_option_ko",
            ).map { tag ->
                composeTestRule
                    .onNodeWithTag(tag)
                    .fetchSemanticsNode()
                    .boundsInRoot.height
            }

        heights.drop(1).forEach { height ->
            assertEquals(heights.first(), height, 0.5f)
        }
        composeTestRule
            .onAllNodesWithText(pageTitle)
            .assertCountEquals(0)
    }
}
