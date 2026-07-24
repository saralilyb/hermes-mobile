package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionTreeItem
import com.m57.hermescontrol.data.model.flattenSessionTree
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall

interface SessionPinStore {
    fun load(): List<String>

    fun save(pinIds: List<String>)
}

internal class AuthManagerSessionPinStore(
    private val profileId: String =
        AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
) : SessionPinStore {
    override fun load(): List<String> = AuthManager.getPinnedSessionIds(profileId)

    override fun save(pinIds: List<String>) {
        AuthManager.savePinnedSessionIds(pinIds, profileId)
    }
}

internal fun SessionInfo.pinId(): String = _lineage_root_id?.takeIf(String::isNotBlank) ?: id

internal fun SessionInfo.matchesPin(pinId: String): Boolean = id == pinId || _lineage_root_id == pinId

internal data class SessionSections(
    val pinned: List<SessionTreeItem>,
    val recent: List<SessionTreeItem>,
)

internal fun buildSessionSections(
    sessions: List<SessionInfo>,
    pinnedSessionIds: List<String>,
): SessionSections {
    val flattened = flattenSessionTree(sessions)
    val seenSessionIds = mutableSetOf<String>()
    val pinned =
        pinnedSessionIds.mapNotNull { pinId ->
            flattened
                .firstOrNull {
                    it.session.matchesPin(pinId) &&
                        seenSessionIds.add(it.session.id)
                }
                ?.copy(depth = 0, branchStem = null)
        }
    val pinnedIds = pinned.mapTo(mutableSetOf()) { it.session.id }
    val recentSessions = sessions.filterNot { it.id in pinnedIds }
    return SessionSections(
        pinned = pinned,
        recent = flattenSessionTree(recentSessions),
    )
}

internal suspend fun hydratePinnedSessions(
    api: HermesApiService,
    pinIds: List<String>,
): List<SessionInfo> =
    pinIds.mapNotNull { pinId ->
        val resolvedSessionId =
            when (val result = safeApiCall { api.getSessionLatestDescendant(pinId) }) {
                is NetworkResult.Success -> result.data.session_id.takeIf(String::isNotBlank) ?: pinId
                is NetworkResult.Failure -> pinId
            }
        when (val result = safeApiCall { api.getSession(resolvedSessionId) }) {
            is NetworkResult.Success -> {
                val session = result.data
                if (session.matchesPin(pinId)) {
                    session
                } else {
                    session.copy(_lineage_root_id = pinId)
                }
            }

            is NetworkResult.Failure -> null
        }
    }

internal fun remainingPinsAfterDeleting(
    pinnedSessionIds: List<String>,
    sessions: List<SessionInfo>,
    deletedSessionIds: Set<String>,
): List<String> {
    val deletedPinIds =
        sessions
            .filter { it.id in deletedSessionIds }
            .mapTo(deletedSessionIds.toMutableSet()) { it.pinId() }
    return pinnedSessionIds.filterNot { it in deletedPinIds }
}

internal fun mergeSessionPage(
    previous: List<SessionInfo>,
    incoming: List<SessionInfo>,
    pinnedSessionIds: List<String>,
): List<SessionInfo> {
    val incomingKeys = incoming.flatMap { listOf(it.id, it.pinId()) }.toSet()
    val preservedPins =
        previous.filter { session ->
            pinnedSessionIds.any(session::matchesPin) &&
                session.id !in incomingKeys &&
                session.pinId() !in incomingKeys
        }
    return preservedPins + incoming
}

internal fun mergeSessionRows(
    existing: List<SessionInfo>,
    incoming: List<SessionInfo>,
): List<SessionInfo> {
    if (incoming.isEmpty()) return existing
    val incomingKeys = incoming.flatMap { listOf(it.id, it.pinId()) }.toSet()
    return existing.filterNot { it.id in incomingKeys || it.pinId() in incomingKeys } +
        incoming
}
