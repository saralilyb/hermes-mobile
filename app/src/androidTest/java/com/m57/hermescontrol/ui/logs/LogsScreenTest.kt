package com.m57.hermescontrol.ui.logs

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class LogsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun filterSelectionsAccumulateThroughTheScreenViewModelWiring() {
        val state =
            MutableStateFlow(
                LogsUiState(
                    logs = listOf("A log line"),
                ),
            )
        val viewModel = mockk<LogsViewModel>(relaxed = true)
        every { viewModel.uiState } returns state.asStateFlow()
        every { viewModel.setFilters(any()) } answers {
            state.value = state.value.copy(filters = firstArg())
        }

        composeTestRule.setContent {
            LogsScreen(
                onOpenDrawer = {},
                viewModel = viewModel,
            )
        }

        composeTestRule.onNodeWithText("Errors").performClick()
        composeTestRule.onNodeWithText("Debug").performClick()

        verify { viewModel.setFilters(LogsFilters(file = "errors")) }
        verify {
            viewModel.setFilters(
                LogsFilters(
                    file = "errors",
                    level = "DEBUG",
                ),
            )
        }
    }

    @Test
    fun filtersRemainAvailableWhenTheSelectedSourceIsEmpty() {
        val state = MutableStateFlow(LogsUiState())
        val viewModel = mockk<LogsViewModel>(relaxed = true)
        every { viewModel.uiState } returns state.asStateFlow()

        composeTestRule.setContent {
            LogsScreen(
                onOpenDrawer = {},
                viewModel = viewModel,
            )
        }

        composeTestRule.onNodeWithText("Errors").performClick()

        verify { viewModel.setFilters(LogsFilters(file = "errors")) }
    }

    @Test
    fun filtersRemainAvailableAfterARequestFailure() {
        val state =
            MutableStateFlow(
                LogsUiState(errorMessage = "Failed to load logs"),
            )
        val viewModel = mockk<LogsViewModel>(relaxed = true)
        every { viewModel.uiState } returns state.asStateFlow()

        composeTestRule.setContent {
            LogsScreen(
                onOpenDrawer = {},
                viewModel = viewModel,
            )
        }

        composeTestRule.onNodeWithText("Errors").performClick()

        verify { viewModel.setFilters(LogsFilters(file = "errors")) }
    }
}
