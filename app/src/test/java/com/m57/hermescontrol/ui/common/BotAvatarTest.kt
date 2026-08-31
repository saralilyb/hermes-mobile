package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotAvatarTest {
    @Test
    fun `shapes are strictly allowlisted`() {
        assertEquals(CircleShape, resolveAvatarShape("circle", 40.dp))
        assertTrue(resolveAvatarShape("square", 40.dp) is RoundedCornerShape)
        assertTrue(resolveAvatarShape("hexagon", 40.dp) is CutCornerShape)
        assertEquals(CircleShape, resolveAvatarShape("diamond", 40.dp))
        assertEquals(CircleShape, resolveAvatarShape("unknown", 40.dp))
    }

    @Test
    fun `icons are strictly allowlisted`() {
        assertEquals(Icons.Filled.Code, resolveAvatarIcon("code"))
        assertNull(resolveAvatarIcon("terminal"))
        assertNull(resolveAvatarIcon("https://example.invalid/icon"))
    }

    @Test
    fun `initials are bounded and validated`() {
        assertEquals("DS", extractInitials("daily-sync"))
        assertEquals("A", extractInitials("a"))
        assertEquals("?", extractInitials("  @---__  "))
        assertEquals("AB", extractInitials("alpha beta gamma"))
    }

    @Test
    fun `colors accept only opaque RGB hex`() {
        val fallback = Color.Gray
        assertEquals(Color(0xFF123ABC), resolveAvatarColor("#123ABC", fallback))
        assertEquals(fallback, resolveAvatarColor("#80123456", fallback))
        assertEquals(fallback, resolveAvatarColor("red", fallback))
        assertEquals(fallback, resolveAvatarColor(null, fallback))
    }
}
