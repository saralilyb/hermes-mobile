package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
 * process-global screen snapshot. The discriminator is the RATIO: when the
 * container is narrower than the screen fraction, a screen-derived cap never
 * binds, so the bubble fills its container edge to edge instead of stopping at
 * [BUBBLE_MAX_WIDTH_FRACTION] — about 1.25x too wide.
 *
 * The container is expressed as a FRACTION OF THE WINDOW, and its realised
 * width is measured rather than assumed. An absolute `Modifier.width(600.dp)`
 * is silently clamped by a narrower window, which makes the expectation wrong
 * on every device smaller than the one the test was written on — a 443dp
 * folded window caps a 600dp request at 443dp, and CI's 320dp AVD at 320dp.
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
        "This is a long line of short words, set up so the text is sure to " +
            "run past the cap under test and be bound by it, not by how wide " +
            "the words want to be on their own."

    /** The bubble's own horizontal padding, applied on both sides. */
    private val bubblePadding = 16.dp

    private fun measureIn(fractionOfWindow: Float): Measurement {
        composeTestRule.setContent {
            MaterialTheme {
                // The window stand-in must be an explicit fillMaxSize parent.
                // `onRoot()` reports the CONTENT bounds, so if the fractional
                // container were the top-level composable the root would size
                // to it and window == container by construction — the guard
                // below would then fire on every device.
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .testTag(WINDOW_TAG),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth(fractionOfWindow)
                                .testTag(CONTAINER_TAG),
                    ) {
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
        }
        return Measurement(
            window = composeTestRule.onNodeWithTag(WINDOW_TAG).getUnclippedBoundsInRoot().width,
            container = composeTestRule.onNodeWithTag(CONTAINER_TAG).getUnclippedBoundsInRoot().width,
            bubble = composeTestRule.onNodeWithTag(ASSISTANT_BUBBLE_TAG).getUnclippedBoundsInRoot().width,
        )
    }

    private data class Measurement(
        val window: Dp,
        val container: Dp,
        val bubble: Dp,
    )

    private fun assertCappedByContainer(measured: Measurement) {
        // Guard against a vacuous pass: if the container ever gets the whole
        // window, a screen-derived cap and a container-derived cap agree and
        // the test discriminates nothing.
        assertTrue(
            "container ${measured.container} must be narrower than the window " +
                "${measured.window} or this test cannot tell the two caps apart",
            measured.container < measured.window - 1.dp,
        )

        val available = measured.container - bubblePadding
        val cap = available * BUBBLE_MAX_WIDTH_FRACTION

        // The real assertion. Layout cannot exceed the cap, so this is exact
        // (bar sub-pixel rounding). Without the fix the bubble fills the
        // container edge to edge — `available`, which is 1.25x the cap.
        assertTrue(
            "bubble measured ${measured.bubble} in a ${measured.container} container; " +
                "expected at most $cap ($BUBBLE_MAX_WIDTH_FRACTION of the $available offered, " +
                "not of the ${measured.window} screen)",
            measured.bubble <= cap + 1.dp,
        )

        // Anti-vacuity only, deliberately loose: how close a wrapped paragraph
        // lands to its cap depends on word widths, which vary with the
        // device's width and font scale.
        assertTrue(
            "bubble measured ${measured.bubble}, far below the $cap cap — the message " +
                "did not fill the bubble, so the cap was never exercised",
            measured.bubble > cap * 0.6f,
        )
    }

    @Test
    fun bubbleIsCappedByAHalfWidthContainer() {
        assertCappedByContainer(measureIn(0.5f))
    }

    @Test
    fun bubbleIsCappedByAnEightyPercentContainer() {
        assertCappedByContainer(measureIn(0.8f))
    }

    private companion object {
        const val WINDOW_TAG = "bubble_width_test_window"
        const val CONTAINER_TAG = "bubble_width_test_container"
        const val ASSISTANT_BUBBLE_TAG = "chat_bubble_assistant"
    }
}
