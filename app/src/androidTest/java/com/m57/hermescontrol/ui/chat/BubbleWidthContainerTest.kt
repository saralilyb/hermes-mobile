package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level regression for the foldable bubble-width defect.
 *
 * A bubble must take its cap from the width its container offers, never from a
 * process-global screen snapshot. The discriminator is the RATIO, not growth:
 * when the container is narrower than the screen fraction, the old
 * screen-derived cap never binds, so the bubble fills its container edge to
 * edge instead of stopping at [BUBBLE_MAX_WIDTH_FRACTION].
 *
 * The pure arithmetic lives in `BubbleWidthTest` (JVM). This test exists to
 * prove the modifier is wired into the real bubbles on a real device.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class BubbleWidthContainerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val longMessage =
        "This message is deliberately long enough that the bubble is bound by " +
            "its width constraint rather than by its own intrinsic text width, " +
            "so the measured bounds report the cap under test."

    private fun bubbleWidthIn(container: Dp): Dp {
        composeTestRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(container)) {
                    ChatBubble(
                        message =
                            ChatMessage(
                                role = MessageRole.ASSISTANT,
                                content = longMessage,
                            ),
                        isDarkTheme = true,
                    )
                }
            }
        }
        return composeTestRule
            .onNodeWithTag("chat_bubble_assistant")
            .getUnclippedBoundsInRoot()
            .width
    }

    /** The bubble's own horizontal padding, applied on both sides. */
    private fun availableIn(container: Dp): Dp = container - 16.dp

    private fun assertCappedByContainer(
        container: Dp,
        actual: Dp,
    ) {
        val expected = availableIn(container) * BUBBLE_MAX_WIDTH_FRACTION
        val tolerance = expected * 0.06f
        assertTrue(
            "bubble in a $container container measured $actual, expected ~$expected " +
                "(${BUBBLE_MAX_WIDTH_FRACTION} of the offered width, not of the screen)",
            actual >= expected - tolerance && actual <= expected + tolerance,
        )
    }

    @Test
    fun bubbleIsCappedByNarrowContainerNotByScreen() {
        val container = 300.dp
        assertCappedByContainer(container, bubbleWidthIn(container))
    }

    @Test
    fun bubbleIsCappedByWideContainerNotByScreen() {
        val container = 600.dp
        assertCappedByContainer(container, bubbleWidthIn(container))
    }
}
