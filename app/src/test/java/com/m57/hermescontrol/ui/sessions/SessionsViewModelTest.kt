package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionListResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {
    @Test
    fun `manual History refresh refetches projects on captured owner`() {
        val live = mockk<com.m57.hermescontrol.data.ws.SessionLiveStatusSource>()
        val owner = com.m57.hermescontrol.data.ws.SessionLiveConnection("a", 1)
        every { live.selectedProfileIds } returns kotlinx.coroutines.flow.MutableStateFlow("a")
        every { live.connectionStatus } returns
            kotlinx.coroutines.flow.MutableStateFlow(com.m57.hermescontrol.data.ws.ConnectionStatus.CONNECTED)
        every { live.sourcedEvents } returns kotlinx.coroutines.flow.emptyFlow()
        every { live.currentConnection("a") } returns owner
        coEvery { live.fetchActiveSessionsSnapshot(owner) } returns null
        val projects = mockk<com.m57.hermescontrol.data.ws.ProjectsSource>()
        coEvery {
            projects.fetchProjects(
                owner,
            )
        } returns com.m57.hermescontrol.data.ws.ProjectsResult.Ready(emptyList())
        coEvery { mockApi.getSessions(any(), any(), any(), any(), any()) } returns
            Response.success(SessionListResponse(emptyList()))
        val vm = SessionsViewModel(ioDispatcher = testDispatcher, liveStatusSource = live, projectsSource = projects)
        vm.startLiveStatusTracking()
        testDispatcher.scheduler.runCurrent()
        coVerify(exactly = 1) { projects.fetchProjects(owner) }
        vm.loadSessions()
        testDispatcher.scheduler.runCurrent()
        coVerify(exactly = 2) { projects.fetchProjects(owner) }
        vm.stopLiveStatusTracking()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `project controls are view model state and preserve paging metadata`() {
        val vm = createViewModel()
        vm.selectProjectFilter("project:a")
        vm.toggleProjectGroup("project:a")
        assertEquals("project:a", vm.uiState.value.projectFilter)
        assertEquals(setOf("project:a"), vm.uiState.value.collapsedProjectGroups)
        val before = vm.uiState.value.serverOffset
        vm.toggleGroupByProject()
        assertEquals(before, vm.uiState.value.serverOffset)
        vm.selectProjectFilter("project:a")
        assertEquals(null, vm.uiState.value.projectFilter)
    }

    private val testDispatcher = StandardTestDispatcher()
    private val mockApi = mockk<HermesApiService>(relaxed = true)

    private fun createViewModel(): SessionsViewModel {
        val vm = SessionsViewModel(ioDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(AuthManager)
        mockkObject(ApiClient)
        every { AuthManager.getSelectedProfileId() } returns null
        every { AuthManager.getPinnedSessionIds(AuthManager.DEFAULT_PROFILE_ID) } returns emptyList()
        every { ApiClient.hermesApi } returns mockApi
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `blank query resets search mode`() {
        val vm = createViewModel()
        vm.setSearchQuery("something")
        vm.setSearchQuery("")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("", vm.uiState.value.searchQuery)
        assertFalse(vm.uiState.value.isSearchMode)
        assertEquals(0, vm.uiState.value.searchResults.size)
        assertFalse(vm.uiState.value.isSearching)
    }

    @Test
    fun `non-blank query enters search mode and resolves`() {
        val vm = createViewModel()
        vm.setSearchQuery("hello")
        // state is set synchronously
        assertEquals("hello", vm.uiState.value.searchQuery)
        assertTrue(vm.uiState.value.isSearchMode)
        // advance past debounce + (failing, offline) network call
        testDispatcher.scheduler.advanceTimeBy(500)
        testDispatcher.scheduler.advanceUntilIdle()
        // Either way the spinner must stop and the query persists.
        assertFalse(vm.uiState.value.isSearching)
        assertEquals("hello", vm.uiState.value.searchQuery)
    }

    @Test
    fun `select all uses the IDs shown in the current view`() {
        val vm = createViewModel()

        vm.selectAll(setOf("search-session-1", "search-session-2"))

        assertEquals(
            setOf("search-session-1", "search-session-2"),
            vm.uiState.value.selectedIds,
        )
    }

    @Test
    fun `clean search snippet extracts text from JSON payload`() {
        assertEquals(
            "Find the deployment logs",
            cleanSearchSnippet("{\"role\":\"user\",\"content\":\">>>Find<<< the deployment logs\"}"),
        )
    }

    @Test
    fun `load more dedupes churn without advancing by hydrated pins`() {
        val pinStore = FakeSessionPinStore(listOf("lineage-pinned"))
        val vm = SessionsViewModel(pinStore = pinStore, ioDispatcher = testDispatcher)
        coEvery { mockApi.getSessions(50, 0, "recent", null, "cron") } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("recent-1"), SessionInfo("recent-2")),
                    total = 3,
                ),
            )
        coEvery { mockApi.getSessionLatestDescendant("lineage-pinned") } returns
            Response.success(com.m57.hermescontrol.data.model.SessionLatestDescendantResponse("pinned-child"))
        coEvery { mockApi.getSession("pinned-child") } returns Response.success(SessionInfo("pinned-child"))
        coEvery { mockApi.getSessions(50, 2, "recent", null, "cron") } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("recent-2"), SessionInfo("recent-3")),
                    total = 3,
                ),
            )

        vm.loadSessions()
        awaitState { !vm.uiState.value.isLoading && vm.uiState.value.loadedSessionIds.size == 2 }

        vm.loadMore()
        awaitState { !vm.uiState.value.isLoadingMore && vm.uiState.value.loadedSessionIds.size == 3 }

        coVerify { mockApi.getSessions(50, 2, "recent", null, "cron") }
        assertEquals(
            setOf("pinned-child", "recent-1", "recent-2", "recent-3"),
            vm.uiState.value.sessions.map { it.id }.toSet(),
        )
        assertEquals(4, vm.uiState.value.sessions.distinctBy { it.id }.size)
        assertFalse(vm.uiState.value.hasMore)
    }

    @Test
    fun `load more advances server offset across duplicate-only page`() {
        val vm =
            SessionsViewModel(
                pinStore = FakeSessionPinStore(emptyList()),
                ioDispatcher = testDispatcher,
            )
        coEvery { mockApi.getSessions(50, 0, "recent", null, "cron") } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("recent-1"), SessionInfo("recent-2")),
                    total = 5,
                ),
            )
        coEvery { mockApi.getSessions(50, 2, "recent", null, "cron") } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("recent-1"), SessionInfo("recent-2")),
                    total = 5,
                ),
            )
        coEvery { mockApi.getSessions(50, 4, "recent", null, "cron") } returns
            Response.success(SessionListResponse(sessions = emptyList(), total = 4))

        vm.loadSessions()
        awaitState { !vm.uiState.value.isLoading }
        vm.loadMore()
        awaitState { !vm.uiState.value.isLoadingMore && vm.uiState.value.serverOffset == 4 }
        vm.loadMore()
        awaitState { !vm.uiState.value.isLoadingMore && !vm.uiState.value.hasMore }

        coVerify { mockApi.getSessions(50, 2, "recent", null, "cron") }
        coVerify { mockApi.getSessions(50, 4, "recent", null, "cron") }
    }

    @Test
    fun `refresh cancels stale load more before it can mutate refreshed state`() {
        val vm =
            SessionsViewModel(
                pinStore = FakeSessionPinStore(emptyList()),
                ioDispatcher = testDispatcher,
            )
        val stalePage = CompletableDeferred<Response<SessionListResponse>>()
        coEvery { mockApi.getSessions(50, 0, "recent", null, "cron") } returnsMany
            listOf(
                Response.success(
                    SessionListResponse(
                        sessions = listOf(SessionInfo("old-1"), SessionInfo("old-2")),
                        total = 4,
                    ),
                ),
                Response.success(
                    SessionListResponse(
                        sessions = listOf(SessionInfo("fresh-1"), SessionInfo("fresh-2")),
                        total = 2,
                    ),
                ),
            )
        coEvery { mockApi.getSessions(50, 2, "recent", null, "cron") } coAnswers { stalePage.await() }

        vm.loadSessions()
        awaitState { vm.uiState.value.sessions.map { it.id } == listOf("old-1", "old-2") }
        vm.loadMore()
        testDispatcher.scheduler.runCurrent()
        vm.loadSessions()
        awaitState { vm.uiState.value.sessions.map { it.id } == listOf("fresh-1", "fresh-2") }
        stalePage.complete(
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("stale-3"), SessionInfo("stale-4")),
                    total = 4,
                ),
            ),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("fresh-1", "fresh-2"), vm.uiState.value.sessions.map { it.id })
        assertEquals(2, vm.uiState.value.serverOffset)
        assertFalse(vm.uiState.value.isLoadingMore)
    }

    // ── History sections (conversations vs. automations) ─────────────────

    @Test
    fun `global statistics are only shown for conversations`() {
        assertTrue(HistorySection.CONVERSATIONS.showsGlobalStats)
        assertFalse(HistorySection.AUTOMATIONS.showsGlobalStats)
    }

    @Test
    fun `automation pagination advances on server rows not merged pin rows`() {
        val vm =
            SessionsViewModel(
                pinStore = FakeSessionPinStore(listOf("lineage-pinned")),
                ioDispatcher = testDispatcher,
            )
        val firstPage =
            listOf(
                SessionInfo("cron_job-a_20260905_180000", source = "cron"),
                SessionInfo("cron_job-a_20260905_180500", source = "cron"),
            )
        coEvery { mockApi.getSessions(50, 0, "recent", "cron", null) } returns
            Response.success(SessionListResponse(sessions = firstPage, total = 3))
        coEvery { mockApi.getSessions(50, 2, "recent", "cron", null) } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("cron_job-a_20260905_190000", source = "cron")),
                    total = 3,
                ),
            )
        coEvery { mockApi.getSessionLatestDescendant("lineage-pinned") } returns
            Response.success(
                com.m57.hermescontrol.data.model.SessionLatestDescendantResponse("cron_job-b_20260905_120000"),
            )
        coEvery { mockApi.getSession("cron_job-b_20260905_120000") } returns
            Response.success(SessionInfo("cron_job-b_20260905_120000", source = "cron"))

        vm.selectSection(HistorySection.AUTOMATIONS)
        awaitState { !vm.uiState.value.isLoading && vm.uiState.value.sessions.size == 3 }

        // Three rows are displayed, but only two came from the server window.
        assertEquals(2, vm.uiState.value.serverOffset)
        assertTrue(vm.uiState.value.hasMore)

        vm.loadMore()
        awaitState { !vm.uiState.value.isLoadingMore && vm.uiState.value.serverOffset == 3 }

        coVerify { mockApi.getSessions(50, 2, "recent", "cron", null) }
        assertFalse(vm.uiState.value.hasMore)
        assertEquals(
            listOf("job-a", "job-b"),
            automationGroups(vm.uiState.value.sessions).map { it.jobId }.sortedBy { it },
        )
    }

    @Test
    fun `automation pagination honors server offset and limit`() {
        val vm = createViewModel()
        coEvery { mockApi.getSessions(50, 0, "recent", "cron", null) } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("cron_job_20260905_100000", source = "cron")),
                    total = 75,
                    limit = 25,
                    offset = 10,
                ),
            )
        coEvery { mockApi.getSessions(50, 35, "recent", "cron", null) } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("cron_job_20260905_110000", source = "cron")),
                    total = 36,
                    limit = 1,
                    offset = 35,
                ),
            )

        vm.selectSection(HistorySection.AUTOMATIONS)
        awaitState { !vm.uiState.value.isLoading }
        assertEquals(35, vm.uiState.value.serverOffset)

        vm.loadMore()
        awaitState { !vm.uiState.value.isLoadingMore }

        coVerify { mockApi.getSessions(50, 35, "recent", "cron", null) }
        assertEquals(36, vm.uiState.value.serverOffset)
        assertEquals(36, vm.uiState.value.total)
        assertFalse(vm.uiState.value.hasMore)
    }

    @Test
    fun `pins hydrate into their own section only`() {
        val vm =
            SessionsViewModel(
                pinStore = FakeSessionPinStore(listOf("chat-pin", "cron-pin")),
                ioDispatcher = testDispatcher,
            )
        coEvery { mockApi.getSessions(50, 0, "recent", null, "cron") } returns
            Response.success(SessionListResponse(sessions = listOf(SessionInfo("chat-1")), total = 1))
        coEvery { mockApi.getSessions(50, 0, "recent", "cron", null) } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("cron_job_20260905_100000", source = "cron")),
                    total = 1,
                ),
            )
        coEvery { mockApi.getSessionLatestDescendant("chat-pin") } returns
            Response.success(com.m57.hermescontrol.data.model.SessionLatestDescendantResponse("chat-pin"))
        coEvery { mockApi.getSession("chat-pin") } returns Response.success(SessionInfo("chat-pin"))
        coEvery { mockApi.getSessionLatestDescendant("cron-pin") } returns
            Response.success(
                com.m57.hermescontrol.data.model.SessionLatestDescendantResponse("cron_job_20260904_100000"),
            )
        coEvery { mockApi.getSession("cron_job_20260904_100000") } returns
            Response.success(SessionInfo("cron_job_20260904_100000", source = "cron"))

        vm.loadSessions()
        awaitState { vm.uiState.value.sessions.size == 2 }
        assertEquals(
            setOf("chat-pin", "chat-1"),
            vm.uiState.value.sessions.map { it.id }.toSet(),
        )

        vm.selectSection(HistorySection.AUTOMATIONS)
        awaitState { vm.uiState.value.sessions.size == 2 }
        assertEquals(
            setOf("cron_job_20260904_100000", "cron_job_20260905_100000"),
            vm.uiState.value.sessions.map { it.id }.toSet(),
        )
        assertEquals(listOf("chat-pin", "cron-pin"), vm.uiState.value.pinnedSessionIds)
    }

    @Test
    fun `section switch rescopes the active search query`() {
        val vm = createViewModel()
        coEvery { mockApi.searchSessions("deploy", null, null, "cron") } returns
            Response.success(
                com.m57.hermescontrol.data.model.SessionSearchResponse(
                    listOf(com.m57.hermescontrol.data.model.SessionSearchResult(session_id = "chat-hit")),
                ),
            )
        coEvery { mockApi.searchSessions("deploy", null, "cron", null) } returns
            Response.success(
                com.m57.hermescontrol.data.model.SessionSearchResponse(
                    listOf(
                        com.m57.hermescontrol.data.model.SessionSearchResult(
                            session_id = "cron-hit",
                            source = "cron",
                        ),
                    ),
                ),
            )

        vm.setSearchQuery("deploy")
        awaitState { vm.uiState.value.searchResults.size == 1 }
        assertEquals("chat-hit", vm.uiState.value.searchResults.single().session_id)
        vm.selectAll(setOf("chat-hit"))

        vm.selectSection(HistorySection.AUTOMATIONS)
        assertEquals("deploy", vm.uiState.value.searchQuery)
        assertTrue(vm.uiState.value.searchResults.isEmpty())
        assertTrue(vm.uiState.value.selectedIds.isEmpty())
        awaitState { vm.uiState.value.searchResults.size == 1 }

        assertEquals("cron-hit", vm.uiState.value.searchResults.single().session_id)
        coVerify(exactly = 1) { mockApi.searchSessions("deploy", null, null, "cron") }
        coVerify(exactly = 1) { mockApi.searchSessions("deploy", null, "cron", null) }
    }

    @Test
    fun `late conversation load cannot replace automation rows`() {
        val vm = createViewModel()
        val stalePage = CompletableDeferred<Response<SessionListResponse>>()
        coEvery { mockApi.getSessions(50, 0, "recent", null, "cron") } coAnswers { stalePage.await() }
        coEvery { mockApi.getSessions(50, 0, "recent", "cron", null) } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("cron_job_20260905_100000", source = "cron")),
                    total = 1,
                ),
            )

        vm.loadSessions()
        testDispatcher.scheduler.runCurrent()
        vm.selectSection(HistorySection.AUTOMATIONS)
        awaitState { vm.uiState.value.sessions.size == 1 }
        stalePage.complete(
            Response.success(SessionListResponse(sessions = listOf(SessionInfo("old-chat")), total = 1)),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(HistorySection.AUTOMATIONS, vm.uiState.value.section)
        assertEquals(listOf("cron_job_20260905_100000"), vm.uiState.value.sessions.map { it.id })
    }

    @Test
    fun `deleting a pinned automation run drops its row and pin`() {
        val pinStore = FakeSessionPinStore(listOf("cron_job_20260905_100000"))
        val vm = SessionsViewModel(pinStore = pinStore, ioDispatcher = testDispatcher)
        coEvery { mockApi.getSessions(50, 0, "recent", "cron", null) } returns
            Response.success(
                SessionListResponse(
                    sessions =
                        listOf(
                            SessionInfo("cron_job_20260905_100000", source = "cron"),
                            SessionInfo("cron_job_20260905_110000", source = "cron"),
                        ),
                    total = 2,
                ),
            )
        coEvery { mockApi.deleteSession("cron_job_20260905_100000") } returns Response.success(Unit)

        vm.selectSection(HistorySection.AUTOMATIONS)
        awaitState { vm.uiState.value.sessions.size == 2 }

        vm.requestDeleteSession("cron_job_20260905_100000")
        assertEquals("cron_job_20260905_100000", vm.uiState.value.sessionToDeleteConfirm)
        vm.confirmDeleteSession()
        awaitState { vm.uiState.value.sessions.size == 1 }

        assertEquals(listOf("cron_job_20260905_110000"), vm.uiState.value.sessions.map { it.id })
        assertEquals(emptyList<String>(), vm.uiState.value.pinnedSessionIds)
        assertEquals(emptyList<String>(), pinStore.load())
        assertEquals(1, vm.uiState.value.total)
    }

    @Test
    fun `pins are scoped to the profile that owns the view model`() {
        every { AuthManager.getPinnedSessionIds("profile-a") } returns listOf("pin-a")
        every { AuthManager.getPinnedSessionIds("profile-b") } returns listOf("pin-b")
        every { AuthManager.savePinnedSessionIds(any(), any()) } returns Unit

        val vmA = SessionsViewModel(AuthManagerSessionPinStore("profile-a"), testDispatcher)
        val vmB = SessionsViewModel(AuthManagerSessionPinStore("profile-b"), testDispatcher)

        assertEquals(listOf("pin-a"), vmA.uiState.value.pinnedSessionIds)
        assertEquals(listOf("pin-b"), vmB.uiState.value.pinnedSessionIds)

        vmB.toggleSessionPin(SessionInfo("cron_job_20260905_100000", source = "cron"))

        verify {
            AuthManager.savePinnedSessionIds(
                listOf("pin-b", "cron_job_20260905_100000"),
                "profile-b",
            )
        }
        verify(exactly = 0) { AuthManager.savePinnedSessionIds(any(), "profile-a") }
    }

    private fun awaitState(predicate: () -> Boolean) {
        repeat(250) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (predicate()) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for ViewModel state")
    }

    private class FakeSessionPinStore(
        private var pins: List<String>,
    ) : SessionPinStore {
        override fun load(): List<String> = pins

        override fun save(pinIds: List<String>) {
            pins = pinIds
        }
    }
}
