package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionTreeItem
import java.util.Locale

const val NO_PROJECT_KEY = "home:"

data class SessionProject(val key: String, val label: String, val color: String? = null)

data class ProjectGroup(val key: String, val label: String, val color: String?, val items: List<SessionTreeItem>)

/** Pure lexical inference for display only. Never interprets a server path on Android's filesystem. */
private data class WorkspacePath(val root: String, val parts: List<String>) {
    val key: String get() = root + parts.joinToString("/")

    fun contains(other: WorkspacePath): Boolean =
        root == other.root && parts.size <= other.parts.size && parts.indices.all { parts[it] == other.parts[it] }
}

private val windowsDrive = Regex("^[A-Za-z]:[/\\\\]")

private val windowsUnc = Regex("""^(?:\\\\|//)[^/\\]+[/\\][^/\\]+(?:[/\\]|$)""")

private fun workspacePath(raw: String?): WorkspacePath? {
    val text = raw?.takeIf { it.isNotBlank() } ?: return null
    // Projects P2: a single leading backslash is a literal POSIX relative filename.
    val windows = windowsDrive.containsMatchIn(text) || windowsUnc.containsMatchIn(text)
    val value = if (windows) text.replace('\\', '/').lowercase(Locale.ROOT) else text
    val root =
        when {
            windowsDrive.containsMatchIn(value) -> value.take(2) + "/"
            windows && value.startsWith("//") -> "//"
            value.startsWith('/') -> "/"
            else -> "relative:"
        }
    val body = if (windowsDrive.containsMatchIn(value)) value.drop(3) else value.trimStart('/')
    val parts = mutableListOf<String>()
    body.split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." ->
                if (parts.isNotEmpty() && parts.last() != "..") {
                    parts.removeAt(parts.lastIndex)
                } else if (root == "relative:") {
                    parts.add(part)
                }
            else -> parts.add(part)
        }
    }
    return WorkspacePath(root, parts)
}

fun resolveSessionProject(
    session: SessionInfo,
    projects: List<ProjectInfo>,
): SessionProject? {
    val cwd = workspacePath(session.cwd)
    val repo = workspacePath(session.git_repo_root)
    if (cwd == null && repo == null) return null

    data class Candidate(val project: ProjectInfo, val path: WorkspacePath, val primary: Boolean)
    val candidates =
        projects.filterNot { it.isArchived }.flatMap { p ->
            val folders =
                p.folders.mapNotNull { f ->
                    workspacePath(f.path)?.let { Candidate(p, it, f.isPrimary == true) }
                }
            val primary = workspacePath(p.primaryPath)?.let { Candidate(p, it, true) }
            if (primary == null) folders else listOf(primary) + folders
        }
    // Prefer cwd ownership, then recorded repo ownership; deepest folder and primary break ties.
    val owner =
        listOfNotNull(cwd, repo).firstNotNullOfOrNull { target ->
            candidates.filter { it.path.contains(target) }
                .maxWithOrNull(compareBy<Candidate> { it.path.parts.size }.thenBy { it.primary })
        }
    if (owner != null) {
        return SessionProject(
            "project:${owner.project.id}",
            owner.project.name.takeIf { it.isNotBlank() } ?: owner.path.parts.lastOrNull().orEmpty(),
            owner.project.color,
        )
    }
    val auto = repo ?: cwd ?: return null
    val raw = if (repo != null) session.git_repo_root else session.cwd
    val label =
        raw.orEmpty().trimEnd('/', '\\').substringAfterLast('/')
            .let { if (auto.root.endsWith(":/") || auto.root == "//") it.substringAfterLast('\\') else it }
            .ifEmpty { auto.key }
    return SessionProject("auto:${auto.key}", label)
}

fun sessionProjectKey(
    session: SessionInfo,
    projects: List<ProjectInfo>,
): String = resolveSessionProject(session, projects)?.key ?: NO_PROJECT_KEY

fun groupSessionsByProject(
    items: List<SessionTreeItem>,
    projects: List<ProjectInfo>,
): List<ProjectGroup> =
    items.groupBy { sessionProjectKey(it.session, projects) }.map { (key, rows) ->
        val project = resolveSessionProject(rows.first().session, projects)
        ProjectGroup(key, project?.label.orEmpty(), project?.color, rows)
    }.sortedBy { it.key == NO_PROJECT_KEY }

/** Remove direction overrides in untrusted labels, then isolate the entire displayed value. */
fun projectDisplayText(raw: String): String =
    "\u2068" +
        raw.take(512)
            .filterNot { it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' || it.isISOControl() } + "\u2069"
