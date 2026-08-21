package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.SubagentIndicator
import com.m57.hermescontrol.ui.chat.TodoItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubagentInspectionSheetAccessibilityTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun todoStatusIconsExposeLocalizedDescriptions() {
        composeTestRule.setContent {
            HermesControlTheme(useDynamicColors = false) {
                Column {
                    TodoInspectionCard(
                        TodoItem("pending", "Pending", "pending"),
                    )
                    TodoInspectionCard(
                        TodoItem("running", "Running", "in_progress"),
                    )
                    TodoInspectionCard(
                        TodoItem("completed", "Completed", "completed"),
                    )
                    TodoInspectionCard(
                        TodoItem("cancelled", "Cancelled", "cancelled"),
                    )
                }
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeTestRule
            .onNodeWithContentDescription(
                context.getString(R.string.subagent_status_pending),
            ).assertExists()
        composeTestRule
            .onNodeWithContentDescription(
                context.getString(R.string.subagent_status_running),
            ).assertExists()
        composeTestRule
            .onNodeWithContentDescription(
                context.getString(R.string.subagent_status_completed),
            ).assertExists()
        composeTestRule
            .onNodeWithContentDescription(
                context.getString(R.string.subagent_status_cancelled),
            ).assertExists()
    }

    @Test
    fun subagentStatusIconsExposeLocalizedDescriptions() {
        composeTestRule.setContent {
            HermesControlTheme(useDynamicColors = false) {
                Column {
                    InspectionItemCard(
                        SubagentIndicator(
                            type = "subagent.start",
                            status = "running",
                        ),
                    )
                    InspectionItemCard(
                        SubagentIndicator(
                            type = "subagent.complete",
                            status = "completed",
                        ),
                    )
                    InspectionItemCard(
                        SubagentIndicator(
                            type = "subagent.failed",
                            status = "failed",
                        ),
                    )
                }
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeTestRule
            .onNodeWithContentDescription(
                context.getString(R.string.subagent_status_running),
            ).assertExists()
        composeTestRule
            .onNodeWithContentDescription(
                context.getString(R.string.subagent_status_completed),
            ).assertExists()
        composeTestRule
            .onNodeWithContentDescription(
                context.getString(R.string.subagent_status_failed),
            ).assertExists()
    }
}
