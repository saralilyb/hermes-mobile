package com.m57.hermescontrol.ui.plugins

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PluginsReloadWiringTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun toolbarRefreshInvokesScreenReloadCallback() {
        var reloads = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            PluginsScaffold(isRefreshing = false, onReload = { reloads++ }) { _ -> Text("content") }
        }

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.content_desc_refresh))
            .performClick()

        assertEquals(1, reloads)
    }
}
