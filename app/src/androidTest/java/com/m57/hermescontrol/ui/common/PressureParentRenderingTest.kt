package com.m57.hermescontrol.ui.common

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.m57.hermescontrol.data.model.DiskPressureStatus
import com.m57.hermescontrol.data.model.MemoryPressureStatus
import com.m57.hermescontrol.data.model.StatusResponse
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.connect.ConnectPressureAdvisory
import com.m57.hermescontrol.ui.gateway.GatewayPressureAdvisory
import org.junit.Rule
import org.junit.Test

class PressureParentRenderingTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun connectParentRendersAccessibleCriticalAdvisory() {
        composeRule.setContent {
            HermesControlTheme {
                ConnectPressureAdvisory(StatusResponse(memory = MemoryPressureStatus("critical")))
            }
        }
        composeRule.onNodeWithText("Agent is almost out of memory and may restart")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Critical host resource pressure"),
            )
    }

    @Test
    fun gatewayParentRendersAccessibleWarningAdvisory() {
        composeRule.setContent {
            HermesControlTheme {
                GatewayPressureAdvisory(StatusResponse(disk = DiskPressureStatus("elevated")))
            }
        }
        composeRule.onNodeWithText("Agent's disk is filling up — consider clearing old sessions or expanding storage")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Host resource pressure warning"),
            )
    }
}
