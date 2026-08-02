package com.m57.hermescontrol.ui.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** Fraction of the available chat-list width a message bubble may occupy. */
const val BUBBLE_MAX_WIDTH_FRACTION = 0.80f

/**
 * Resolve a bubble's maximum width from the constraints its container hands
 * down, rather than from a screen-global `Configuration`.
 *
 * The `Configuration` a Compose tree reads is a snapshot of the window the
 * process was laid out for. On a foldable it can still describe the cover
 * display after the device is opened, which pins every bubble to the narrow
 * width for the rest of the process. Incoming constraints always describe the
 * real container, so they track folding, unfolding, multi-window and
 * letterboxing with no extra recomposition trigger.
 *
 * An unbounded width (a horizontally scrollable parent) has no fraction to
 * take, so it passes through unchanged.
 */
fun resolveBubbleMaxWidth(
    constraintMaxWidth: Int,
    constraintMinWidth: Int,
    fraction: Float = BUBBLE_MAX_WIDTH_FRACTION,
): Int {
    if (constraintMaxWidth == Constraints.Infinity) return constraintMaxWidth
    val scaled = (constraintMaxWidth * fraction).roundToInt()
    return scaled
        .coerceAtMost(constraintMaxWidth)
        .coerceAtLeast(constraintMinWidth)
}

/**
 * Cap the measured width at [fraction] of the width the parent offers.
 *
 * Drop-in replacement for `widthIn(max = <screen width> * fraction)` that is
 * relative to the real container instead of a device-global snapshot.
 */
fun Modifier.bubbleMaxWidth(fraction: Float = BUBBLE_MAX_WIDTH_FRACTION): Modifier =
    layout { measurable, constraints ->
        val maxWidth =
            resolveBubbleMaxWidth(
                constraintMaxWidth = constraints.maxWidth,
                constraintMinWidth = constraints.minWidth,
                fraction = fraction,
            )
        val placeable = measurable.measure(constraints.copy(maxWidth = maxWidth))
        layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }
