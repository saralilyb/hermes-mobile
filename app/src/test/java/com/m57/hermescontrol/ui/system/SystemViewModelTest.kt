package com.m57.hermescontrol.ui.system

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SystemViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load never starts doctor`() =
        kotlinx.coroutines.test.runTest(testDispatcher) {
            io.mockk.mockkStatic(android.util.Log::class)
            io.mockk.every { android.util.Log.w(any(), any<String>()) } returns 0
            io.mockk.mockkObject(com.m57.hermescontrol.data.remote.ApiClient)
            val api = io.mockk.mockk<com.m57.hermescontrol.data.remote.HermesApiService>(relaxed = true)
            io.mockk.every { com.m57.hermescontrol.data.remote.ApiClient.hermesApi } returns api
            try {
                val vm = SystemViewModel(ioDispatcher = testDispatcher)
                vm.loadAll()
                testDispatcher.scheduler.advanceUntilIdle()
                io.mockk.coVerify(exactly = 0) { api.runDoctor() }
            } finally {
                io.mockk.unmockkAll()
            }
        }

    @Test
    fun `openUpdateConfirm sets updateConfirmOpen to true`() {
        val viewModel = SystemViewModel()

        assertFalse(viewModel.uiState.value.updateConfirmOpen)

        viewModel.openUpdateConfirm()

        assertTrue(viewModel.uiState.value.updateConfirmOpen)
    }

    @Test
    fun `closeUpdateConfirm sets updateConfirmOpen to false`() {
        val viewModel = SystemViewModel()

        // First open it
        viewModel.openUpdateConfirm()
        assertTrue(viewModel.uiState.value.updateConfirmOpen)

        // Then close it
        viewModel.closeUpdateConfirm()
        assertFalse(viewModel.uiState.value.updateConfirmOpen)
    }

    @Test
    fun `initial uiState has null learningGraph`() {
        val viewModel = SystemViewModel()

        org.junit.Assert.assertNull(viewModel.uiState.value.learningGraph)
    }
}
