package com.m57.hermescontrol.ui.plugins

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PluginsReloadWiringTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun toolbarRefreshInvokesScreenReloadCallback() {
        var reloads = 0
        composeRule.setContent {
            PluginsScaffold(isRefreshing = false, onReload = { reloads++ }) { _ -> Text("content") }
        }

        composeRule.onNodeWithTag("refresh_button").performClick()

        assertEquals(1, reloads)
    }
}
