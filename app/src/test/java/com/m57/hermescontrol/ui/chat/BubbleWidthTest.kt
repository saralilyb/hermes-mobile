package com.m57.hermescontrol.ui.chat

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bubble width must follow the container the parent actually offers, not a
 * process-global screen snapshot. On a foldable opened after launch the
 * snapshot still describes the cover display, which pinned every bubble to the
 * narrow width for the life of the process.
 */
class BubbleWidthTest {
    @Test
    fun takesFractionOfTheOfferedWidth() {
        assertEquals(
            864,
            resolveBubbleMaxWidth(constraintMaxWidth = 1080, constraintMinWidth = 0),
        )
    }

    @Test
    fun tracksAWiderContainerAfterUnfolding() {
        val folded = resolveBubbleMaxWidth(constraintMaxWidth = 1080, constraintMinWidth = 0)
        val unfolded = resolveBubbleMaxWidth(constraintMaxWidth = 2208, constraintMinWidth = 0)

        // The regression: both widths came out identical because the fraction
        // was taken from the same stale Configuration on either side.
        assertEquals(864, folded)
        assertEquals(1766, unfolded)
    }

    @Test
    fun unboundedWidthPassesThrough() {
        assertEquals(
            Constraints.Infinity,
            resolveBubbleMaxWidth(
                constraintMaxWidth = Constraints.Infinity,
                constraintMinWidth = 0,
            ),
        )
    }

    @Test
    fun neverReportsLessThanTheRequiredMinimum() {
        // An exact-width parent (min == max) leaves no room for a fraction;
        // returning the scaled value would produce an unsatisfiable constraint.
        assertEquals(
            1080,
            resolveBubbleMaxWidth(constraintMaxWidth = 1080, constraintMinWidth = 1080),
        )
    }

    @Test
    fun neverExceedsTheOfferedWidth() {
        assertEquals(
            1080,
            resolveBubbleMaxWidth(
                constraintMaxWidth = 1080,
                constraintMinWidth = 0,
                fraction = 1.5f,
            ),
        )
    }

    @Test
    fun zeroWidthContainerIsHandled() {
        assertEquals(
            0,
            resolveBubbleMaxWidth(constraintMaxWidth = 0, constraintMinWidth = 0),
        )
    }
}
