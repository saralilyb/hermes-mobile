package com.m57.hermescontrol.ui.system

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.DoctorResponse
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class DoctorConnectionTest {
    private val dispatcher = StandardTestDispatcher()
    private val a = mockk<HermesApiService>(relaxed = true)
    private val b = mockk<HermesApiService>(relaxed = true)
    private var selected = "A"

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkObject(AuthManager, ApiClient)
        io.mockk.mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        coEvery { b.getActionStatus(any()) } returns
            Response.success(com.m57.hermescontrol.data.model.ActionStatusResponse(running = false))
        every { AuthManager.getSelectedProfileId() } answers { selected }
        every { ApiClient.hermesApi } answers { if (selected == "A") a else b }
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `switch before dispatch does not run Doctor on replacement`() =
        runTest(dispatcher) {
            coEvery { b.runDoctor() } returns Response.success(DoctorResponse(ok = false))
            val vm = SystemViewModel(dispatcher)
            vm.runDoctor()
            selected = "B"
            dispatcher.scheduler.advanceUntilIdle()
            coVerify(exactly = 0) { b.runDoctor() }
            coVerify(exactly = 0) { a.runDoctor() }
            assertNull(vm.uiState.value.toastMessage)
        }

    @Test
    fun `stale Doctor response cannot start polling or publish`() =
        runTest(dispatcher) {
            val response = CompletableDeferred<Response<DoctorResponse>>()
            coEvery { a.runDoctor() } coAnswers { response.await() }
            val vm = SystemViewModel(dispatcher)
            vm.runDoctor()
            dispatcher.scheduler.runCurrent()
            selected = "B"
            response.complete(Response.success(DoctorResponse(ok = true, name = "doctor")))
            dispatcher.scheduler.advanceUntilIdle()
            coVerify(exactly = 0) { b.getActionStatus(any()) }
            assertNull(vm.uiState.value.activeAction)
            assertNull(vm.uiState.value.toastMessage)
        }

    @Test
    fun `stale polling response cannot publish or reload replacement`() =
        runTest(dispatcher) {
            val response = CompletableDeferred<Response<com.m57.hermescontrol.data.model.ActionStatusResponse>>()
            coEvery { a.runDoctor() } returns Response.success(DoctorResponse(ok = true, name = "doctor"))
            coEvery { a.getActionStatus(any()) } coAnswers { response.await() }
            val vm = SystemViewModel(dispatcher)
            vm.runDoctor()
            dispatcher.scheduler.runCurrent()
            dispatcher.scheduler.advanceTimeBy(1200)
            dispatcher.scheduler.runCurrent()
            coVerify(exactly = 1) { a.getActionStatus(any()) }
            selected = "B"
            response.complete(Response.success(com.m57.hermescontrol.data.model.ActionStatusResponse(running = false)))
            dispatcher.scheduler.advanceUntilIdle()
            assertNull(vm.uiState.value.actionLog)
            coVerify(exactly = 0) { b.getSystemStats() }
        }

    @Test
    fun `current Doctor response starts polling and publishes status`() =
        runTest(dispatcher) {
            coEvery { a.runDoctor() } returns Response.success(DoctorResponse(ok = true, name = "doctor"))
            coEvery { a.getActionStatus(any()) } returns
                Response.success(com.m57.hermescontrol.data.model.ActionStatusResponse(running = false))
            val vm = SystemViewModel(dispatcher)
            vm.runDoctor()
            dispatcher.scheduler.advanceUntilIdle()
            coVerify(exactly = 1) { a.getActionStatus("doctor") }
            org.junit.Assert.assertEquals("Doctor started", vm.uiState.value.toastMessage)
            org.junit.Assert.assertEquals(false, vm.uiState.value.actionLog?.running)
        }

    @Test
    fun `poll stops when connection changes during delay`() =
        runTest(dispatcher) {
            coEvery { a.runDoctor() } returns Response.success(DoctorResponse(ok = true, name = "doctor"))
            val vm = SystemViewModel(dispatcher)
            vm.runDoctor()
            dispatcher.scheduler.runCurrent()
            selected = "B"
            dispatcher.scheduler.advanceUntilIdle()
            coVerify(exactly = 0) { b.getActionStatus(any()) }
            assertNull(vm.uiState.value.actionLog)
        }
}
