package com.m57.hermescontrol.ui.webhooks

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ActionResponse
import com.m57.hermescontrol.data.model.WebhooksResponse
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

@OptIn(ExperimentalCoroutinesApi::class)
class WebhookReconciliationTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<HermesApiService>()
    private var selected = "A"

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkObject(ApiClient, AuthManager)
        every { AuthManager.getSelectedProfileId() } answers { selected }
        every { ApiClient.hermesApi } returns api
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `refresh switch before dispatch does not load replacement`() =
        runTest(dispatcher) {
            coEvery { api.getWebhooks() } returns Response.success(WebhooksResponse(false))
            val vm = WebhooksViewModel(dispatcher)
            vm.loadWebhooks()
            selected = "B"
            dispatcher.scheduler.advanceUntilIdle()
            io.mockk.coVerify(exactly = 0) { api.getWebhooks() }
        }

    @Test
    fun `old toggle result and finally cannot change replacement action`() =
        runTest(dispatcher) {
            val old = CompletableDeferred<Response<ActionResponse>>()
            val replacement = CompletableDeferred<Response<ActionResponse>>()
            coEvery { api.configurePlatform(any(), any()) } coAnswers {
                if (selected == "A") old.await() else replacement.await()
            }
            val vm = WebhooksViewModel(dispatcher)
            vm.toggleWebhooks(true)
            dispatcher.scheduler.runCurrent()
            selected = "B"
            vm.toggleWebhooks(true)
            dispatcher.scheduler.runCurrent()
            old.complete(Response.success(ActionResponse(ok = false)))
            dispatcher.scheduler.runCurrent()
            assertTrue("stale refusal rolled back B", vm.uiState.value.enabled)
            assertTrue("stale finally unlocked B", vm.uiState.value.isActionRunning)
            assertEquals(null, vm.uiState.value.toastMessage)
            replacement.complete(Response.success(ActionResponse(ok = true)))
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(vm.uiState.value.enabled)
            assertFalse(vm.uiState.value.isActionRunning)
        }

    @Test
    fun `latest refresh wins and stale error cannot change replacement`() =
        runTest(dispatcher) {
            val old = CompletableDeferred<Response<WebhooksResponse>>()
            coEvery { api.getWebhooks() } coAnswers { old.await() }
            val vm = WebhooksViewModel(dispatcher)
            vm.loadWebhooks()
            dispatcher.scheduler.runCurrent()
            selected = "B"
            coEvery { api.getWebhooks() } returns Response.success(WebhooksResponse(true))
            vm.loadWebhooks()
            dispatcher.scheduler.runCurrent()
            old.complete(Response.error(400, okhttp3.ResponseBody.create(null, "old error")))
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(vm.uiState.value.enabled)
            assertEquals(null, vm.uiState.value.errorMessage)
            assertFalse(vm.uiState.value.isLoading)
        }

    @Test
    fun `newer refresh replaces older refresh on same connection`() =
        runTest(dispatcher) {
            val old = CompletableDeferred<Response<WebhooksResponse>>()
            coEvery { api.getWebhooks() } coAnswers { old.await() }
            val vm = WebhooksViewModel(dispatcher)
            vm.loadWebhooks()
            dispatcher.scheduler.runCurrent()
            coEvery { api.getWebhooks() } returns Response.success(WebhooksResponse(true))
            vm.loadWebhooks()
            dispatcher.scheduler.runCurrent()
            old.complete(Response.success(WebhooksResponse(false)))
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(vm.uiState.value.enabled)
        }

    @Test
    fun `connection switch alone discards old refresh without replacement load`() =
        runTest(dispatcher) {
            val old = CompletableDeferred<Response<WebhooksResponse>>()
            coEvery { api.getWebhooks() } coAnswers { old.await() }
            val vm = WebhooksViewModel(dispatcher)
            vm.loadWebhooks()
            dispatcher.scheduler.runCurrent()
            selected = "B"
            old.complete(Response.success(WebhooksResponse(true)))
            dispatcher.scheduler.advanceUntilIdle()
            assertFalse(vm.uiState.value.enabled)
        }

    @Test
    fun `toggle acceptance invalidates held load errors before mutation settles`() =
        runTest(dispatcher) {
            val load = CompletableDeferred<Response<WebhooksResponse>>()
            val mutation = CompletableDeferred<Response<ActionResponse>>()
            coEvery { api.getWebhooks() } coAnswers { load.await() }
            coEvery { api.configurePlatform(any(), any()) } coAnswers { mutation.await() }
            val vm = WebhooksViewModel(dispatcher)
            vm.loadWebhooks()
            dispatcher.scheduler.runCurrent()
            vm.toggleWebhooks(true)
            dispatcher.scheduler.runCurrent()
            load.complete(Response.error(400, okhttp3.ResponseBody.create(null, "old error")))
            dispatcher.scheduler.runCurrent()
            assertEquals(null, vm.uiState.value.errorMessage)
            mutation.complete(Response.success(ActionResponse(ok = true)))
            dispatcher.scheduler.advanceUntilIdle()
        }

    @Test
    fun `refresh during pending toggle preserves optimistic enablement`() =
        runTest(dispatcher) {
            val mutation = CompletableDeferred<Response<ActionResponse>>()
            coEvery { api.configurePlatform(any(), any()) } coAnswers { mutation.await() }
            coEvery { api.getWebhooks() } returns Response.success(WebhooksResponse(false))
            val vm = WebhooksViewModel(dispatcher)
            vm.toggleWebhooks(true)
            dispatcher.scheduler.runCurrent()
            vm.loadWebhooks()
            dispatcher.scheduler.runCurrent()
            assertTrue(vm.uiState.value.enabled)
            mutation.complete(Response.success(ActionResponse(ok = true)))
            dispatcher.scheduler.advanceUntilIdle()
        }

    @Test
    fun `refresh begun during mutation cannot roll back completed toggle`() =
        runTest(dispatcher) {
            val mutation = CompletableDeferred<Response<ActionResponse>>()
            val load = CompletableDeferred<Response<WebhooksResponse>>()
            coEvery { api.configurePlatform(any(), any()) } coAnswers { mutation.await() }
            coEvery { api.getWebhooks() } coAnswers { load.await() }
            val vm = WebhooksViewModel(dispatcher)
            vm.toggleWebhooks(true)
            dispatcher.scheduler.runCurrent()
            vm.loadWebhooks()
            dispatcher.scheduler.runCurrent()
            mutation.complete(Response.success(ActionResponse(ok = true)))
            dispatcher.scheduler.runCurrent()
            load.complete(Response.success(WebhooksResponse(false)))
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(vm.uiState.value.enabled)
            assertFalse(vm.uiState.value.isLoading)
        }
}
