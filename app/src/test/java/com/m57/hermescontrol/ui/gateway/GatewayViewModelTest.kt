package com.m57.hermescontrol.ui.gateway

import com.m57.hermescontrol.data.model.MemoryPressureStatus
import com.m57.hermescontrol.data.model.StatusResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockApi = mockk()
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `loadStatus success updates state with status data`() =
        runTest {
            val mockResponse = StatusResponse(version = "test")
            coEvery { mockApi.getStatus() } returns Response.success(mockResponse)

            val viewModel = GatewayViewModel(ioDispatcher = testDispatcher)

            // Before calling, status should be null
            assertNull(viewModel.uiState.value.status)
            assertFalse(viewModel.uiState.value.isLoading)

            viewModel.loadStatus()

            // Assert loading state (before coroutine executes)
            assertTrue(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.errorMessage)

            testDispatcher.scheduler.advanceUntilIdle()

            // Assert final state
            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(mockResponse, viewModel.uiState.value.status)
            assertNull(viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `loadStatus error updates state with error message`() =
        runTest {
            coEvery { mockApi.getStatus() } returns Response.error(500, "Server Error".toResponseBody(null))

            val viewModel = GatewayViewModel(ioDispatcher = testDispatcher)

            viewModel.loadStatus()
            assertTrue(viewModel.uiState.value.isLoading)

            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.status)
            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.contains("500") == true,
            )
        }

    @Test
    fun `loadStatus network exception updates state with error message`() =
        runTest {
            coEvery { mockApi.getStatus() } throws RuntimeException("Network timeout")

            val viewModel = GatewayViewModel(ioDispatcher = testDispatcher)

            viewModel.loadStatus()

            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.status)
            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.contains("Network timeout") == true,
            )
        }

    @Test
    fun `profile change clears stale pressure`() =
        runTest {
            coEvery { mockApi.getStatus() } returns
                Response.success(StatusResponse(memory = MemoryPressureStatus("critical"))) andThen
                Response.success(StatusResponse())
            val viewModel = GatewayViewModel(ioDispatcher = testDispatcher)
            viewModel.onProfileChanged("old")
            advanceUntilIdle()
            assertEquals("critical", viewModel.uiState.value.status?.memory?.pressure)
            viewModel.onProfileChanged("new")
            assertNull(viewModel.uiState.value.status)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.status?.memory)
        }

    @Test
    fun `newer same-profile refresh wins over older response`() =
        runTest {
            val firstStarted = CompletableDeferred<Unit>()
            val firstResult = CompletableDeferred<Response<StatusResponse>>()
            var callCount = 0
            coEvery { mockApi.getStatus() } coAnswers {
                callCount += 1
                if (callCount == 1) {
                    firstStarted.complete(Unit)
                    firstResult.await()
                } else {
                    Response.success(StatusResponse())
                }
            }
            val viewModel = GatewayViewModel(ioDispatcher = testDispatcher)
            viewModel.onProfileChanged("profile")
            runCurrent()
            firstStarted.await()
            viewModel.loadStatus()
            runCurrent()
            firstResult.complete(Response.success(StatusResponse(memory = MemoryPressureStatus("critical"))))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.status?.memory)
        }

    @Test
    fun `unknown sample preserves previous actionable pressure`() =
        runTest {
            coEvery { mockApi.getStatus() } returns
                Response.success(StatusResponse(memory = MemoryPressureStatus("critical"))) andThen
                Response.success(StatusResponse(memory = MemoryPressureStatus("unknown")))
            val viewModel = GatewayViewModel(ioDispatcher = testDispatcher)
            viewModel.onProfileChanged("profile")
            advanceUntilIdle()
            viewModel.loadStatus()
            advanceUntilIdle()

            assertEquals("critical", viewModel.uiState.value.status?.memory?.pressure)
        }

    @Test
    fun `profile change fences old action transport and completion`() =
        runTest {
            val oldApi = mockApi
            val newApi = mockk<HermesApiService>()
            val oldActionStarted = CompletableDeferred<Unit>()
            val oldActionResult = CompletableDeferred<Response<Unit>>()
            var currentApi = oldApi
            every { ApiClient.hermesApi } answers { currentApi }
            coEvery { oldApi.getStatus() } returns Response.success(StatusResponse())
            coEvery { oldApi.startGateway() } coAnswers {
                oldActionStarted.complete(Unit)
                oldActionResult.await()
            }
            coEvery { newApi.getStatus() } returns Response.success(StatusResponse())
            coEvery { newApi.startGateway() } returns Response.success(Unit)

            val viewModel = GatewayViewModel(ioDispatcher = testDispatcher)
            viewModel.onProfileChanged("old")
            advanceUntilIdle()
            viewModel.startGateway()
            runCurrent()
            oldActionStarted.await()
            currentApi = newApi
            viewModel.onProfileChanged("new")
            runCurrent()
            viewModel.startGateway()
            runCurrent()
            oldActionResult.complete(Response.error(500, "stale".toResponseBody()))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.errorMessage)
            assertEquals("Gateway started successfully", viewModel.uiState.value.toastMessage)
            assertFalse(viewModel.uiState.value.isActionRunning)
        }
}
