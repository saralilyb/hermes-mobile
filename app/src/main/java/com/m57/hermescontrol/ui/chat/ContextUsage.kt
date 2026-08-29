// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.ui.chat

import java.util.Locale

/**
 * Live context-window occupancy plus the session's cumulative token totals.
 *
 * Sourced from the `usage` block the gateway attaches to `session.info` and
 * `message.complete` (hermes-agent `tui_gateway/server.py`, `_get_usage`) —
 * deliberately **not** from `GET /api/sessions/{id}`. That REST row's
 * `input_tokens` column is the session's cumulative *lifetime* prompt total, so
 * using it as the meter numerator reports impossible fills: a 1.9M reading
 * against a 120k window, clamped to 100%. The backend refuses that exact
 * substitution on its own status surfaces and emits `context_used` /
 * `context_max` only when it holds a real current-window figure, omitting them
 * otherwise rather than fabricating a 0%.
 *
 * [usedTokens] and [maxTokens] are therefore nullable: absent means "this
 * context engine does not report per-window occupancy" and must render as
 * unknown. The remaining fields are cumulative session counters — useful as a
 * breakdown, never as the gauge numerator.
 */
data class ContextUsage(
    /** Current-window occupancy (`usage.context_used`); null when unknown. */
    val usedTokens: Long? = null,
    /** Context window of the active model (`usage.context_max`). */
    val maxTokens: Long? = null,
    /** Cumulative prompt tokens across the session (`usage.input`). */
    val inputTokens: Long = 0L,
    /** Cumulative completion tokens across the session (`usage.output`). */
    val outputTokens: Long = 0L,
    /** Cumulative reasoning tokens across the session (`usage.reasoning`). */
    val reasoningTokens: Long = 0L,
    /** Cumulative total tokens across the session (`usage.total`). */
    val totalTokens: Long = 0L,
    /** Provider API calls made by the session (`usage.calls`). */
    val apiCalls: Long = 0L,
    /** Automatic compactions performed so far (`usage.compressions`). */
    val compressions: Long = 0L,
    /** Model the usage was recorded against (`usage.model`). */
    val model: String = "",
)

/**
 * Coerce a JSON-decoded WebSocket payload value to [Long].
 *
 * `JsonElement.toAny()` tries `doubleOrNull` before `longOrNull`, so integral
 * counts arrive as [Double] — a plain `as? Long` cast silently yields null for
 * every token count the gateway sends. Usage counts must still be exact
 * integers; `Number.toLong()` would incorrectly truncate fractional values.
 */
private fun Any?.asUsageLong(): Long? =
    when (this) {
        is Number -> {
            try {
                toString()
                    .toBigDecimalOrNull()
                    ?.toBigIntegerExact()
                    ?.toString()
                    ?.toLongOrNull()
            } catch (_: ArithmeticException) {
                null
            }
        }

        is String -> {
            trim().toLongOrNull()
        }

        else -> {
            null
        }
    }

/**
 * Fold a gateway `usage` block into [ContextUsage].
 *
 * An absent/empty usage block means an older gateway supplied no update, so the
 * prior reading is retained. A non-empty block that omits `context_used` is
 * different: the gateway deliberately reports occupancy as unknown (for
 * example, immediately after compaction or for an external context engine).
 * That clears the numerator rather than displaying a stale pre-compaction fill.
 * The window maximum and cumulative counters may still retain their latest
 * known values when a partial block omits them.
 */
internal fun parseContextUsage(
    usage: Map<*, *>?,
    previous: ContextUsage? = null,
): ContextUsage? {
    if (usage.isNullOrEmpty()) return previous
    val used = usage["context_used"].asUsageLong()?.takeIf { it >= 0L }
    val max = usage["context_max"].asUsageLong()?.takeIf { it > 0L }
    return ContextUsage(
        usedTokens = used,
        maxTokens = max ?: previous?.maxTokens,
        inputTokens = usage["input"].asUsageLong() ?: previous?.inputTokens ?: 0L,
        outputTokens = usage["output"].asUsageLong() ?: previous?.outputTokens ?: 0L,
        reasoningTokens = usage["reasoning"].asUsageLong() ?: previous?.reasoningTokens ?: 0L,
        totalTokens = usage["total"].asUsageLong() ?: previous?.totalTokens ?: 0L,
        apiCalls = usage["calls"].asUsageLong() ?: previous?.apiCalls ?: 0L,
        compressions = usage["compressions"].asUsageLong() ?: previous?.compressions ?: 0L,
        model = (usage["model"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.model.orEmpty(),
    )
}

/**
 * Return a REST context-window fallback only when it belongs to the active
 * session model. Exact qualified identifiers are preferred; a bare identifier
 * may match the suffix of a provider-qualified identifier.
 */
internal fun matchingModelContextLength(
    activeModel: String?,
    fallbackModel: String?,
    fallbackLength: Long?,
): Long? {
    val length = fallbackLength?.takeIf { it > 0L } ?: return null
    val active = activeModel?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    val fallback = fallbackModel?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    val matches =
        active == fallback ||
            ('/' !in active && fallback.endsWith("/$active")) ||
            ('/' !in fallback && active.endsWith("/$fallback"))
    return length.takeIf { matches }
}
