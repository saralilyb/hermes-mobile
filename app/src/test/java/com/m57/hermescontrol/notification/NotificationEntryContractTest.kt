package com.m57.hermescontrol.notification

import com.m57.hermescontrol.PendingSessionTarget
import com.m57.hermescontrol.idForProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationEntryContractTest {
    @Test
    fun `notification actions are scoped to each application id`() {
        assertEquals(
            "sh.slb.hermesmobile.ACTION_OPEN_CHAT_FROM_NOTIFICATION",
            openChatAction("sh.slb.hermesmobile"),
        )
        assertEquals(
            "sh.slb.irismobile.ACTION_OPEN_CHAT_FROM_NOTIFICATION",
            openChatAction("sh.slb.irismobile"),
        )
    }

    @Test
    fun `matching action and profile route to the requested chat`() {
        assertEquals(
            NotificationEntryDecision.OpenChat(
                sessionId = "session-abc",
                profileId = "profile-a",
            ),
            resolveNotificationEntry(
                action = openChatAction(APP_ID),
                applicationId = APP_ID,
                sessionId = "session-abc",
                sourceProfileId = "profile-a",
                activeProfileId = "profile-a",
            ),
        )
    }

    @Test
    fun `notification without session opens app without routing`() {
        assertEquals(
            NotificationEntryDecision.OpenApp,
            resolveNotificationEntry(
                action = openChatAction(APP_ID),
                applicationId = APP_ID,
                sessionId = null,
                sourceProfileId = "profile-a",
                activeProfileId = "profile-a",
            ),
        )
    }

    @Test
    fun `notification from another profile cannot route a session`() {
        assertEquals(
            NotificationEntryDecision.OpenApp,
            resolveNotificationEntry(
                action = openChatAction(APP_ID),
                applicationId = APP_ID,
                sessionId = "session-abc",
                sourceProfileId = "profile-a",
                activeProfileId = "profile-b",
            ),
        )
    }

    @Test
    fun `notification without profile provenance cannot route a session`() {
        assertEquals(
            NotificationEntryDecision.OpenApp,
            resolveNotificationEntry(
                action = openChatAction(APP_ID),
                applicationId = APP_ID,
                sessionId = "session-abc",
                sourceProfileId = null,
                activeProfileId = "profile-a",
            ),
        )
    }

    @Test
    fun `foreign action is rejected even on the private entry component`() {
        assertEquals(
            NotificationEntryDecision.Reject,
            resolveNotificationEntry(
                action = "example.foreign.OPEN_CHAT",
                applicationId = APP_ID,
                sessionId = "session-abc",
                sourceProfileId = "profile-a",
                activeProfileId = "profile-a",
            ),
        )
    }

    @Test
    fun `queued session remains bound to its source profile`() {
        val target = PendingSessionTarget("session-abc", "profile-a")

        assertEquals("session-abc", target.idForProfile("profile-a"))
        assertNull(target.idForProfile("profile-b"))
        assertNull(target.idForProfile(null))
    }

    companion object {
        private const val APP_ID = "sh.slb.hermesmobile"
    }
}
