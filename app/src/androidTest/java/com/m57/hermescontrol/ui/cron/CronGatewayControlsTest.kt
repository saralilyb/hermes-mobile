package com.m57.hermescontrol.ui.cron

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.data.model.CronJob
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CronGatewayControlsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(state: String): CronJobsViewModel {
        val vm = mockk<CronJobsViewModel>(relaxed = true)
        every { vm.uiState } returns
            MutableStateFlow(
                CronJobsUiState(jobs = listOf(CronJob(id = "job", name = "Job", state = state))),
            ).asStateFlow()
        compose.setContent { CronJobsScreen(viewModel = vm) }
        return vm
    }

    @Test
    fun scheduledPausesThroughParent() {
        val vm = render("scheduled")
        compose.onNodeWithContentDescription("Resume").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause").assertHasClickAction().performClick()
        verify(exactly = 1) { vm.pauseCronJob("job") }
    }

    @Test
    fun legacyActivePausesThroughParent() {
        val vm = render("active")
        compose.onNodeWithContentDescription("Pause").performClick()
        verify(exactly = 1) { vm.pauseCronJob("job") }
    }

    @Test
    fun pausedResumesThroughParent() {
        val vm = render("paused")
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        compose.onNodeWithContentDescription("Resume").assertHasClickAction().performClick()
        verify(exactly = 1) { vm.resumeCronJob("job") }
    }

    @Test
    fun errorResumesThroughParent() {
        val vm = render("error")
        compose.onNodeWithContentDescription("Resume").performClick()
        verify(exactly = 1) { vm.resumeCronJob("job") }
    }

    @Test
    fun completedHasNoStateControl() {
        render("completed")
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        compose.onNodeWithContentDescription("Resume").assertDoesNotExist()
    }

    @Test
    fun unknownHasNoStateControl() {
        render("future-state")
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        compose.onNodeWithContentDescription("Resume").assertDoesNotExist()
    }
}
