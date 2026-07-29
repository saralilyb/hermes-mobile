// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.ContextUsage
import java.text.NumberFormat
import java.util.Locale

/** Detailed, explicitly separated view of live occupancy and lifetime totals. */
@Composable
fun ContextUsageDialog(
    usage: ContextUsage?,
    fullTokens: Long,
    model: String?,
    onDismiss: () -> Unit,
) {
    val usedTokens = usage?.usedTokens
    val percent =
        if (usedTokens != null && fullTokens > 0L) {
            ((usedTokens.toDouble() / fullTokens.toDouble()) * 100.0)
                .toInt()
                .coerceIn(0, 100)
        } else {
            null
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_context_detail_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ContextUsageChip(
                    usedTokens = usedTokens,
                    fullTokens = fullTokens,
                )

                if (percent == null) {
                    Text(
                        text = stringResource(R.string.chat_context_detail_unknown_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.chat_context_detail_unknown_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.chat_context_detail_used, percent),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (usage != null) {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.chat_context_detail_totals),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    UsageRow(
                        label = stringResource(R.string.chat_context_detail_input),
                        value = formatExactTokens(usage.inputTokens),
                    )
                    UsageRow(
                        label = stringResource(R.string.chat_context_detail_output),
                        value = formatExactTokens(usage.outputTokens),
                    )
                    UsageRow(
                        label = stringResource(R.string.chat_context_detail_reasoning),
                        value = formatExactTokens(usage.reasoningTokens),
                    )
                    UsageRow(
                        label = stringResource(R.string.chat_context_detail_total),
                        value = formatExactTokens(usage.totalTokens),
                    )
                    UsageRow(
                        label = stringResource(R.string.chat_context_detail_calls),
                        value = formatExactTokens(usage.apiCalls),
                    )
                    UsageRow(
                        label = stringResource(R.string.chat_context_detail_compressions),
                        value = formatExactTokens(usage.compressions),
                    )
                    val resolvedModel =
                        usage.model.takeIf { it.isNotBlank() }
                            ?: model?.takeIf { it.isNotBlank() }
                    resolvedModel?.let { modelName ->
                        UsageRow(
                            label = stringResource(R.string.chat_context_detail_model),
                            value = modelName,
                        )
                    }
                    Text(
                        text = stringResource(R.string.chat_context_detail_footnote),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        },
    )
}

@Composable
private fun UsageRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun formatExactTokens(tokens: Long): String =
    NumberFormat.getIntegerInstance(Locale.getDefault()).format(tokens)
