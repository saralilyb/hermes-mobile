package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Application-level authentication state shared by REST-backed screens. */
object AuthSessionState {
    private val _signInRequired = MutableStateFlow(false)
    val signInRequired: StateFlow<Boolean> = _signInRequired.asStateFlow()

    @PublishedApi
    internal fun requireSignIn() {
        _signInRequired.value = true
    }

    fun markAuthenticated() {
        _signInRequired.value = false
    }
}
