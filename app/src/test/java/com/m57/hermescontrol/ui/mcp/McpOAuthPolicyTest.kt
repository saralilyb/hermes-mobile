package com.m57.hermescontrol.ui.mcp

import com.m57.hermescontrol.data.remote.NetworkError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthPolicyTest {
    @Test
    fun pollingDeadline_matchesBackendFlowLifetime() {
        assertEquals(
            5 * 60 * 1_000L,
            McpOAuthPolicy.MAX_POLL_ATTEMPTS * McpOAuthPolicy.POLL_INTERVAL_MS,
        )
    }

    @Test
    fun terminalPollErrors_stopExpiredOrUnauthorizedFlows() {
        assertTrue(McpOAuthPolicy.isTerminalPollError(NetworkError.Http(404, "gone")))
        assertTrue(McpOAuthPolicy.isTerminalPollError(NetworkError.Http(403, "forbidden")))
        assertTrue(McpOAuthPolicy.isTerminalPollError(NetworkError.AuthExpired()))
        assertFalse(McpOAuthPolicy.isTerminalPollError(NetworkError.Http(500, "retry")))
    }

    @Test
    fun authorizationUrl_acceptsHttpsProviderUrl() {
        assertEquals(
            "https://idp.example/authorize?state=opaque",
            McpOAuthPolicy.authorizationUrlOrNull(
                "https://idp.example/authorize?state=opaque",
            ),
        )
    }

    @Test
    fun authorizationUrl_rejectsCustomAndCleartextSchemes() {
        assertNull(McpOAuthPolicy.authorizationUrlOrNull("intent://authorize"))
        assertNull(
            McpOAuthPolicy.authorizationUrlOrNull(
                "http://idp.example/authorize",
            ),
        )
    }

    @Test
    fun authorizationUrl_rejectsCredentialsAndMalformedHosts() {
        assertNull(
            McpOAuthPolicy.authorizationUrlOrNull(
                "https://user:secret@idp.example/authorize",
            ),
        )
        assertNull(McpOAuthPolicy.authorizationUrlOrNull("https:///authorize"))
    }

    @Test
    fun flowStatus_isClosedToUnknownValues() {
        assertEquals(
            OAuthFlowState.PENDING,
            McpOAuthPolicy.classify("authorization_required"),
        )
        assertEquals(
            OAuthFlowState.SUCCEEDED,
            McpOAuthPolicy.classify("approved"),
        )
        assertEquals(OAuthFlowState.FAILED, McpOAuthPolicy.classify("error"))
        assertEquals(OAuthFlowState.FAILED, McpOAuthPolicy.classify("unknown"))
    }
}
