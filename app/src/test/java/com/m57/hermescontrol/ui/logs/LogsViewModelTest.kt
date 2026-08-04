package com.m57.hermescontrol.ui.logs

import com.m57.hermescontrol.data.model.LogResponse
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val mockApi = mockk<HermesApiService>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `loadLogs requests the default server filters`() =
        runBlocking {
            coEvery {
                mockApi.getLogs(
                    file = "agent",
                    lines = 100,
                    level = "ALL",
                    component = "all",
                )
            } returns Response.success(LogResponse(lines = listOf("default")))

            val viewModel = LogsViewModel()
            viewModel.loadLogs()
            withTimeout(5_000) {
                viewModel.uiState.first { it.logs == listOf("default") }
            }

            coVerify(exactly = 1) {
                mockApi.getLogs(
                    file = "agent",
                    lines = 100,
                    level = "ALL",
                    component = "all",
                )
            }
            assertEquals(LogsFilters(), viewModel.uiState.value.filters)
        }

    @Test
    fun `setFilters combines choices and reloads with the latest filters`() =
        runBlocking {
            val firstRequestStarted = CompletableDeferred<Unit>()
            coEvery {
                mockApi.getLogs(
                    file = "agent",
                    lines = 100,
                    level = "ALL",
                    component = "all",
                )
            } coAnswers {
                firstRequestStarted.complete(Unit)
                awaitCancellation()
            }
            coEvery {
                mockApi.getLogs(
                    file = "errors",
                    lines = 100,
                    level = "DEBUG",
                    component = "all",
                )
            } returns Response.success(LogResponse(lines = listOf("filtered")))

            val viewModel = LogsViewModel()
            viewModel.loadLogs()
            withTimeout(5_000) { firstRequestStarted.await() }

            viewModel.setFilters(LogsFilters(file = "errors"))
            viewModel.setFilters(viewModel.uiState.value.filters.copy(level = "DEBUG"))
            withTimeout(5_000) {
                viewModel.uiState.first { it.logs == listOf("filtered") }
            }

            val expected = LogsFilters(file = "errors", level = "DEBUG")
            coVerify(exactly = 1) {
                mockApi.getLogs(
                    file = expected.file,
                    lines = expected.lines,
                    level = expected.level,
                    component = expected.component,
                )
            }
            assertEquals(expected, viewModel.uiState.value.filters)
        }

    @Test
    fun `loadLogs accepts the legacy logs response field`() =
        runBlocking {
            coEvery { mockApi.getLogs(any(), any(), any(), any()) } returns
                Response.success(LogResponse(logs = listOf("legacy")))

            val viewModel = LogsViewModel()
            viewModel.loadLogs()
            withTimeout(5_000) {
                viewModel.uiState.first { it.logs == listOf("legacy") }
            }

            assertEquals(listOf("legacy"), viewModel.uiState.value.logs)
        }
}
