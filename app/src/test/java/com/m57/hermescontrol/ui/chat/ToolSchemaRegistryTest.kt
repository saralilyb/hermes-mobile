package com.m57.hermescontrol.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Terminal
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolSchemaRegistryTest {
    @Test
    fun `tool configs use material icons`() {
        assertEquals(
            Icons.Filled.Terminal,
            ToolSchemaRegistry.getDisplayConfig("terminal").icon,
        )
        assertEquals(
            Icons.Filled.Description,
            ToolSchemaRegistry.getDisplayConfig("read_file").icon,
        )
    }

    @Test
    fun `only terminal keeps a textual summary prefix`() {
        assertEquals(
            "$ ",
            ToolSchemaRegistry.getDisplayConfig("terminal").summaryPrefix,
        )
        assertEquals(
            "",
            ToolSchemaRegistry.getDisplayConfig("read_file").summaryPrefix,
        )
        assertEquals(
            "",
            ToolSchemaRegistry.getDisplayConfig("web_search").summaryPrefix,
        )
    }
}
