package com.m57.hermescontrol.ui.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpOAuthPolicyTest {
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
