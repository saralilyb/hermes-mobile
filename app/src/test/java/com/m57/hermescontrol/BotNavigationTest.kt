package com.m57.hermescontrol

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.data.model.CanonicalSessionInfo
import com.m57.hermescontrol.data.model.ProfileInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BotNavigationTest {
    @After
    fun tearDown() {
        NavigationController.backStack = null
        NavigationController.pendingSessionTarget = null
    }

    @Test
    fun `opening bot queues canonical resolved identity with selected connection profile`() {
        val stack = NavBackStack<NavKey>(BotsScreen)
        NavigationController.backStack = stack
        val bot = ProfileInfo("bot", canonical_session = CanonicalSessionInfo(id = "root", resolved_id = "tip"))

        NavigationController.openBot(bot, "connection-a")

        assertEquals(PendingSessionTarget("tip", "connection-a"), NavigationController.pendingSessionTarget)
        assertEquals(ChatScreen, stack.lastOrNull())
        assertEquals("tip", NavigationController.pendingSessionTarget?.idForProfile("connection-a"))
        assertNull(NavigationController.pendingSessionTarget?.idForProfile("connection-b"))
    }

    @Test
    fun `opening bot without identity or selected profile does nothing`() {
        val stack = NavBackStack<NavKey>(BotsScreen)
        NavigationController.backStack = stack

        NavigationController.openBot(ProfileInfo("no-session"), "connection-a")
        NavigationController.openBot(
            ProfileInfo("bot", canonical_session = CanonicalSessionInfo("root")),
            " ",
        )

        assertNull(NavigationController.pendingSessionTarget)
        assertEquals(listOf(BotsScreen), stack.toList())
    }
}
