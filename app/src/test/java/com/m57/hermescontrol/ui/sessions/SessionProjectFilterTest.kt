package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionTreeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProjectFilterTest {
    private val projects =
        listOf(
            ProjectInfo("a", name = "Same", primaryPath = "/a"),
            ProjectInfo("b", name = "Same", primaryPath = "/b"),
        )
    private val rows = listOf(SessionInfo("a", cwd = "/a"), SessionInfo("b", cwd = "/b"), SessionInfo("h"))

    @Test
    fun `duplicate labels never merge project identities`() {
        val groups = groupSessionsByProject(rows.map { SessionTreeItem(it, 0, null, displayTitle = it.id) }, projects)
        assertEquals(listOf("project:a", "project:b", NO_PROJECT_KEY), groups.map { it.key })
        assertEquals(listOf("Same", "Same", ""), groups.map { it.label })
        val auto = listOf(SessionInfo("x", cwd = "/one/app"), SessionInfo("y", cwd = "/two/app"))
        assertEquals(
            2,
            groupSessionsByProject(auto.map { SessionTreeItem(it, 0, null, displayTitle = it.id) }, projects).size,
        )
    }

    @Test
    fun `filter is loaded recent scope and search bypasses it`() {
        val state = SessionsUiState(sessions = rows, projects = projects, projectFilter = "project:a")
        assertEquals(listOf("a"), state.projectFilteredSessions.map { it.id })
        assertEquals(3, state.availableProjects.size)
        assertEquals(rows, state.copy(searchQuery = "anything").projectFilteredSessions)
        assertTrue(state.copy(searchQuery = "anything").availableProjects.isEmpty())
        assertEquals(rows, state.copy(projectFilter = "gone").projectFilteredSessions)
    }
}
