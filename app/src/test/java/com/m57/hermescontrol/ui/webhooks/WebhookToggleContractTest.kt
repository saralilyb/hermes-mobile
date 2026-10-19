package com.m57.hermescontrol.ui.webhooks

import com.m57.hermescontrol.data.model.ActionResponse
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebhookToggleContractTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<HermesApiService>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkObject(ApiClient, com.m57.hermescontrol.data.local.AuthManager)
        every { com.m57.hermescontrol.data.local.AuthManager.getSelectedProfileId() } returns "A"
        every { ApiClient.hermesApi } returns api
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `toggle uses configure endpoint checks refusal and rejects overlap`() =
        kotlinx.coroutines.test.runTest(dispatcher) {
            val response = kotlinx.coroutines.CompletableDeferred<retrofit2.Response<ActionResponse>>()
            io.mockk.coEvery { api.configurePlatform(any(), any()) } coAnswers { response.await() }
            val vm = WebhooksViewModel(ioDispatcher = dispatcher)
            vm.toggleWebhooks(true)
            vm.toggleWebhooks(false)
            dispatcher.scheduler.runCurrent()
            io.mockk.coVerify(exactly = 1) {
                api.configurePlatform(
                    "webhook",
                    MessagingPlatformUpdate(enabled = true),
                )
            }
            io.mockk.coVerify(exactly = 0) { api.toggleWebhooks(any()) }
            response.complete(retrofit2.Response.success(ActionResponse(ok = false)))
            dispatcher.scheduler.advanceUntilIdle()
            org.junit.Assert.assertFalse(vm.uiState.value.enabled)
            org.junit.Assert.assertFalse(vm.uiState.value.isActionRunning)
            assertTrue(vm.uiState.value.toastMessage.orEmpty().contains("Failed"))
            io.mockk.coEvery { api.configurePlatform(any(), any()) } returns
                retrofit2.Response.success(ActionResponse(ok = true))
            vm.toggleWebhooks(true)
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(vm.uiState.value.enabled)
            vm.toggleWebhooks(false)
            dispatcher.scheduler.advanceUntilIdle()
            org.junit.Assert.assertFalse(vm.uiState.value.enabled)
        }

    @Test
    fun `held refresh cannot overwrite successful toggle`() =
        kotlinx.coroutines.test.runTest(dispatcher) {
            val response =
                kotlinx.coroutines.CompletableDeferred<
                    retrofit2.Response<com.m57.hermescontrol.data.model.WebhooksResponse>,
                >()
            val started = java.util.concurrent.CountDownLatch(1)
            io.mockk.coEvery { api.getWebhooks() } coAnswers {
                started.countDown()
                response.await()
            }
            io.mockk.coEvery { api.configurePlatform(any(), any()) } returns
                retrofit2.Response.success(ActionResponse(ok = true))
            val vm = WebhooksViewModel(dispatcher)
            vm.loadWebhooks()
            dispatcher.scheduler.runCurrent()
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            vm.toggleWebhooks(true)
            dispatcher.scheduler.runCurrent()
            response.complete(retrofit2.Response.success(com.m57.hermescontrol.data.model.WebhooksResponse(false)))
            // HEAD uses real IO for load; let its continuation rejoin Main.
            repeat(20) {
                Thread.sleep(10)
                dispatcher.scheduler.runCurrent()
            }
            assertTrue("stale disabled GET overwrote successful enable", vm.uiState.value.enabled)
        }

    @Test
    fun `toggle locks synchronously before dispatch`() {
        val vm = WebhooksViewModel(ioDispatcher = dispatcher)
        vm.toggleWebhooks(true)
        assertTrue(vm.uiState.value.isActionRunning)
    }
}
