package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.BulkDeleteResponse
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionLatestDescendantResponse
import com.m57.hermescontrol.data.model.SessionListResponse
import com.m57.hermescontrol.data.model.SessionSearchResponse
import com.m57.hermescontrol.data.model.SessionSearchResult
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class SessionSearchCorrectnessTest {
    @Test
    fun `server search ignores retained project filter and collapse`() {
        coEvery { api.searchSessions(any(), any(), any(), any()) } returns hits("outside")
        val vm = viewModel()
        vm.selectProjectFilter("project:only")
        vm.toggleProjectGroup("project:only")
        vm.setSearchQuery("needle")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("outside"), vm.uiState.value.searchResults.map { it.session_id })
        assertTrue(vm.uiState.value.availableProjects.isEmpty())
    }

    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<HermesApiService>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(AuthManager, ApiClient)
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
        every { ApiClient.hermesApi } returns api
        coEvery { api.getSessions(any(), any(), any(), any(), any()) } returns
            Response.success(SessionListResponse(emptyList()))
        coEvery { api.getSessionStats() } returns
            Response.success(com.m57.hermescontrol.data.model.SessionStatsResponse())
        coEvery { api.getSessionLatestDescendant(any()) } returns Response.error(404, "missing".toResponseBody())
        coEvery { api.getSession(any()) } returns Response.error(404, "missing".toResponseBody())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel() = SessionsViewModel(pinStore = SearchReviewPinStore(), ioDispatcher = dispatcher)

    private class SearchReviewPinStore(private var pins: List<String> = emptyList()) : SessionPinStore {
        override fun load() = pins

        override fun save(pinIds: List<String>) {
            pins = pinIds
        }
    }

    private fun hits(vararg ids: String) = Response.success(SessionSearchResponse(ids.map { SessionSearchResult(it) }))

    @Test
    fun `partial zero and unconfirmed bulk responses do not tombstone surviving hits or pins`() {
        for (response in listOf(
            BulkDeleteResponse(ok = true, deleted = 1),
            BulkDeleteResponse(ok = true, deleted = 0),
            BulkDeleteResponse(ok = false, deleted = 2),
        )) {
            coEvery { api.searchSessions(any(), any(), any(), any()) } returns hits("a", "b")
            coEvery { api.bulkDeleteSessions(any()) } returns Response.success(response)
            val pins = SearchReviewPinStore(listOf("a", "b"))
            val vm = SessionsViewModel(pinStore = pins, ioDispatcher = dispatcher)
            vm.setSearchQuery("a")
            dispatcher.scheduler.advanceUntilIdle()
            vm.selectAll(setOf("a", "b"))
            vm.confirmBulkDelete()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf("a", "b"), vm.uiState.value.searchResults.map { it.session_id })
            assertEquals(listOf("a", "b"), pins.load())
            assertFalse(vm.uiState.value.isDeletingBulk)
            vm.setSearchQuery("again")
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf("a", "b"), vm.uiState.value.searchResults.map { it.session_id })
        }
    }

    @Test
    fun `stale single cleanup releases its own busy state but never a successor`() {
        lateinit var first: Continuation<Response<Unit>>
        lateinit var second: Continuation<Response<Unit>>
        var calls = 0
        coEvery { api.deleteSession("a") } coAnswers {
            suspendCoroutine { if (calls++ == 0) first = it else second = it }
        }
        val vm = viewModel()
        vm.requestDeleteSession("a")
        vm.confirmDeleteSession()
        dispatcher.scheduler.advanceUntilIdle()
        every { AuthManager.getSelectedProfileId() } returns "profile-b"
        vm.requestDeleteSession("a")
        vm.confirmDeleteSession()
        dispatcher.scheduler.advanceUntilIdle()
        first.resume(Response.success(Unit))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(setOf("a"), vm.uiState.value.deletingSessionIds)
        every { AuthManager.getSelectedProfileId() } returns "profile-c"
        second.resume(Response.success(Unit))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.deletingSessionIds.isEmpty())
        assertNull(vm.uiState.value.toastMessage)
    }

    @Test
    fun `stale bulk cleanup releases its own busy state but never a successor`() {
        lateinit var first: Continuation<Response<BulkDeleteResponse>>
        lateinit var second: Continuation<Response<BulkDeleteResponse>>
        var calls = 0
        coEvery { api.bulkDeleteSessions(any()) } coAnswers {
            suspendCoroutine { if (calls++ == 0) first = it else second = it }
        }
        val vm = viewModel()
        vm.selectAll(setOf("a"))
        vm.confirmBulkDelete()
        dispatcher.scheduler.advanceUntilIdle()
        every { ApiClient.hermesApi } returns mockk(relaxed = true)
        every { AuthManager.getSelectedProfileId() } returns "profile-b"
        every { ApiClient.hermesApi } returns api
        vm.confirmBulkDelete()
        dispatcher.scheduler.advanceUntilIdle()
        first.resume(Response.success(BulkDeleteResponse(ok = true, deleted = 1)))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.isDeletingBulk)
        every { ApiClient.hermesApi } returns mockk(relaxed = true)
        second.resume(Response.success(BulkDeleteResponse(ok = true, deleted = 1)))
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.isDeletingBulk)
        assertEquals(setOf("a"), vm.uiState.value.selectedIds)
        assertNull(vm.uiState.value.toastMessage)
        every { ApiClient.hermesApi } returns api
        every { AuthManager.getSelectedProfileId() } returns "profile-a"
    }

    @Test
    fun `cancel resistant history page cannot undo a rename or resurrect a deletion`() {
        for (delete in listOf(false, true)) {
            lateinit var pending: Continuation<Response<SessionListResponse>>
            coEvery { api.getSessions(any(), any(), any(), any(), any()) } coAnswers {
                suspendCoroutine { pending = it }
            }
            coEvery { api.renameSession(any(), any()) } returns Response.success(Unit)
            coEvery { api.deleteSession(any()) } returns Response.success(Unit)
            val vm = viewModel()
            vm.loadSessions()
            dispatcher.scheduler.advanceUntilIdle()
            if (delete) {
                vm.requestDeleteSession("a")
                vm.confirmDeleteSession()
            } else {
                vm.renameSession("a", "New")
            }
            dispatcher.scheduler.advanceUntilIdle()
            pending.resume(
                Response.success(
                    SessionListResponse(
                        sessions = listOf(SessionInfo(id = "a", title = "Old")),
                        total = 1,
                    ),
                ),
            )
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(vm.uiState.value.sessions.isEmpty())
            assertFalse(vm.uiState.value.isLoading)
            if (!delete) assertEquals("New", vm.uiState.value.searchTitles["a"])
        }
    }

    @Test
    fun `partial bulk reconciliation fences an older search response`() {
        lateinit var stale: Continuation<Response<SessionSearchResponse>>
        var calls = 0
        coEvery { api.searchSessions(any(), any(), any(), any()) } coAnswers {
            if (calls++ == 0) suspendCoroutine { stale = it } else hits("survivor")
        }
        coEvery { api.bulkDeleteSessions(any()) } returns Response.success(BulkDeleteResponse(ok = true, deleted = 1))
        val vm = viewModel()
        vm.setSearchQuery("a")
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectAll(setOf("gone", "survivor"))
        vm.confirmBulkDelete()
        dispatcher.scheduler.advanceUntilIdle()
        stale.resume(hits("gone", "survivor"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("survivor"), vm.uiState.value.searchResults.map { it.session_id })
        assertFalse(vm.uiState.value.isSearching)
    }

    @Test
    fun `mutation fences pending next page and pinned hydration`() {
        for (hydrate in listOf(false, true)) {
            coEvery { api.getSessions(any(), 0, any(), any(), any()) } returns
                Response.success(SessionListResponse(listOf(SessionInfo("base")), total = 2))
            lateinit var page: Continuation<Response<SessionListResponse>>
            lateinit var pin: Continuation<Response<SessionInfo>>
            coEvery { api.getSessions(any(), 1, any(), any(), any()) } coAnswers {
                suspendCoroutine { page = it }
            }
            coEvery { api.getSessionLatestDescendant("a") } returns
                Response.success(SessionLatestDescendantResponse("a"))
            coEvery { api.getSession("a") } coAnswers { suspendCoroutine { pin = it } }
            coEvery { api.deleteSession("a") } returns Response.success(Unit)
            val vm =
                SessionsViewModel(
                    pinStore = SearchReviewPinStore(if (hydrate) listOf("a") else emptyList()),
                    ioDispatcher = dispatcher,
                )
            vm.loadSessions()
            dispatcher.scheduler.advanceUntilIdle()
            if (!hydrate) {
                vm.loadMore()
                dispatcher.scheduler.advanceUntilIdle()
            }
            vm.requestDeleteSession("a")
            vm.confirmDeleteSession()
            dispatcher.scheduler.advanceUntilIdle()
            if (hydrate) {
                pin.resume(Response.success(SessionInfo("a")))
            } else {
                page.resume(Response.success(SessionListResponse(listOf(SessionInfo("a")), total = 2)))
            }
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf("base"), vm.uiState.value.sessions.map { it.id })
            assertFalse(vm.uiState.value.isLoadingMore)
        }
    }

    @Test
    fun `dedupe keeps the first surfaced ID in server order`() {
        coEvery { api.searchSessions(any(), any(), any(), any()) } returns hits("a", "b", "a", "b", "c")
        val vm = viewModel()
        vm.setSearchQuery("deploy")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), vm.uiState.value.searchResults.map { it.session_id })
        vm.setSearchQuery("next")
        assertTrue(vm.uiState.value.searchResults.isEmpty())
        assertTrue(vm.uiState.value.isSearching)
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `new query immediately clears previous failure before debounce`() {
        coEvery { api.searchSessions(any(), any(), any(), any()) } returns
            Response.error(400, "bad query".toResponseBody())
        val vm = viewModel()
        vm.setSearchQuery("bad")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.searchError != null)
        vm.toggleSessionSelection("old")
        vm.setSearchQuery("new")
        assertNull(vm.uiState.value.searchError)
        assertTrue(vm.uiState.value.isSearching)
        assertTrue(vm.uiState.value.selectedIds.isEmpty())
        vm.setSearchQuery("")
        assertFalse(vm.uiState.value.isSearching)
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `cancel resistant A response cannot overwrite second A query`() {
        for (failure in listOf(false, true)) {
            lateinit var stale: Continuation<Response<SessionSearchResponse>>
            var calls = 0
            coEvery { api.searchSessions("a", null, null, "cron") } coAnswers {
                if (calls++ == 0) suspendCoroutine { stale = it } else hits("fresh")
            }
            val vm = viewModel()
            vm.setSearchQuery("a")
            dispatcher.scheduler.advanceUntilIdle()
            vm.setSearchQuery("b")
            vm.setSearchQuery("a")
            dispatcher.scheduler.advanceUntilIdle()
            stale.resume(if (failure) Response.error(400, "stale".toResponseBody()) else hits("stale"))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf("fresh"), vm.uiState.value.searchResults.map { it.session_id })
            assertNull(vm.uiState.value.searchError)
            assertFalse(vm.uiState.value.isSearching)
        }
    }

    @Test
    fun `response from changed service or profile is discarded`() {
        for (changeService in listOf(false, true)) {
            every { ApiClient.hermesApi } returns api
            every { AuthManager.getSelectedProfileId() } returns "profile-a"
            lateinit var stale: Continuation<Response<SessionSearchResponse>>
            coEvery { api.searchSessions(any(), any(), any(), any()) } coAnswers {
                suspendCoroutine { stale = it }
            }
            val vm = viewModel()
            vm.setSearchQuery("a")
            dispatcher.scheduler.advanceUntilIdle()
            if (changeService) {
                every { ApiClient.hermesApi } returns mockk(relaxed = true)
            } else {
                every { AuthManager.getSelectedProfileId() } returns "profile-b"
            }
            stale.resume(hits("wrong-profile"))
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(vm.uiState.value.searchResults.isEmpty())
            assertNull(vm.uiState.value.searchError)
            assertFalse(vm.uiState.value.isSearching)
        }
    }

    @Test
    fun `section switch fences cancel resistant old section response`() {
        lateinit var stale: Continuation<Response<SessionSearchResponse>>
        coEvery { api.searchSessions("a", null, null, "cron") } coAnswers { suspendCoroutine { stale = it } }
        coEvery { api.searchSessions("a", null, "cron", null) } returns hits("automation")
        val vm = viewModel()
        vm.setSearchQuery("a")
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectSection(HistorySection.AUTOMATIONS)
        dispatcher.scheduler.advanceUntilIdle()
        stale.resume(hits("conversation"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("automation"), vm.uiState.value.searchResults.map { it.session_id })
    }

    @Test
    fun `history refresh does not orphan a pending search`() {
        lateinit var pending: Continuation<Response<SessionSearchResponse>>
        coEvery { api.searchSessions(any(), any(), any(), any()) } coAnswers { suspendCoroutine { pending = it } }
        val vm = viewModel()
        vm.setSearchQuery("a")
        dispatcher.scheduler.advanceUntilIdle()
        vm.loadSessions()
        dispatcher.scheduler.advanceUntilIdle()
        pending.resume(hits("found"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("found"), vm.uiState.value.searchResults.map { it.session_id })
        assertFalse(vm.uiState.value.isSearching)
    }

    @Test
    fun `single and bulk delete remove already displayed hits`() {
        coEvery { api.searchSessions(any(), any(), any(), any()) } returns hits("a", "b", "c")
        coEvery { api.deleteSession("b") } returns Response.success(Unit)
        coEvery { api.bulkDeleteSessions(any()) } returns Response.success(BulkDeleteResponse(ok = true, deleted = 1))
        val vm = viewModel()
        vm.setSearchQuery("a")
        dispatcher.scheduler.advanceUntilIdle()
        vm.requestDeleteSession("b")
        vm.confirmDeleteSession()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("a", "c"), vm.uiState.value.searchResults.map { it.session_id })
        vm.toggleSessionSelection("c")
        vm.confirmBulkDelete()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("a"), vm.uiState.value.searchResults.map { it.session_id })
    }

    @Test
    fun `rename is preserved and pending search cannot resurrect single or bulk deletions`() {
        coEvery { api.searchSessions(any(), any(), any(), any()) } returns hits("a", "b", "c")
        val vm = viewModel()
        vm.setSearchQuery("a")
        dispatcher.scheduler.advanceUntilIdle()
        coEvery { api.renameSession(any(), any()) } returns Response.success(Unit)
        vm.renameSession("a", "Renamed")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Renamed", vm.uiState.value.searchTitles["a"])
        lateinit var pending: Continuation<Response<SessionSearchResponse>>
        coEvery { api.searchSessions(any(), any(), any(), any()) } coAnswers { suspendCoroutine { pending = it } }
        vm.setSearchQuery("refresh")
        dispatcher.scheduler.advanceUntilIdle()
        coEvery { api.deleteSession("b") } returns Response.success(Unit)
        coEvery { api.bulkDeleteSessions(any()) } returns Response.success(BulkDeleteResponse(ok = true, deleted = 1))
        vm.requestDeleteSession("b")
        vm.confirmDeleteSession()
        vm.toggleSessionSelection("c")
        vm.confirmBulkDelete()
        dispatcher.scheduler.advanceUntilIdle()
        pending.resume(hits("a", "b", "c"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("a"), vm.uiState.value.searchResults.map { it.session_id })
        assertEquals("Renamed", vm.uiState.value.searchTitles["a"])
        assertFalse(vm.uiState.value.isSearching)
    }
}
