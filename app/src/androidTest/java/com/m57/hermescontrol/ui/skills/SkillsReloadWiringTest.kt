package com.m57.hermescontrol.ui.skills

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SkillsReloadWiringTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun toolbarReloadAndHubUpdateInvokeDistinctCallbacks() {
        var reloads = 0
        var hubUpdates = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            SkillsScaffold(
                isRefreshing = false,
                onReload = { reloads++ },
                onUpdateFromHub = { hubUpdates++ },
            ) { _ -> Text("content") }
        }

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.content_desc_refresh))
            .performClick()
        assertEquals(1, reloads)
        assertEquals(0, hubUpdates)

        composeRule.onNodeWithContentDescription(
            context.getString(R.string.content_desc_update_skills_hub),
        ).performClick()
        assertEquals(1, reloads)
        assertEquals(1, hubUpdates)
    }

    @Test
    fun hubModeOmitsInstalledSkillsUpdateAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            SkillsScaffold(
                isRefreshing = false,
                onReload = {},
                onUpdateFromHub = null,
            ) { _ -> Text("content") }
        }

        composeRule
            .onNodeWithContentDescription(
                context.getString(R.string.content_desc_update_skills_hub),
            ).assertDoesNotExist()
    }

    @Test
    fun hubScreenRefreshRerunsVisibleQueryInsteadOfReloadingInstalledSkills() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val viewModel = mockk<SkillsViewModel>(relaxed = true)
        val state =
            SkillsUiState(
                viewMode = SkillsViewMode.HUB,
                hubQuery = "agents",
            )
        every { viewModel.uiState } returns MutableStateFlow(state).asStateFlow()
        composeRule.setContent {
            SkillsScreen(viewModel = viewModel)
        }
        composeRule.waitForIdle()
        clearMocks(viewModel, answers = false, recordedCalls = true)

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.content_desc_refresh))
            .performClick()

        verify(exactly = 1) { viewModel.searchHub("agents") }
        verify(exactly = 0) { viewModel.loadSkills() }
    }
}
