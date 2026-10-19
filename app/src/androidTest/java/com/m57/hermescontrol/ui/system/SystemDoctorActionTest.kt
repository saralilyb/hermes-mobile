package com.m57.hermescontrol.ui.system

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.data.model.StatusResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemDoctorActionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun doctorButtonCallsExplicitOperationAndShowsGatewayPid() {
        val vm = mockk<SystemViewModel>(relaxed = true)
        every { vm.uiState } returns
            MutableStateFlow(
                SystemUiState(status = StatusResponse(version = "test", gateway_running = true, gateway_pid = 9876)),
            ).asStateFlow()
        compose.setContent { SystemScreen(viewModel = vm) }
        val list = compose.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("test · pid 9876"))
        compose.onNodeWithText("test · pid 9876").assertExists()
        list.performScrollToNode(hasText("Run doctor"))
        compose.onNodeWithText("Run doctor").assertHasClickAction().performClick()
        verify(exactly = 1) { vm.runDoctor() }
    }
}
