package com.m57.hermescontrol.data.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialWarningStateTest {
    @Test
    fun dismissedWarningDoesNotImmediatelyReappear() {
        val state = CredentialWarningState()

        state.update(MISSING_KEY_WARNING)
        assertEquals(MISSING_KEY_WARNING, state.warning.value)

        state.dismiss()
        state.update(MISSING_KEY_WARNING)

        assertNull(state.warning.value)
    }

    @Test
    fun differentWarningAppearsAfterDismissal() {
        val state = CredentialWarningState()

        state.update(MISSING_KEY_WARNING)
        state.dismiss()
        state.update("OAuth session expired")

        assertEquals("OAuth session expired", state.warning.value)
    }

    @Test
    fun cleanCredentialReportResetsDismissal() {
        val state = CredentialWarningState()

        state.update(MISSING_KEY_WARNING)
        state.dismiss()
        state.update(null)
        state.update(MISSING_KEY_WARNING)

        assertEquals(MISSING_KEY_WARNING, state.warning.value)
    }

    private companion object {
        const val MISSING_KEY_WARNING =
            "No API key configured for provider 'meridian'. First message will fail."
    }
}
