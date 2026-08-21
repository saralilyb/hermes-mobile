package com.m57.hermescontrol.ui.chat.components

import com.m57.hermescontrol.ui.chat.SubagentIndicator
import com.m57.hermescontrol.ui.chat.TodoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickySubagentBarTest {
    private fun todo(
        content: String,
        status: String,
    ) = TodoItem(id = content, content = content, status = status)

    @Test
    fun `display selects one task for both number and content`() {
        val todos =
            listOf(
                todo("pending first", "pending"),
                todo("active second", "in_progress"),
            )

        val display = computeStickyProgressDisplay(todos, emptyList())

        assertEquals(2, display.currentTaskNumber)
        assertEquals("active second", display.currentTaskContent)
        assertEquals(2, display.totalTasks)
    }

    @Test
    fun `display falls back to first pending task`() {
        val todos =
            listOf(
                todo("pending first", "pending"),
                todo("pending second", "pending"),
            )

        val display = computeStickyProgressDisplay(todos, emptyList())

        assertEquals(1, display.currentTaskNumber)
        assertEquals("pending first", display.currentTaskContent)
    }

    @Test
    fun `display counts only running subagents`() {
        val indicators =
            listOf(
                SubagentIndicator(type = "subagent.start", status = "running"),
                SubagentIndicator(type = "subagent.complete", status = "completed"),
            )

        val display = computeStickyProgressDisplay(emptyList(), indicators)

        assertEquals(1, display.activeAgents)
        assertFalse(display.hasTodos)
        assertNull(display.currentTaskContent)
    }

    @Test
    fun `terminal todos do not displace a running agent label`() {
        val todos = listOf(todo("done", "completed"))
        val indicators =
            listOf(
                SubagentIndicator(type = "subagent.start", status = "running"),
            )

        val display = computeStickyProgressDisplay(todos, indicators)

        assertFalse(display.hasTodos)
        assertEquals(1, display.activeAgents)
    }

    @Test
    fun `bar remains visible for pending work`() {
        assertTrue(
            shouldShowStickyProgress(
                listOf(todo("pending", "pending")),
                emptyList(),
            ),
        )
    }

    @Test
    fun `bar hides after all work is terminal`() {
        val todos =
            listOf(
                todo("done", "completed"),
                todo("cancelled", "cancelled"),
            )
        val indicators =
            listOf(
                SubagentIndicator(
                    type = "subagent.complete",
                    status = "completed",
                ),
            )

        assertFalse(shouldShowStickyProgress(todos, indicators))
    }
}
