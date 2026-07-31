package com.m57.hermescontrol.ui.mcp

import java.net.URI

internal enum class OAuthFlowState {
    PENDING,
    SUCCEEDED,
    FAILED,
}

/** Closed policy for the dashboard-hosted MCP OAuth flow. */
internal object McpOAuthPolicy {
    const val POLL_INTERVAL_MS = 2_000L
    const val MAX_CONSECUTIVE_POLL_FAILURES = 3
    const val MAX_POLL_ATTEMPTS = 450

    fun classify(status: String): OAuthFlowState =
        when (status) {
            "authorization_required" -> OAuthFlowState.PENDING
            "approved", "completed" -> OAuthFlowState.SUCCEEDED
            else -> OAuthFlowState.FAILED
        }

    /**
     * Accept only ordinary HTTPS authorization URLs. The URL comes from a
     * remote dashboard response and is handed to another app through an
     * Android intent, so fail closed on malformed URLs, credentials in the
     * authority, custom schemes, and unbounded input.
     */
    fun authorizationUrlOrNull(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.length in 1..8_192 } ?: return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return value
    }
}
