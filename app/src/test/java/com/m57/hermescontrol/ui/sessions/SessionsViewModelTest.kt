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
    private val testDispatcher = StandardTestDispatcher()
    private val mockApi = mockk<HermesApiService>(relaxed = true)

    private fun createViewModel(): SessionsViewModel {
        val vm = SessionsViewModel()
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
        val vm = SessionsViewModel(pinStore)
        coEvery { mockApi.getSessions(50, 0, "recent") } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("recent-1"), SessionInfo("recent-2")),
                    total = 3,
                ),
            )
        coEvery { mockApi.getSessionLatestDescendant("lineage-pinned") } returns
            Response.success(com.m57.hermescontrol.data.model.SessionLatestDescendantResponse("pinned-child"))
        coEvery { mockApi.getSession("pinned-child") } returns Response.success(SessionInfo("pinned-child"))
        coEvery { mockApi.getSessions(50, 2, "recent") } returns
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

        coVerify { mockApi.getSessions(50, 2, "recent") }
        assertEquals(
            setOf("pinned-child", "recent-1", "recent-2", "recent-3"),
            vm.uiState.value.sessions.map { it.id }.toSet(),
        )
        assertEquals(4, vm.uiState.value.sessions.distinctBy { it.id }.size)
        assertFalse(vm.uiState.value.hasMore)
    }

    @Test
    fun `load more advances server offset across duplicate-only page`() {
        val vm = SessionsViewModel(FakeSessionPinStore(emptyList()))
        coEvery { mockApi.getSessions(50, 0, "recent") } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("recent-1"), SessionInfo("recent-2")),
                    total = 5,
                ),
            )
        coEvery { mockApi.getSessions(50, 2, "recent") } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("recent-1"), SessionInfo("recent-2")),
                    total = 5,
                ),
            )
        coEvery { mockApi.getSessions(50, 4, "recent") } returns
            Response.success(SessionListResponse(sessions = emptyList(), total = 4))

        vm.loadSessions()
        awaitState { !vm.uiState.value.isLoading }
        vm.loadMore()
        awaitState { !vm.uiState.value.isLoadingMore && vm.uiState.value.serverOffset == 4 }
        vm.loadMore()
        awaitState { !vm.uiState.value.isLoadingMore && !vm.uiState.value.hasMore }

        coVerify { mockApi.getSessions(50, 2, "recent") }
        coVerify { mockApi.getSessions(50, 4, "recent") }
    }

    @Test
    fun `refresh cancels stale load more before it can mutate refreshed state`() {
        val vm = SessionsViewModel(FakeSessionPinStore(emptyList()))
        val stalePage = CompletableDeferred<Response<SessionListResponse>>()
        coEvery { mockApi.getSessions(50, 0, "recent") } returnsMany
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
        coEvery { mockApi.getSessions(50, 2, "recent") } coAnswers { stalePage.await() }

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
