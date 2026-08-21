package com.m57.hermescontrol.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.SubagentIndicator
import com.m57.hermescontrol.ui.chat.TodoItem

/**
 * Compact progress indicator displayed below the top app bar while todos are
 * incomplete or background subagents are running.
 */
@Composable
fun StickySubagentBar(
    indicators: List<SubagentIndicator> = emptyList(),
    todos: List<TodoItem> = emptyList(),
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = shouldShowStickyProgress(todos, indicators),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val display = computeStickyProgressDisplay(todos, indicators)
        val label =
            buildString {
                if (display.hasTodos) {
                    append(
                        stringResource(
                            R.string.task_progress_count,
                            display.currentTaskNumber,
                            display.totalTasks,
                        ),
                    )
                    display.currentTaskContent?.let {
                        append(" · ")
                        append(it)
                    }
                } else if (display.activeAgents > 0) {
                    append(
                        pluralStringResource(
                            R.plurals.task_progress_agent_running,
                            display.activeAgents,
                            display.activeAgents,
                        ),
                    )
                }
                if (display.hasTodos && display.activeAgents > 0) {
                    append(" · ")
                    append(
                        pluralStringResource(
                            R.plurals.task_progress_agents,
                            display.activeAgents,
                            display.activeAgents,
                        ),
                    )
                }
            }

        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onClick)
                    .testTag("sticky_subagent_bar"),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription =
                        stringResource(R.string.task_progress_open_details),
                    tint =
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = 0.7f,
                        ),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

internal data class StickyProgressDisplay(
    val hasTodos: Boolean,
    val currentTaskNumber: Int,
    val totalTasks: Int,
    val currentTaskContent: String?,
    val activeAgents: Int,
)

internal fun computeStickyProgressDisplay(
    todos: List<TodoItem>,
    indicators: List<SubagentIndicator>,
): StickyProgressDisplay {
    val selectedIndex =
        todos.indexOfFirst { it.isInProgress }.let { inProgressIndex ->
            if (inProgressIndex >= 0) {
                inProgressIndex
            } else {
                todos.indexOfFirst { !it.isCompleted && !it.isCancelled }
            }
        }
    val selected = todos.getOrNull(selectedIndex)
    val currentTaskNumber =
        if (selectedIndex >= 0) {
            selectedIndex + 1
        } else {
            todos.count { it.isCompleted }
        }

    return StickyProgressDisplay(
        hasTodos = selected != null,
        currentTaskNumber = currentTaskNumber,
        totalTasks = todos.size,
        currentTaskContent = selected?.content,
        activeAgents = indicators.count { it.isRunning },
    )
}

internal fun shouldShowStickyProgress(
    todos: List<TodoItem>,
    indicators: List<SubagentIndicator>,
): Boolean =
    todos.any { !it.isCompleted && !it.isCancelled } ||
        indicators.any { it.isRunning }
