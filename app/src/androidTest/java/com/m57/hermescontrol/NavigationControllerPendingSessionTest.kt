package com.m57.hermescontrol

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NavigationControllerPendingSessionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        NavigationController.pendingSessionTarget = null
    }

    @After
    fun tearDown() {
        NavigationController.pendingSessionTarget = null
    }

    @Test
    fun pendingSessionChange_recomposesAnExistingChatDestination() {
        composeTestRule.setContent {
            Text(
                NavigationController.pendingSessionTarget?.sessionId
                    ?: "no pending session",
            )
        }
        composeTestRule.onNodeWithText("no pending session").assertIsDisplayed()

        composeTestRule.runOnIdle {
            NavigationController.queuePendingSession(
                sessionId = "session-next",
                profileId = "profile-a",
            )
        }

        composeTestRule.onNodeWithText("session-next").assertIsDisplayed()
    }
}
