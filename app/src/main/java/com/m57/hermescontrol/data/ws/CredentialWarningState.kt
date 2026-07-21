package com.m57.hermescontrol.data.ws

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the gateway's credential warning without letting a repeated
 * `session.info` event undo an explicit dismissal.
 */
internal class CredentialWarningState {
    private val _warning = MutableStateFlow<String?>(null)
    val warning: StateFlow<String?> = _warning.asStateFlow()

    private var dismissedWarning: String? = null

    @Synchronized
    fun update(reportedWarning: String?) {
        val warning = reportedWarning?.trim()?.takeIf { it.isNotEmpty() }
        if (warning == null) {
            dismissedWarning = null
            _warning.value = null
            return
        }
        if (warning != dismissedWarning) {
            _warning.value = warning
        }
    }

    @Synchronized
    fun dismiss() {
        dismissedWarning = _warning.value
        _warning.value = null
    }
}
