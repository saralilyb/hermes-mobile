package com.m57.hermescontrol.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide mirror of the active chat session id.
 *
 * The desktop `process.list` / `process.kill` RPCs are session-scoped — the
 * gateway returns `4001 "session not found"` without a valid `session_id`, and
 * mobile has no global "current session" holder (ChatViewModel keeps it
 * privately). This singleton holds the last-known active session id so
 * session-scoped drawer screens (e.g. the Processes screen, issue #532) can
 * issue those RPCs. ChatViewModel writes here on every switch/resume; it is a
 * best-effort mirror and may be null when no chat session has been opened yet.
 */
object ActiveSessionHolder {
    private val _activeSessionId = MutableStateFlow<String?>(null)

    @Volatile
    private var storedSessionId: String? = null

    /** The currently active runtime session id, or null if none is known yet. */
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    /**
     * Update the active runtime and storage identities.
     *
     * Hermes may return a short-lived runtime id while retaining a distinct
     * persisted session id. Notification deep links must use the persisted id.
     */
    fun set(
        runtimeSessionId: String?,
        persistedSessionId: String? = runtimeSessionId,
    ) {
        _activeSessionId.value = runtimeSessionId
        storedSessionId = persistedSessionId
    }

    fun resolveStoredSessionId(runtimeSessionId: String): String =
        if (_activeSessionId.value == runtimeSessionId) {
            storedSessionId ?: runtimeSessionId
        } else {
            runtimeSessionId
        }

    fun clear() {
        set(null)
    }
}
