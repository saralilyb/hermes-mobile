package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage

/**
 * Keeps reasoning-only assistant rows while dropping empty tool-call
 * placeholders that have no user-visible content.
 */
internal fun isDisplayableServerMessage(message: SessionMessage): Boolean {
    val isAssistant =
        when (message.role?.lowercase()) {
            "user", "system", "tool" -> false
            else -> true
        }
    if (!isAssistant) return true

    return message.contentText.isNotBlank() ||
        message.reasoningText.isNotBlank()
}
