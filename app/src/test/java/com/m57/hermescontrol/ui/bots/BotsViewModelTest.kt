package com.m57.hermescontrol.ui.bots

import com.m57.hermescontrol.data.model.BotRosterMeta
import com.m57.hermescontrol.data.model.CanonicalSessionInfo
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.ProfileWorkerSummary
import com.m57.hermescontrol.data.model.ProfilesResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class BotsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: HermesApiService

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(ApiClient)
        api = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns api
    }

    @After
    fun tearDown() {
        unmockkObject(ApiClient)
        Dispatchers.resetMain()
    }

    @Test
    fun `load and refresh use only getProfiles`() =
        runTest(dispatcher) {
            coEvery { api.getProfiles() } returns Response.success(ProfilesResponse(listOf(ProfileInfo("bot"))))
            val viewModel = BotsViewModel(dispatcher, autoLoad = false, clockSeconds = { 1_000L })

            viewModel.loadBots()
            advanceUntilIdle()
            viewModel.loadBots(isRefresh = true)
            advanceUntilIdle()

            assertEquals(listOf("bot"), viewModel.uiState.value.profiles.map { it.name })
            assertFalse(viewModel.uiState.value.isLoading)
            assertFalse(viewModel.uiState.value.isRefreshing)
            coVerify(exactly = 2) { api.getProfiles() }
            coVerify(exactly = 0) { api.getActiveProfile() }
            coVerify(exactly = 0) { api.setActiveProfile(any()) }
            coVerify(exactly = 0) { api.createProfile(any()) }
        }

    @Test
    fun `filtering and bounded deterministic presence`() {
        val recent = ProfileInfo("recent", canonical_session = CanonicalSessionInfo("r", last_active = 950L))
        val stale = ProfileInfo("stale", canonical_session = CanonicalSessionInfo("s", last_active = 909L))
        val worker = ProfileInfo("worker", worker_session = ProfileWorkerSummary("w", last_active = 960L))
        val undatedWorker = ProfileInfo("undated", worker_session = ProfileWorkerSummary("u"))
        val state = BotsUiState(profiles = listOf(recent, stale, worker, undatedWorker), nowSeconds = 1_000L)

        assertEquals(listOf("recent", "worker"), state.activeNowBots.map { it.name }.sorted())
        assertTrue(state.activeNowBots.size <= MAX_ACTIVE_NOW_BOTS)
        assertFalse(undatedWorker.isActiveAt(1_000L))
    }

    @Test
    fun `search and hidden filter are fail closed`() {
        val visible = ProfileInfo("alpha", description = "Research")
        val hidden =
            ProfileInfo(
                name = "beta",
                ui_meta =
                    mapOf(
                        "hermes-bots" to
                            Json.encodeToJsonElement(
                                BotRosterMeta(title = "Builder", hidden = true),
                            ),
                    ),
            )
        val base = BotsUiState(profiles = listOf(hidden, visible), nowSeconds = 0L)
        assertEquals(listOf("alpha"), base.displayProfiles.map { it.name })
        assertEquals(
            listOf("beta"),
            base.copy(showHidden = true, searchQuery = "build").displayProfiles.map { it.name },
        )
    }
}
