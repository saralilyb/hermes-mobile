package com.m57.hermescontrol.data.model

import com.m57.hermescontrol.data.remote.OkHttpProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectModelsTest {
    @Test
    fun `real snake case fixture and sqlite booleans decode`() {
        val result =
            OkHttpProvider.json.decodeFromString<ProjectsListResponse>(
                """{"projects":[{"id":"p","slug":"app","name":"App","description":"Notes","icon":null,
            "color":"hsl(210 68% 58%)","board_slug":"board","primary_path":"/srv/app","archived":0,
            "created_at":1787590315,"folders":[{"path":"/srv/app","is_primary":1,"added_at":1787590315}]}],
            "active_id":"p"}""",
            )
        assertEquals("p", result.activeId)
        val p = result.projects.single()
        assertEquals("/srv/app", p.primaryPath)
        assertEquals("board", p.boardSlug)
        assertFalse(p.isArchived)
        assertEquals(true, p.folders.single().isPrimary)
        assertEquals(1787590315L, p.createdAt)
    }

    @Test
    fun `workspace fields are optional and decode real wire names`() {
        val row =
            OkHttpProvider.json.decodeFromString<SessionInfo>(
                """{"id":"s","cwd":"/srv/app","git_repo_root":"/srv","last_active":123.5}""",
            )
        assertEquals("/srv/app", row.cwd)
        assertEquals("/srv", row.git_repo_root)
        assertEquals(123.5, row.last_active)
        assertNull(OkHttpProvider.json.decodeFromString<SessionInfo>("""{"id":"s"}""").cwd)
        assertTrue(OkHttpProvider.json.decodeFromString<ProjectInfo>("""{"id":"p","archived":true}""").isArchived)
    }
}
