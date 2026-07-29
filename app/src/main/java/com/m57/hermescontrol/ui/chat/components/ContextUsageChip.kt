// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import java.util.Locale
import kotlin.math.min

/** Bar turns amber at this fill — compaction pressure is building. */
private const val CONTEXT_WARN_PERCENT = 70

/** Bar turns red at this fill — a compaction is imminent. */
private const val CONTEXT_DANGER_PERCENT = 90

/**
 * Compact "used / full context" meter, rendered above the composer.
 *
 * [usedTokens] is the gateway's *current-window* occupancy, not a cumulative
 * session total, and it is genuinely nullable: the backend omits it whenever it
 * has no real occupancy figure (an external context engine that does not track
 * per-window fill, or the transitional turn right after a compaction). This
 * renders that case as `— / <full>` with an unfilled bar, matching the
 * backend's own refusal to fabricate a percentage. Substituting the session's
 * lifetime prompt total there produces readings like 1.9M against a 120k
 * window, pinned at 100% — see [com.m57.hermescontrol.ui.chat.ContextUsage].
 *
 * The whole chip hides until a denominator is known.
 */
@Composable
fun ContextUsageChip(
    usedTokens: Long?,
    fullTokens: Long?,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (fullTokens == null || fullTokens <= 0L) return

    val fraction =
        if (usedTokens == null) {
            0f
        } else {
            min(1f, usedTokens.toFloat() / fullTokens.toFloat())
        }
    val pct = (fraction * 100).toInt()
    val statusColors = LocalHermesStatusColors.current
    val barColor =
        when {
            usedTokens == null -> statusColors.neutral
            pct >= CONTEXT_DANGER_PERCENT -> statusColors.error
            pct >= CONTEXT_WARN_PERCENT -> statusColors.warning
            else -> MaterialTheme.colorScheme.primary
        }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier
                            .minimumInteractiveComponentSize()
                            .clickable(
                                role = Role.Button,
                                onClick = onClick,
                            )
                    } else {
                        Modifier
                    },
                ).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    stringResource(
                        R.string.chat_context_meter_label,
                        usedTokens?.let { formatTokens(it) }
                            ?: stringResource(R.string.chat_context_meter_unknown),
                        formatTokens(fullTokens),
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    if (usedTokens == null) {
                        stringResource(R.string.chat_context_meter_unknown)
                    } else {
                        stringResource(R.string.chat_context_meter_percent, pct)
                    },
                style = MaterialTheme.typography.labelSmall,
                color = barColor,
                maxLines = 1,
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(color = trackColor, shape = RoundedCornerShape(2.dp)),
        ) {
            if (fraction > 0f) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(3.dp)
                            .background(color = barColor, shape = RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

/**
 * Compact token formatting: 1_500 → "1.5k", 262_144 → "262k", 950 → "950".
 * Keeps the chip narrow enough to fit above the composer on a phone screen.
 */
internal fun formatTokens(tokens: Long): String =
    when {
        tokens >= 1_000_000 -> {
            "${tokens / 1_000_000}M"
        }

        tokens >= 100_000 -> {
            "${tokens / 1000}k"
        }

        tokens >= 1_000 -> {
            val k = tokens / 1000.0
            if (k % 1.0 == 0.0) "${k.toInt()}k" else String.format(Locale.US, "%.1fk", k)
        }

        else -> {
            tokens.toString()
        }
    }
