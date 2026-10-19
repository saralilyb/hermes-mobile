package com.m57.hermescontrol.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.BulkDeleteRequest
import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.data.model.PruneRequest
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionLiveStatus
import com.m57.hermescontrol.data.model.SessionRenameRequest
import com.m57.hermescontrol.data.model.SessionSearchResult
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesProjectsSource
import com.m57.hermescontrol.data.ws.HermesSessionLiveStatusSource
import com.m57.hermescontrol.data.ws.ProjectsResult
import com.m57.hermescontrol.data.ws.ProjectsSource
import com.m57.hermescontrol.data.ws.SessionLiveConnection
import com.m57.hermescontrol.data.ws.SessionLiveStatusSource
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionStats(
    val total: Int = 0,
    val messages: Int = 0,
)

/** History is split by provenance: interactive conversations vs. scheduled automation runs. */
enum class HistorySection {
    CONVERSATIONS,
    AUTOMATIONS,
}

internal val HistorySection.showsGlobalStats: Boolean
    get() = this == HistorySection.CONVERSATIONS

internal val HistorySection.source: String?
    get() = if (this == HistorySection.AUTOMATIONS) AUTOMATION_SOURCE else null

internal val HistorySection.excludeSources: String?
    get() = if (this == HistorySection.CONVERSATIONS) AUTOMATION_SOURCE else null

data class SessionsUiState(
    val section: HistorySection = HistorySection.CONVERSATIONS,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val sessions: List<SessionInfo> = emptyList(),
    val loadedSessionIds: Set<String> = emptySet(),
    val serverOffset: Int = 0,
    val paginationExhausted: Boolean = false,
    val pinnedSessionIds: List<String> = emptyList(),
    val total: Int = 0,
    val errorMessage: String? = null,
    val stats: SessionStats = SessionStats(),
    val isLoadingStats: Boolean = false,
    val statsError: String? = null,
    val isSelecting: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val renamingSessionId: String? = null,
    val deletingSessionIds: Set<String> = emptySet(),
    val showPruneDialog: Boolean = false,
    val isPruning: Boolean = false,
    val isDeletingBulk: Boolean = false,
    val toastMessage: String? = null,
    val sessionToDeleteConfirm: String? = null,
    val showBulkDeleteConfirm: Boolean = false,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<SessionSearchResult> = emptyList(),
    val searchError: String? = null,
    val searchTitles: Map<String, String> = emptyMap(),
    val liveStatuses: Map<String, SessionLiveStatus> = emptyMap(),
    val projects: List<ProjectInfo> = emptyList(),
    val projectsStatus: ProjectsResult = ProjectsResult.Initial,
    val projectFilter: String? = null,
    val groupByProject: Boolean = true,
    val collapsedProjectGroups: Set<String> = emptySet(),
    val liveStatusesAuthoritative: Boolean = false,
) {
    /** Counts and filters apply to loaded History rows, not server totals. Pins remain untouched. */
    val availableProjects: List<ProjectGroup>
        get() =
            if (isSearchMode || section != HistorySection.CONVERSATIONS) {
                emptyList()
            } else {
                groupSessionsByProject(buildSessionSections(sessions, pinnedSessionIds).recent, projects)
            }
    val projectFilteredSessions: List<SessionInfo>
        get() =
            if (isSearchMode || availableProjects.none { it.key == projectFilter }) {
                sessions
            } else {
                sessions.filter {
                        s ->
                    pinnedSessionIds.any(s::matchesPin) || sessionProjectKey(s, projects) == projectFilter
                }
            }
    val hasMore: Boolean get() = !paginationExhausted && total > serverOffset
    val isSearchMode: Boolean get() = searchQuery.isNotBlank()
}

private fun com.m57.hermescontrol.data.model.SessionListResponse.nextOffset(requestOffset: Int): Int =
    if (limit > 0) offset + limit else requestOffset + sessions.size

