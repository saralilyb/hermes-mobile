package com.m57.hermescontrol.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFontScaleTest {
    @Test
    fun `effective chat density multiplies system font scale and preserves density`() {
        val effective = effectiveChatDensity(systemDensity = 2.75f, systemFontScale = 1.2f, chatFontScale = 1.3f)

        assertEquals(2.75f, effective.density)
        assertEquals(1.56f, effective.fontScale, 0.0001f)
    }
}
