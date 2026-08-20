package com.m57.hermescontrol.ui.chat

/** A neutral timeline classification for backend-authored conversation scaffolding. */
data class SystemTimelineEvent(
    val kind: String,
    val model: String? = null,
)

private const val MAX_ITERATIONS_SYSTEM_MARKER =
    "You've reached the maximum number of tool-calling iterations allowed."

/**
 * Classifies timeline events without changing the stored message. Backend tags
 * take precedence and every non-null tag is fail-safe: unknown tags still
 * become neutral system events. The sole prefix fallback mirrors the backend's
 * stable max-iterations nudge, whose display tag is stripped on persistence;
 * broader bracket-like prefixes would hide genuine user-authored content.
 */
fun classifySystemTimelineEvent(message: ChatMessage): SystemTimelineEvent? {
    message.displayKind?.let { kind ->
        return SystemTimelineEvent(kind = kind, model = modelFromTimelineContent(message.content))
    }
    if (message.role != MessageRole.USER) return null
    if (!message.content.startsWith(MAX_ITERATIONS_SYSTEM_MARKER)) return null
    return SystemTimelineEvent(kind = "max_iterations_reached")
}

internal fun modelFromTimelineContent(content: String): String? =
    Regex("""changed to ([A-Za-z0-9_.\-]+)""")
        .find(content)
        ?.groupValues
        ?.getOrNull(1)
        ?.removeSuffix(".")
        ?.takeIf { it.isNotBlank() }
