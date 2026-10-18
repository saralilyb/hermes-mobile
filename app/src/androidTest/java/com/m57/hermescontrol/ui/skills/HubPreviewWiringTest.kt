package com.m57.hermescontrol.ui.skills

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.HubSkill
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class HubPreviewWiringTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun actualHubDialogDismissalClearsPreviewAndCanReopenSameCard() {
        val viewModel = hubViewModel()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { SkillsScreen(viewModel = viewModel) }
        composeRule.waitForIdle()
        clearMocks(viewModel, answers = false, recordedCalls = true)

        composeRule.onNodeWithText("Regression skill").assertHasClickAction().performClick()
        verify(exactly = 1) { viewModel.previewHubSkill("fixture/skill") }
        composeRule.onNodeWithContentDescription(context.getString(R.string.content_desc_back))
            .assertHasClickAction().performClick()
        composeRule.waitForIdle()
        verify(exactly = 1) { viewModel.clearHubPreview() }
        verify(exactly = 0) { viewModel.clearPreview() }
        composeRule.onNodeWithContentDescription(context.getString(R.string.content_desc_back)).assertDoesNotExist()

        composeRule.onNodeWithText("Regression skill").performClick()
        verify(exactly = 2) { viewModel.previewHubSkill("fixture/skill") }
    }

    @Test
    fun disposingActualSkillsScreenClearsHubPreview() {
        val viewModel = hubViewModel()
        val visible = mutableStateOf(true)
        composeRule.setContent { if (visible.value) SkillsScreen(viewModel = viewModel) }
        composeRule.onNodeWithText("Regression skill").assertHasClickAction().performClick()
        composeRule.waitForIdle()
        clearMocks(viewModel, answers = false, recordedCalls = true)
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        verify(exactly = 1) { viewModel.clearHubPreview() }
        verify(exactly = 0) { viewModel.clearPreview() }
    }

    @Test
    fun leavingHubTabClearsPreviewThroughActualParent() {
        val viewModel = hubViewModel()
        val state = viewModel.uiState as MutableStateFlow<SkillsUiState>
        every { viewModel.setViewMode(any()) } answers {
            state.value = state.value.copy(viewMode = firstArg())
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { SkillsScreen(viewModel = viewModel) }
        composeRule.waitForIdle()
        clearMocks(viewModel, answers = false, recordedCalls = true)
        composeRule.onNodeWithText(context.getString(R.string.skills_mode_installed))
            .assertHasClickAction().performClick()
        composeRule.waitForIdle()
        verify(exactly = 1) { viewModel.setViewMode(SkillsViewMode.INSTALLED) }
        verify(exactly = 1) { viewModel.clearHubPreview() }
    }

    private fun hubViewModel(): SkillsViewModel {
        val viewModel = mockk<SkillsViewModel>(relaxed = true)
        every { viewModel.uiState } returns
            MutableStateFlow(
                SkillsUiState(
                    viewMode = SkillsViewMode.HUB,
                    hubQuery = "fixture",
                    hubResults = listOf(HubSkill(name = "Regression skill", identifier = "fixture/skill")),
                ),
            )
        return viewModel
    }
}
