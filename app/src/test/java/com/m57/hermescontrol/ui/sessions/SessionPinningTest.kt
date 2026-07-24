package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionLatestDescendantResponse
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SessionPinningTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<HermesApiService>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns api
        coEvery { api.getSessionLatestDescendant(any()) } throws
            IOException("Endpoint unavailable")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `pin identity survives compression`() {
        val session = SessionInfo(id = "tip", _lineage_root_id = "root")

        assertEquals("root", session.pinId())
        assertTrue(session.matchesPin("root"))
        assertFalse(session.matchesPin("other"))
    }

    @Test
    fun `toggle pin persists the durable lineage identity`() {
        val store = FakeSessionPinStore()
        val viewModel = SessionsViewModel(store)
        val session = SessionInfo(id = "tip", _lineage_root_id = "root")

        viewModel.toggleSessionPin(session)
        assertEquals(listOf("root"), viewModel.uiState.value.pinnedSessionIds)
        assertEquals(listOf("root"), store.saved)

        viewModel.toggleSessionPin(session)
        assertTrue(viewModel.uiState.value.pinnedSessionIds.isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `loading a missing pin preserves its durable identity`() =
        runTest {
            val pinnedTip = SessionInfo(id = "tip", title = "Pinned")
            coEvery { api.getSession("root") } returns Response.success(pinnedTip)

            val hydrated = hydratePinnedSessions(api, listOf("root"))

            assertEquals(listOf("tip"), hydrated.map { it.id })
            assertEquals("root", hydrated.single()._lineage_root_id)
        }

    @Test
    fun `loading a compressed pin resolves and hydrates its live tip`() =
        runTest {
            coEvery { api.getSessionLatestDescendant("root") } returns
                Response.success(SessionLatestDescendantResponse(session_id = "tip"))
            coEvery { api.getSession("tip") } returns
                Response.success(SessionInfo(id = "tip", title = "Live tip"))

            val hydrated = hydratePinnedSessions(api, listOf("root"))

            assertEquals(listOf("tip"), hydrated.map { it.id })
            assertEquals("root", hydrated.single()._lineage_root_id)
        }

    @Test
    fun `pinned section follows pin order and removes duplicates from recents`() {
        val first = SessionInfo(id = "first", _lineage_root_id = "root-a")
        val child = SessionInfo(id = "child", parent_session_id = "first")
        val second = SessionInfo(id = "second")
        val recent = SessionInfo(id = "recent")

        val sections =
            buildSessionSections(
                sessions = listOf(recent, first, child, second),
                pinnedSessionIds = listOf("second", "root-a", "first"),
            )

        assertEquals(listOf("second", "first"), sections.pinned.map { it.session.id })
        assertEquals(listOf("recent", "child"), sections.recent.map { it.session.id })
        assertTrue(sections.pinned.all { it.depth == 0 && it.branchStem == null })
        assertTrue(sections.recent.all { it.depth == 0 })
    }

    @Test
    fun `hydrated pins do not change the recent pagination offset`() {
        val loaded = (1..20).map { SessionInfo(id = "recent-$it") }
        val hydratedPin = SessionInfo(id = "pinned-tip", _lineage_root_id = "pinned-root")
        val state =
            SessionsUiState(
                sessions = loaded + hydratedPin,
                loadedSessionIds = loaded.mapTo(mutableSetOf()) { it.id },
                pinnedSessionIds = listOf("pinned-root"),
                total = 21,
            )

        assertTrue(state.hasMore)
        assertEquals(20, state.loadedSessionIds.size)
    }

    @Test
    fun `deleting a compressed tip removes its durable pin`() {
        val session = SessionInfo(id = "tip", _lineage_root_id = "root")

        val remaining =
            remainingPinsAfterDeleting(
                pinnedSessionIds = listOf("root", "other"),
                sessions = listOf(session),
                deletedSessionIds = setOf("tip"),
            )

        assertEquals(listOf("other"), remaining)
    }

    private class FakeSessionPinStore(
        initial: List<String> = emptyList(),
    ) : SessionPinStore {
        var saved = initial

        override fun load(): List<String> = saved

        override fun save(pinIds: List<String>) {
            saved = pinIds
        }
    }
}
