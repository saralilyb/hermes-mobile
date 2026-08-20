package com.m57.hermescontrol.ui.chat.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.SystemTimelineEvent

@StringRes
internal fun timelineEventLabel(kind: String): Int =
    when (kind) {
        "model_switch" -> R.string.timeline_marker_model_switch
        "personality_switch" -> R.string.timeline_marker_personality_switch
        "auto_continue" -> R.string.timeline_marker_auto_continue
        "async_delegation_complete" -> R.string.timeline_marker_delegation_complete
        "skill_invocation" -> R.string.timeline_marker_skill_invocation
        "internal_notification" -> R.string.timeline_marker_internal_notification
        "max_iterations_reached" -> R.string.timeline_marker_max_iterations
        else -> R.string.timeline_marker_system_notice
    }

@Composable
internal fun SystemTimelineMarker(
    event: SystemTimelineEvent,
    modifier: Modifier = Modifier,
) {
    val text =
        if (event.kind == "model_switch" && event.model != null) {
            stringResource(R.string.timeline_marker_model_switch_to, event.model)
        } else {
            stringResource(timelineEventLabel(event.kind))
        }
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("system_timeline_event"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
