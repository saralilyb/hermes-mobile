package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.model.ProjectFolder
import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.data.model.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionProjectTest {
    private fun resolve(
        path: String,
        projects: List<ProjectInfo> = emptyList(),
    ) = resolveSessionProject(SessionInfo("s", cwd = path), projects)

    @Test
    fun `nested folder wins and siblings do not match`() {
        val projects =
            listOf(
                ProjectInfo("outer", folders = listOf(ProjectFolder("/srv/app/"))),
                ProjectInfo("inner", folders = listOf(ProjectFolder("/srv/app/web"))),
            )
        assertEquals("project:inner", resolve("/srv/app/web/src", projects)?.key)
        assertEquals("auto:/srv/application", resolve("/srv/application", projects)?.key)
        assertEquals("auto:/srv/out", resolve("/srv/app/../out", projects)?.key)
    }

    @Test
    fun `windows case folds but posix backslashes stay literal`() {
        val projects = listOf(ProjectInfo("win", folders = listOf(ProjectFolder("C:\\Work\\App"))))
        assertEquals("project:win", resolve("c:/work/app/./src", projects)?.key)
        assertEquals("auto:/srv/a\\b", resolve("/srv/a\\b")?.key)
        assertNotEquals(resolve("/srv/a\\b")?.key, resolve("/srv/a/b")?.key)
        assertNotEquals(resolve("relative/app")?.key, resolve("/relative/app")?.key)
    }

    @Test
    fun `primary path works without folders and archived projects fall back`() {
        val p = ProjectInfo("p", name = "Same", primaryPath = "/work")
        assertEquals("project:p", resolve("/work/src", listOf(p))?.key)
        assertEquals("auto:/work/src", resolve("/work/src", listOf(p.copy(archived = true)))?.key)
        assertNull(resolveSessionProject(SessionInfo("home"), listOf(p)))
        assertEquals("auto:/", resolve("/")?.key)
    }

    @Test
    fun `primary folder breaks equal depth ties and repo root is fallback`() {
        val a = ProjectInfo("a", folders = listOf(ProjectFolder("/app")))
        val b = ProjectInfo("b", folders = listOf(ProjectFolder("/app", isPrimary = true)))
        assertEquals("project:b", resolve("/app/src", listOf(a, b))?.key)
        assertEquals(
            "project:a",
            resolveSessionProject(
                SessionInfo("s", cwd = "/elsewhere", git_repo_root = "/app"),
                listOf(a),
            )?.key,
        )
    }
}
