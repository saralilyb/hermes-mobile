package com.m57.hermescontrol.ui.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionLiveStatus
import com.m57.hermescontrol.theme.HermesControlTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Real parent: catches missing ViewModel callback wiring, not just child rendering. */
class SessionsProjectsWiringTest {
    @get:Rule val compose = createComposeRule()

    @After fun cleanup() = unmockkAll()

    @Test
    fun projectFilterCollapseAndPinsReachViewModelAtNarrowRtlLargeFont() {
        mockkObject(AuthManager)
        every { AuthManager.getSelectedProfileId() } returns "test"
        val pinned = SessionInfo("pin", title = "Pinned", cwd = "/b")
        val state =
            MutableStateFlow(
                SessionsUiState(
                    sessions = listOf(pinned, SessionInfo("recent", title = "Recent", cwd = "/a")),
                    pinnedSessionIds = listOf("pin"),
                    projects = listOf(ProjectInfo("a", name = "App", primaryPath = "/a")),
                    liveStatuses = mapOf("pin" to SessionLiveStatus.WORKING),
                ),
            )
        val vm = mockk<SessionsViewModel>(relaxed = true)
        every { vm.uiState } returns state
        every { vm.selectProjectFilter("project:a") } answers {
            state.value = state.value.copy(projectFilter = "project:a")
        }
        every { vm.toggleProjectGroup("project:a") } answers {
            state.value = state.value.copy(collapsedProjectGroups = setOf("project:a"))
        }
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 1.5f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                HermesControlTheme {
                    Box(Modifier.fillMaxWidth(0.85f)) { SessionsScreen(viewModel = vm) }
                }
            }
        }
        compose.onNodeWithTag("history_project_chip_project:a").performScrollTo().assertHasClickAction().performClick()
        val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        list.performScrollToNode(hasTestTag("history_project_group_project:a"))
        compose.onNodeWithTag("history_project_group_project:a").performClick()
        compose.onNodeWithTag("session_card_recent").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("session_card_recent").assertDoesNotExist()
        list.performScrollToNode(hasTestTag("session_pin_pin"))
        compose.onNodeWithTag("session_pin_pin").assertIsDisplayed().assertHasClickAction().performClick()
        compose.runOnIdle {
            verify { vm.selectProjectFilter("project:a") }
            verify { vm.toggleProjectGroup("project:a") }
            verify { vm.toggleSessionPin(pinned) }
        }
    }
}
