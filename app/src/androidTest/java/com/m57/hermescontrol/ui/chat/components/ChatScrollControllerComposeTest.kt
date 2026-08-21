package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class ChatScrollControllerComposeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun delayedTailLayoutRemainsFollowed() {
        var appendTail: (() -> Unit)? = null
        var lastVisibleIndex: (() -> Int)? = null
        var isAtBottom: (() -> Boolean)? = null

        composeTestRule.setContent {
            var itemCount by remember { mutableIntStateOf(20) }
            val listState = rememberLazyListState()
            val controller = rememberChatScrollController(listState, rememberCoroutineScope())
            val bottomTolerance = with(LocalDensity.current) { 8.dp.roundToPx() }
            appendTail = { itemCount += 1 }
            lastVisibleIndex = { listState.layoutInfo.visibleItemsInfo.last().index }
            isAtBottom = { listState.isAtBottom(tolerance = bottomTolerance) }

            LaunchedEffect(itemCount) {
                controller.onTailChanged(
                    tailKey = itemCount,
                    messageCount = itemCount,
                    listItemCount = itemCount,
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.height(120.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items((0 until itemCount).toList()) {
                    Box(Modifier.heightIn(min = 40.dp))
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { appendTail!!() }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            assertEquals(20, lastVisibleIndex!!())
            assertTrue(isAtBottom!!())
        }
    }
}
