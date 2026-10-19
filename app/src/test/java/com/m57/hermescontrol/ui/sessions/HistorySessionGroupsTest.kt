package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.model.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistorySessionGroupsTest {
    @Test
    fun `automation ignores project filters and collapse state`() {
        val run = SessionInfo("cron_job_20260905_100000", source = "cron", cwd = "/app")
        val state =
            SessionsUiState(
                section = HistorySection.AUTOMATIONS,
                sessions = listOf(run),
                projectFilter = "auto:/elsewhere",
                collapsedProjectGroups = setOf("auto:/app"),
            )
        assertEquals(emptyList<Any>(), state.availableProjects)
        assertEquals(listOf(run), state.projectFilteredSessions)
        assertEquals("job", automationGroups(state.projectFilteredSessions).single().jobId)
    }

    @Test
    fun `groups durable job ids not titles`() {
        val groups =
            automationGroups(
                listOf(
                    SessionInfo("cron_job_one_20260905_100000", title = "Same", source = "cron"),
                    SessionInfo("cron_job_two_20260905_100000", title = "Same", source = "cron"),
                    SessionInfo("cron_job_one_20260905_110000", title = "Renamed", source = "cron"),
                ),
            )

        assertEquals(listOf("job_one", "job_two"), groups.map { it.jobId })
        assertEquals(2, groups.first().sessions.size)
        assertEquals(
            "cron_job_one_20260905_110000",
            groups
                .first()
                .sessions
                .last()
                .id,
        )
    }

    @Test
    fun `unknown run ids remain independently accessible`() {
        val groups =
            automationGroups(
                listOf(
                    SessionInfo("legacy-a", title = "Same", source = "cron"),
                    SessionInfo("legacy-b", title = "Same", source = "cron"),
                ),
            )

        assertEquals(2, groups.size)
        assertNull(groups.first().jobId)
        assertEquals(listOf("legacy-a", "legacy-b"), groups.flatMap { it.sessions }.map { it.id })
    }

    @Test
    fun `repeated page rows do not duplicate runs`() {
        val run = SessionInfo("cron_job_20260905_100000", source = "cron")

        assertEquals(1, automationGroups(listOf(run, run)).single().sessions.size)
    }

    @Test
    fun `cron-like conversation id does not invent a job`() {
        val groups = automationGroups(listOf(SessionInfo("cron_job_20260905_100000", source = "cli")))

        assertNull(groups.single().jobId)
    }

    @Test
    fun `derives job title from the first run while grouping by job id`() {
        val groups =
            automationGroups(
                listOf(
                    SessionInfo(
                        "cron_nightly_20260905_100000",
                        title = "Nightly Backup · Sep 05 10:00",
                        source = "cron",
                    ),
                    SessionInfo(
                        "cron_nightly_20260905_090000",
                        title = "Nightly Backup · Sep 05 09:00",
                        source = "cron",
                    ),
                ),
            )

        assertEquals(1, groups.size)
        assertEquals("nightly", groups.single().jobId)
        assertEquals("Nightly Backup", groups.single().title)
    }

    @Test
    fun `untitled runs without job identity fall back to no title`() {
        val groups = automationGroups(listOf(SessionInfo("legacy-run", title = "   ", source = "cron")))

        assertNull(groups.single().jobId)
        assertNull(groups.single().title)
    }

    @Test
    fun `section membership follows the session source`() {
        val automation = SessionInfo("cron_job_20260905_100000", source = "cron")
        val conversation = SessionInfo("chat-1", source = "cli")

        assertEquals(true, automation.inSection(HistorySection.AUTOMATIONS))
        assertEquals(false, automation.inSection(HistorySection.CONVERSATIONS))
        assertEquals(true, conversation.inSection(HistorySection.CONVERSATIONS))
        assertEquals(false, conversation.inSection(HistorySection.AUTOMATIONS))
    }
}
