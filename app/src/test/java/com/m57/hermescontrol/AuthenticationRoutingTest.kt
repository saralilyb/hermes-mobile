package com.m57.hermescontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticationRoutingTest {
    @Test
    fun `expired authentication disconnects and routes to login`() {
        var disconnected = false
        var routedToLogin = false

        routeExpiredAuthentication(
            signInRequired = true,
            disconnect = { disconnected = true },
            showLogin = { routedToLogin = true },
        )

        assertTrue(disconnected)
        assertTrue(routedToLogin)
    }

    @Test
    fun `valid authentication leaves connection and navigation unchanged`() {
        var disconnected = false
        var routedToLogin = false

        routeExpiredAuthentication(
            signInRequired = false,
            disconnect = { disconnected = true },
            showLogin = { routedToLogin = true },
        )

        assertFalse(disconnected)
        assertFalse(routedToLogin)
    }

    @Test
    fun `expired authentication blocks back navigation`() {
        var navigatedBack = false

        navigateBackUnlessSignInRequired(
            signInRequired = true,
            navigateBack = { navigatedBack = true },
        )

        assertFalse(navigatedBack)
    }

    @Test
    fun `valid authentication allows back navigation`() {
        var navigatedBack = false

        navigateBackUnlessSignInRequired(
            signInRequired = false,
            navigateBack = { navigatedBack = true },
        )

        assertTrue(navigatedBack)
    }
}
