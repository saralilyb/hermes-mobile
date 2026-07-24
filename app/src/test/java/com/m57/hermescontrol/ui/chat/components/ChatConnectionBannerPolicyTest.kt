package com.m57.hermescontrol.ui.chat.components

import com.m57.hermescontrol.data.ws.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatConnectionBannerPolicyTest {
    @Test
    fun reconnectingOffersNoCredentialAction() {
        assertEquals(
            ConnectionBannerAction.NONE,
            connectionBannerAction(ConnectionStatus.RECONNECTING),
        )
    }

    @Test
    fun disconnectedOffersReconnect() {
        assertEquals(
            ConnectionBannerAction.RECONNECT,
            connectionBannerAction(ConnectionStatus.DISCONNECTED),
        )
    }

    @Test
    fun expiredAuthenticationOffersRelogin() {
        assertEquals(
            ConnectionBannerAction.RELOGIN,
            connectionBannerAction(ConnectionStatus.AUTH_EXPIRED),
        )
    }
}