class SessionsViewModel(
    private val pinStore: SessionPinStore = AuthManagerSessionPinStore(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val liveStatusSource: SessionLiveStatusSource = HermesSessionLiveStatusSource(),
    private val projectsSource: ProjectsSource =
        HermesProjectsSource(),
) : ViewModel(), ToastHost {
    private val _uiState =
        MutableStateFlow(
            SessionsUiState(pinnedSessionIds = pinStore.load()),
        )
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var loadGeneration = 0
    private var mutationOperation = 0L
    private val deleteOperations = mutableMapOf<String, Long>()
    private var bulkDeleteOperation = 0L

    /** Fence pre-mutation pages and pin hydration, including cancellation-resistant calls. */
    private fun fencePendingSessionLoads() {
        loadGeneration += 1
        loadJob?.cancel()
        loadMoreJob?.cancel()
        hydratePinsJob?.cancel()
        _uiState.update { it.copy(isLoading = false, isLoadingMore = false) }
    }

    private var statsJob: Job? = null
    private var hydratePinsJob: Job? = null
    private var liveTrackingJob: Job? = null
    private var liveSnapshotJob: Job? = null
    private var liveTrackingGeneration = 0L
    private var liveEventRevision = 0L
    private var liveTrackingState = SessionLiveTrackingState()
    private var selectedLiveProfileId: String? = null
    private var activeLiveConnection: SessionLiveConnection? = null
    private var liveRefreshInFlight = false
    private var liveRefreshPending = false
    private var hasAuthoritativeLiveSnapshot = false

    /** Page size matches the desktop sidebar while staying below the server cap. */
    private companion object {
        const val PAGE_SIZE = 50
        const val SEARCH_DEBOUNCE_MS = 300L
        const val LIVE_STATUS_POLL_INTERVAL_MS = 30_000L
    }

    /**
     * Switch between conversation and automation history. Every section owns its own
     * server-paginated window, so the previous section's rows, offset, selection, and
     * search results are dropped and its in-flight loads are fenced out by generation.
     */
    fun selectSection(section: HistorySection) {
        if (_uiState.value.section == section) return
        loadGeneration += 1
        loadJob?.cancel()
        loadJob = null
        loadMoreJob?.cancel()
        loadMoreJob = null
        hydratePinsJob?.cancel()
        searchJob?.cancel()
        val query = _uiState.value.searchQuery
        _uiState.update {
            it.copy(
                section = section,
                isLoading = false,
                isLoadingMore = false,
                sessions = emptyList(),
                loadedSessionIds = emptySet(),
                serverOffset = 0,
                paginationExhausted = false,
                total = 0,
                errorMessage = null,
                isSelecting = false,
                selectedIds = emptySet(),
                isSearching = false,
                searchResults = emptyList(),
                searchError = null,
            )
        }
        if (query.isBlank()) loadSessions() else setSearchQuery(query)
    }

    /** Load (or reload) sessions from page 0. Used by pull-to-refresh and initial load. */
    fun loadSessions() {
        loadProjects()
        loadGeneration += 1
        loadMoreJob?.cancel()
        loadMoreJob = null
        val section = _uiState.value.section
        val generation = loadGeneration
        _uiState.update { it.copy(isLoadingMore = false) }
        loadJob =
            safeLaunchLoad(
                currentJob = loadJob,
                ioDispatcher = ioDispatcher,
                apiCall = {
                    safeApiCall {
                        ApiClient.hermesApi.getSessions(
                            limit = PAGE_SIZE,
                            offset = 0,
                            order = "recent",
                            source = section.source,
                            excludeSources = section.excludeSources,
                        )
                    }
                },
                onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
                onSuccess = { data ->
                    if (generation != loadGeneration) return@safeLaunchLoad
                    val incoming = data.sessions.orEmpty()
                    val nextOffset = data.nextOffset(0)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            sessions =
                                mergeSessionPage(
                                    previous = it.sessions,
                                    incoming = incoming,
                                    pinnedSessionIds = it.pinnedSessionIds,
                                ),
                            loadedSessionIds = incoming.mapTo(mutableSetOf()) { it.id },
                            serverOffset = nextOffset,
                            paginationExhausted = incoming.isEmpty() || nextOffset <= 0,
                            total = data.total,
                            selectedIds = emptySet(),
                        )
                    }
                    hydrateMissingPinnedSessions()
                },
                onError = { errorMsg ->
                    if (generation != loadGeneration) return@safeLaunchLoad
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = "Failed to load sessions: $errorMsg",
                        )
                    }
                },
            )
    }

    /** Load the next page and append to the existing session list. */
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore || state.isSearchMode) return
        val generation = loadGeneration

        _uiState.update { it.copy(isLoadingMore = true) }
        loadMoreJob =
            viewModelScope.launch {
                val result =
                    safeApiCall {
                        // Paginate strictly on the server window: the offset counts rows the
                        // server returned, never the locally merged or filtered row count.
                        ApiClient.hermesApi.getSessions(
                            limit = PAGE_SIZE,
                            offset = state.serverOffset,
                            order = "recent",
                            source = state.section.source,
                            excludeSources = state.section.excludeSources,
                        )
                    }
                when (result) {
                    is NetworkResult.Success -> {
                        val data = result.data
                        val nextOffset = data.nextOffset(state.serverOffset)
                        _uiState.update {
                            if (generation != loadGeneration) return@update it
                            it.copy(
                                isLoadingMore = false,
                                sessions = mergeSessionRows(it.sessions, data.sessions),
                                loadedSessionIds =
                                    it.loadedSessionIds + data.sessions.map { session -> session.id },
                                serverOffset = nextOffset,
                                paginationExhausted = data.sessions.isEmpty() || nextOffset <= state.serverOffset,
                                total = data.total,
                            )
                        }
                        if (generation == loadGeneration) hydrateMissingPinnedSessions()
                    }

                    is NetworkResult.Failure -> {
                        _uiState.update {
                            if (generation != loadGeneration) return@update it
                            it.copy(
                                isLoadingMore = false,
                                errorMessage = "Failed to load more: ${result.error.message}",
                            )
                        }
                    }
                }
            }
    }

    // ── Pinned sessions ──────────────────────────────────────────────────

    fun toggleSessionPin(session: SessionInfo) {
        hydratePinsJob?.cancel()
        val state = _uiState.value
        val pinId = session.pinId()
        val updatedPins =
            if (state.pinnedSessionIds.contains(pinId)) {
                state.pinnedSessionIds - pinId
            } else {
                state.pinnedSessionIds + pinId
            }
        pinStore.save(updatedPins)
        _uiState.update {
            it.copy(
                sessions = mergeSessionRows(it.sessions, listOf(session)),
                pinnedSessionIds = updatedPins,
            )
        }
    }

    private fun hydrateMissingPinnedSessions() {
        hydratePinsJob?.cancel()
        val state = _uiState.value
        val generation = loadGeneration
        val section = state.section
        val missingPinIds =
            state.pinnedSessionIds.filter { pinId ->
                state.sessions.none { it.matchesPin(pinId) }
            }
        if (missingPinIds.isEmpty()) return

        hydratePinsJob =
            viewModelScope.launch {
                // A pin only names a lineage root, so its provenance is unknown until the
                // session is fetched; drop the ones that belong to the other section.
                val hydrated =
                    hydratePinnedSessions(
                        api = ApiClient.hermesApi,
                        pinIds = missingPinIds,
                    ).filter { it.inSection(section) }
                if (hydrated.isNotEmpty() && generation == loadGeneration) {
                    _uiState.update {
                        it.copy(sessions = mergeSessionRows(it.sessions, hydrated))
                    }
                }
            }
    }

    // ── Search (server-backed FTS5) ──────────────────────────────────

    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private var searchApi: HermesApiService? = null
    private var searchProfileId: String? = null
    private val deletedSearchIds = mutableSetOf<String>()

    /**
     * Debounced server-side session search. A non-blank query schedules a search
     * call after [SEARCH_DEBOUNCE_MS]; a blank query returns to the normal
     * paginated list mode.
     */
    fun setSearchQuery(query: String) {
        val generation = ++searchGeneration
        val section = _uiState.value.section
        val api = ApiClient.hermesApi
        val profileId = AuthManager.getSelectedProfileId()
        val connectionChanged = searchApi !== api || searchProfileId != profileId
        searchApi = api
        searchProfileId = profileId
        if (connectionChanged) deletedSearchIds.clear()
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                searchQuery = query,
                searchResults = emptyList(),
                searchError = null,
                isSearching = query.isNotBlank(),
                searchTitles = if (connectionChanged) emptyMap() else it.searchTitles,
                isSelecting = false,
                selectedIds = emptySet(),
            )
        }
        if (query.isBlank()) {
            _uiState.update {
                it.copy(searchResults = emptyList(), searchError = null, isSearching = false)
            }
            // A section switch made while searching clears the paged rows; restore them.
            if (_uiState.value.sessions.isEmpty() && !_uiState.value.isLoading) loadSessions()
            return
        }
        searchJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                if (api !== ApiClient.hermesApi || profileId != AuthManager.getSelectedProfileId()) {
                    if (generation == searchGeneration) {
                        _uiState.update {
                            it.copy(
                                isSearching = false,
                                searchResults = emptyList(),
                                searchError = null,
                                searchTitles = emptyMap(),
                            )
                        }
                    }
                    return@launch
                }
                val result =
                    safeApiCall {
                        api.searchSessions(
                            q = query,
                            profile = null,
                            source = section.source,
                            excludeSources = section.excludeSources,
                        )
                    }
                // #1186: query text alone cannot fence an A -> B -> A race.
                if (generation != searchGeneration || _uiState.value.section != section) return@launch
                if (api !== ApiClient.hermesApi || profileId != AuthManager.getSelectedProfileId()) {
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            searchResults = emptyList(),
                            searchError = null,
                            searchTitles = emptyMap(),
                        )
                    }
                    return@launch
                }
                when (result) {
                    is NetworkResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isSearching = false,
                                // #1186: LazyList uses surfaced session IDs, not lineage roots.
                                searchResults =
                                    result.data.results.distinctBy { hit -> hit.session_id }
                                        .filterNot { hit -> hit.session_id in deletedSearchIds },
                                searchError = null,
                            )
                        }
                    }

                    is NetworkResult.Failure -> {
                        _uiState.update {
                            it.copy(
                                isSearching = false,
                                searchResults = emptyList(),
                                searchError = "Search failed: ${result.error.message}",
                            )
                        }
                    }
                }
            }
    }

    // ── Stats ────────────────────────────────────────────────────────────

    fun loadStats() {
        statsJob =
            safeLaunchLoad(
                currentJob = statsJob,
                ioDispatcher = ioDispatcher,
                apiCall = {
                    safeApiCall { ApiClient.hermesApi.getSessionStats() }
                },
                onStart = { _uiState.update { it.copy(isLoadingStats = true, statsError = null) } },
                onSuccess = { data ->
                    _uiState.update {
                        it.copy(
                            isLoadingStats = false,
                            stats =
                                SessionStats(total = data.total, messages = data.messages),
                        )
                    }
                },
                onError = { errorMsg ->
                    _uiState.update {
                        it.copy(
                            isLoadingStats = false,
                            statsError = errorMsg,
                        )
                    }
                },
            )
    }

    // ── Bulk selection ───────────────────────────────────────────────────

    fun toggleSelecting() {
        _uiState.update {
            it.copy(
                isSelecting = !it.isSelecting,
                selectedIds = if (it.isSelecting) emptySet() else it.selectedIds,
            )
        }
    }

    fun toggleSessionSelection(id: String) {
        _uiState.update {
            val updated = it.selectedIds.toMutableSet()
            if (updated.contains(id)) updated.remove(id) else updated.add(id)
            it.copy(selectedIds = updated)
        }
    }

    fun selectAll(sessionIds: Set<String> = _uiState.value.sessions.mapTo(linkedSetOf()) { it.id }) {
        _uiState.update {
            it.copy(selectedIds = sessionIds.toSet())
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    // ── Rename ───────────────────────────────────────────────────────────

    fun renameSession(
        sessionId: String,
        newTitle: String,
    ) {
        if (newTitle.isBlank()) {
            _uiState.update { it.copy(renamingSessionId = null, toastMessage = "Title cannot be empty") }
            return
        }
        val api = ApiClient.hermesApi
        val profileId = AuthManager.getSelectedProfileId()
        viewModelScope.launch {
            val result =
                safeApiCall {
                    api.renameSession(
                        sessionId = sessionId,
                        body = SessionRenameRequest(title = newTitle),
                    )
                }
            if (api !== ApiClient.hermesApi || profileId != AuthManager.getSelectedProfileId()) return@launch
            when (result) {
                is NetworkResult.Success -> {
                    fencePendingSessionLoads()
                    _uiState.update {
                        it.copy(
                            renamingSessionId = null,
                            sessions =
                                it.sessions.map { s ->
                                    if (s.id == sessionId) s.copy(title = newTitle) else s
                                },
                            searchTitles = it.searchTitles + (sessionId to newTitle),
                            toastMessage = "Session renamed",
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            renamingSessionId = null,
                            toastMessage = "Rename failed: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    // ── Delete (single) ──────────────────────────────────────────────────

    fun requestDeleteSession(sessionId: String) {
        _uiState.update { it.copy(sessionToDeleteConfirm = sessionId) }
    }

    fun cancelDeleteSession() {
        _uiState.update { it.copy(sessionToDeleteConfirm = null) }
    }

    fun confirmDeleteSession() {
        val sessionId = _uiState.value.sessionToDeleteConfirm ?: return
        val operation = ++mutationOperation
        deleteOperations[sessionId] = operation
        _uiState.update {
            it.copy(
                sessionToDeleteConfirm = null,
                deletingSessionIds = it.deletingSessionIds + sessionId,
            )
        }
        val api = ApiClient.hermesApi
        val profileId = AuthManager.getSelectedProfileId()
        viewModelScope.launch {
            val result =
                safeApiCall {
                    api.deleteSession(sessionId)
                }
            if (deleteOperations[sessionId] != operation) return@launch
            deleteOperations.remove(sessionId)
            if (api !== ApiClient.hermesApi || profileId != AuthManager.getSelectedProfileId()) {
                _uiState.update { it.copy(deletingSessionIds = it.deletingSessionIds - sessionId) }
                return@launch
            }
            when (result) {
                is NetworkResult.Success -> {
                    fencePendingSessionLoads()
                    deletedSearchIds += sessionId
                    val state = _uiState.value
                    val updatedPins =
                        remainingPinsAfterDeleting(
                            pinnedSessionIds = state.pinnedSessionIds,
                            sessions = state.sessions,
                            deletedSessionIds = setOf(sessionId),
                        )
                    if (updatedPins != state.pinnedSessionIds) {
                        pinStore.save(updatedPins)
                    }
                    _uiState.update {
                        it.copy(
                            deletingSessionIds = it.deletingSessionIds - sessionId,
                            sessions = it.sessions.filter { s -> s.id != sessionId },
                            loadedSessionIds = it.loadedSessionIds - sessionId,
                            pinnedSessionIds = updatedPins,
                            searchResults = it.searchResults.filter { it.session_id != sessionId },
                            total = (it.total - 1).coerceAtLeast(0),
                            toastMessage = "Session deleted",
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            deletingSessionIds = it.deletingSessionIds - sessionId,
                            toastMessage = "Delete failed: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    // ── Bulk delete ──────────────────────────────────────────────────────

    fun requestBulkDelete() {
        _uiState.update { it.copy(showBulkDeleteConfirm = true) }
    }

    fun cancelBulkDelete() {
        _uiState.update { it.copy(showBulkDeleteConfirm = false) }
    }

    fun confirmBulkDelete() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        val operation = ++mutationOperation
        bulkDeleteOperation = operation

        _uiState.update { it.copy(showBulkDeleteConfirm = false, isDeletingBulk = true) }
        val api = ApiClient.hermesApi
        val profileId = AuthManager.getSelectedProfileId()
        viewModelScope.launch {
            val result =
                safeApiCall {
                    api.bulkDeleteSessions(
                        body = BulkDeleteRequest(ids = ids),
                    )
                }
            if (bulkDeleteOperation != operation) return@launch
            if (api !== ApiClient.hermesApi || profileId != AuthManager.getSelectedProfileId()) {
                _uiState.update { it.copy(isDeletingBulk = false) }
                return@launch
            }
            when (result) {
                is NetworkResult.Success -> {
                    val deletedCount = result.data.deleted
                    val state = _uiState.value
                    fencePendingSessionLoads()
                    // A count alone cannot identify which rows survived a partial response.
                    val complete = result.data.ok && deletedCount == ids.size
                    val deletedIds = if (complete) ids.toSet() else emptySet()
                    deletedSearchIds += deletedIds
                    val updatedPins =
                        remainingPinsAfterDeleting(
                            pinnedSessionIds = state.pinnedSessionIds,
                            sessions = state.sessions,
                            deletedSessionIds = deletedIds,
                        )
                    if (updatedPins != state.pinnedSessionIds) {
                        pinStore.save(updatedPins)
                    }
                    val toastMsg =
                        when {
                            !result.data.ok -> "Deletion was not confirmed; refreshing sessions"
                            complete -> "$deletedCount session(s) deleted"
                            deletedCount == 0 -> "No sessions were deleted"
                            else -> "Partial deletion reported; refreshing sessions"
                        }
                    _uiState.update {
                        it.copy(
                            isDeletingBulk = false,
                            isSelecting = false,
                            selectedIds = emptySet(),
                            sessions = it.sessions.filterNot { session -> session.id in deletedIds },
                            loadedSessionIds = it.loadedSessionIds - deletedIds,
                            pinnedSessionIds = updatedPins,
                            searchResults =
                                it.searchResults.filterNot { result ->
                                    result.session_id in deletedIds
                                },
                            toastMessage = toastMsg,
                        )
                    }
                    if (!complete && _uiState.value.isSearchMode) setSearchQuery(_uiState.value.searchQuery)
                    loadSessions()
                    loadStats()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isDeletingBulk = false,
                            toastMessage = "Delete failed: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    // ── Prune ────────────────────────────────────────────────────────────

    fun showPruneDialog() {
        _uiState.update { it.copy(showPruneDialog = true) }
    }

    fun hidePruneDialog() {
        _uiState.update { it.copy(showPruneDialog = false) }
    }

    fun pruneSessions(days: Int) {
        if (days < 1) return
        _uiState.update { it.copy(isPruning = true, showPruneDialog = false) }
        viewModelScope.launch {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.pruneSessions(
                        body = PruneRequest(days = days),
                    )
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isPruning = false,
                            toastMessage = "Old sessions pruned",
                        )
                    }
                    loadSessions()
                    loadStats()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isPruning = false,
                            toastMessage = "Prune failed: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    // ── Toast ────────────────────────────────────────────────────────────

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    // Projects metadata is owned by an exact socket generation, never a display label/profile alone.
    private var projectsOwner: SessionLiveConnection? = null
    private var projectsGeneration = 0L
    private var projectsJob: Job? = null
    private var projectsDebounceJob: Job? = null

    fun selectProjectFilter(key: String?) {
        _uiState.update { it.copy(projectFilter = if (it.projectFilter == key) null else key) }
    }

    fun toggleGroupByProject() {
        _uiState.update { it.copy(groupByProject = !it.groupByProject) }
    }

    fun toggleProjectGroup(key: String) {
        _uiState.update {
            it.copy(
                collapsedProjectGroups =
                    if (key in it.collapsedProjectGroups) {
                        it.collapsedProjectGroups - key
                    } else {
                        it.collapsedProjectGroups + key
                    },
            )
        }
    }

    private fun resetProjects(clear: Boolean) {
        projectsGeneration++
        projectsJob?.cancel()
        projectsDebounceJob?.cancel()
        if (clear) projectsOwner = null
        _uiState.update {
            it.copy(
                projects = if (clear) emptyList() else it.projects,
                projectsStatus = ProjectsResult.Offline,
                projectFilter = if (clear) null else it.projectFilter,
                collapsedProjectGroups = if (clear) emptySet() else it.collapsedProjectGroups,
            )
        }
    }

    private fun loadProjects() {
        val owner = activeLiveConnection ?: return
        if (liveTrackingJob?.isActive != true) return
        if (projectsOwner != owner) {
            resetProjects(clear = true)
            projectsOwner = owner
        }
        val generation = ++projectsGeneration
        projectsJob?.cancel()
        projectsJob =
            viewModelScope.launch {
                val result = projectsSource.fetchProjects(owner)
                if (generation != projectsGeneration || owner != activeLiveConnection || owner != projectsOwner) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        projectsStatus = result,
                        projects = (result as? ProjectsResult.Ready)?.projects ?: it.projects,
                    )
                }
            }
    }

    // ── Live session status tracking ─────────────────────────────────────

    fun startLiveStatusTracking() {
        if (liveTrackingJob?.isActive == true) return
        liveTrackingJob =
            viewModelScope.launch {
                launch {
                    liveStatusSource.selectedProfileIds.collect { profileId ->
                        if (selectedLiveProfileId != profileId) {
                            selectedLiveProfileId = profileId
                            resetProjects(clear = true)
                            resetLiveTracking()
                            reconnectLiveTrackingIfPossible()
                        }
                    }
                }
                launch {
                    liveStatusSource.connectionStatus.collect { status ->
                        if (status == ConnectionStatus.CONNECTED) {
                            reconnectLiveTrackingIfPossible()
                        } else {
                            resetLiveTracking()
                        }
                    }
                }
                launch {
                    liveStatusSource.sourcedEvents.collect { sourced ->
                        val connection = activeLiveConnection ?: return@collect
                        if (sourced.profileId != selectedLiveProfileId ||
                            sourced.profileId != connection.profileId ||
                            sourced.connectionGeneration != connection.generation
                        ) {
                            return@collect
                        }
                        if (sourced.event == WsEvent.ProjectsChanged) {
                            projectsDebounceJob?.cancel()
                            projectsDebounceJob =
                                viewModelScope.launch {
                                    delay(250L)
                                    if (connection == activeLiveConnection && connection == projectsOwner) {
                                        loadProjects()
                                    }
                                }
                        }
                        val previousState = liveTrackingState
                        val nextState = SessionLiveStatusReducer.applyWsEvent(previousState, sourced.event)
                        val isLiveStatusEvent =
                            SessionLiveStatusReducer.isLiveStatusEvent(sourced.event) || nextState != previousState
                        if (isLiveStatusEvent) liveEventRevision++
                        liveTrackingState = nextState
                        publishLiveStatuses()
                        if (isLiveStatusEvent && liveRefreshInFlight) liveRefreshPending = true
                    }
                }
                launch {
                    while (true) {
                        delay(LIVE_STATUS_POLL_INTERVAL_MS)
                        requestLiveStatusSnapshot()
                    }
                }
            }
    }

    fun stopLiveStatusTracking() {
        liveTrackingJob?.cancel()
        liveTrackingJob = null
        resetLiveTracking()
    }

    fun refreshLiveStatuses() {
        requestLiveStatusSnapshot()
    }

    private fun reconnectLiveTrackingIfPossible() {
        val profileId = selectedLiveProfileId ?: return
        if (liveStatusSource.connectionStatus.value != ConnectionStatus.CONNECTED) return
        val connection = liveStatusSource.currentConnection(profileId) ?: return
        if (activeLiveConnection != connection) {
            resetLiveTracking()
            activeLiveConnection = connection
            loadProjects()
        }
        requestLiveStatusSnapshot()
    }

    private fun resetLiveTracking() {
        resetProjects(clear = false)
        liveTrackingGeneration++
        liveEventRevision++
        liveSnapshotJob?.cancel()
        liveSnapshotJob = null
        activeLiveConnection = null
        liveRefreshInFlight = false
        liveRefreshPending = false
        hasAuthoritativeLiveSnapshot = false
        liveTrackingState = SessionLiveStatusReducer.clear()
        publishLiveStatuses()
    }

    private fun requestLiveStatusSnapshot() {
        val connection = activeLiveConnection ?: return
        if (liveTrackingJob?.isActive != true ||
            liveStatusSource.connectionStatus.value != ConnectionStatus.CONNECTED
        ) {
            return
        }
        if (liveRefreshInFlight) {
            liveRefreshPending = true
            return
        }
        liveRefreshInFlight = true
        val generation = liveTrackingGeneration
        val eventRevision = liveEventRevision
        liveSnapshotJob =
            viewModelScope.launch {
                try {
                    val snapshot = liveStatusSource.fetchActiveSessionsSnapshot(connection)
                    if (snapshot != null &&
                        generation == liveTrackingGeneration &&
                        eventRevision == liveEventRevision &&
                        connection == activeLiveConnection &&
                        liveStatusSource.connectionStatus.value == ConnectionStatus.CONNECTED
                    ) {
                        liveTrackingState = SessionLiveStatusReducer.applySnapshot(liveTrackingState, snapshot)
                        hasAuthoritativeLiveSnapshot = true
                        publishLiveStatuses()
                    }
                } finally {
                    if (generation == liveTrackingGeneration && connection == activeLiveConnection) {
                        liveSnapshotJob = null
                        liveRefreshInFlight = false
                        if (liveRefreshPending) {
                            liveRefreshPending = false
                            requestLiveStatusSnapshot()
                        }
                    }
                }
            }
    }

    private fun publishLiveStatuses() {
        _uiState.update {
            it.copy(
                liveStatuses = liveTrackingState.liveStatuses,
                liveStatusesAuthoritative = hasAuthoritativeLiveSnapshot,
            )
        }
    }

    override fun onCleared() {
        stopLiveStatusTracking()
        super.onCleared()
    }
}
