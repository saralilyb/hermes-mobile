package com.m57.hermescontrol

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NavigationBackStackRestorationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun languageSettingsBackStackSurvivesActivityStateRestoration() {
        val restorationTester = StateRestorationTester(composeTestRule)
        lateinit var backStack: NavBackStack<NavKey>

        restorationTester.setContent {
            backStack = rememberNavBackStack(ChatScreen)
        }
        composeTestRule.runOnIdle {
            backStack.add(SettingsScreen)
            backStack.add(SettingsLanguage)
        }

        restorationTester.emulateSavedInstanceStateRestore()

        composeTestRule.runOnIdle {
            assertEquals(
                listOf(ChatScreen, SettingsScreen, SettingsLanguage),
                backStack.toList(),
            )
        }
    }
}
