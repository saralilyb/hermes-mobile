package com.m57.hermescontrol.ui.chat.components

import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.modelFromTimelineContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemTimelineMarkerTest {
    @Test
    fun everySupportedKindHasExpectedFriendlyLabel() {
        assertEquals(R.string.timeline_marker_model_switch, timelineEventLabel("model_switch"))
        assertEquals(
            R.string.timeline_marker_personality_switch,
            timelineEventLabel("personality_switch"),
        )
        assertEquals(R.string.timeline_marker_auto_continue, timelineEventLabel("auto_continue"))
        assertEquals(
            R.string.timeline_marker_delegation_complete,
            timelineEventLabel("async_delegation_complete"),
        )
        assertEquals(R.string.timeline_marker_skill_invocation, timelineEventLabel("skill_invocation"))
        assertEquals(
            R.string.timeline_marker_internal_notification,
            timelineEventLabel("internal_notification"),
        )
        assertEquals(
            R.string.timeline_marker_max_iterations,
            timelineEventLabel("max_iterations_reached"),
        )
        assertEquals(R.string.timeline_marker_system_notice, timelineEventLabel("future_kind"))
    }

    @Test
    fun modelNameIsParsedWithoutSentencePunctuation() {
        assertEquals(
            "gpt-5.6-sol",
            modelFromTimelineContent(
                "[System: The active model for this chat has changed to gpt-5.6-sol via provider openai.]",
            ),
        )
        assertNull(modelFromTimelineContent("Model changed without a target"))
    }
}
