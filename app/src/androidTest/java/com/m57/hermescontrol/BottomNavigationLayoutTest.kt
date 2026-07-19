package com.m57.hermescontrol

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.BottomNavDisplayMode
import org.junit.Rule
import org.junit.Test

class BottomNavigationLayoutTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun compactDisplayModes_keepConfiguredHeights() {
        composeTestRule.setContent {
            MaterialTheme {
                Column {
                    NavigationBar(
                        modifier =
                            Modifier
                                .testTag("icon-only-bar")
                                .bottomNavigationHeight(
                                    BottomNavDisplayMode.ICON_ONLY,
                                ),
                    ) {}
                    NavigationBar(
                        modifier =
                            Modifier
                                .testTag("text-only-bar")
                                .bottomNavigationHeight(
                                    BottomNavDisplayMode.TEXT_ONLY,
                                ),
                    ) {}
                }
            }
        }

        composeTestRule
            .onNodeWithTag("icon-only-bar")
            .assertHeightIsEqualTo(56.dp)
        composeTestRule
            .onNodeWithTag("text-only-bar")
            .assertHeightIsEqualTo(44.dp)
    }
}
