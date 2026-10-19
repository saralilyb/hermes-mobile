package com.m57.hermescontrol.ui.cron

import org.junit.Assert.assertEquals
import org.junit.Test

class CronJobDisplayStateTest {
    @Test
    fun `gateway states expose only safe controls`() {
        listOf("scheduled", "active").forEach {
            assertEquals(true to false, CronJobDisplayState.from(it).let { it.canPause to it.canResume })
        }
        listOf("paused", "error").forEach {
            assertEquals(false to true, CronJobDisplayState.from(it).let { it.canPause to it.canResume })
        }
        listOf("completed", "future-state", null).forEach {
            assertEquals(false to false, CronJobDisplayState.from(it).let { it.canPause to it.canResume })
        }
    }
}
