package com.m57.hermescontrol

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomNavigationSelectionTest {
    @Test
    fun `settings drill-down destinations keep Settings selected`() {
        val drillDownDestinations =
            listOf(
                SettingsConnection,
                SettingsAppearance,
                SettingsChat,
                SettingsNavBar,
                SettingsBehavior,
                SettingsAbout,
            )

        drillDownDestinations.forEach { destination ->
            assertEquals(
                SettingsScreen,
                selectedBottomNavDestination(destination),
            )
        }
    }

    @Test
    fun `primary destinations remain selected directly`() {
        listOf(
            ChatScreen,
            SkillsScreen,
            CronJobsScreen,
            SystemScreen,
            SettingsScreen,
        ).forEach { destination ->
            assertEquals(
                destination,
                selectedBottomNavDestination(destination),
            )
        }
    }
}
