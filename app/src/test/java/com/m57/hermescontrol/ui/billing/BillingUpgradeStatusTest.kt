package com.m57.hermescontrol.ui.billing

import com.m57.hermescontrol.data.model.SubscriptionUpgradeResponse
import com.m57.hermescontrol.data.ws.BillingRepository
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BillingUpgradeStatusTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkObject(BillingRepository)
        coEvery { BillingRepository.getSubscriptionState() } returns null
        coEvery { BillingRepository.getUsageBars() } returns null
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `reason overrides optimistic ok and final status`() =
        runTest(dispatcher) {
            coEvery { BillingRepository.upgradeSubscription(any()) } returns
                SubscriptionUpgradeResponse(ok = true, status = "upgraded", reason = "authentication_required")
            val vm = BillingViewModel()
            vm.upgrade("plan")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.actionMessage.orEmpty().contains("Action required"))
            coEvery { BillingRepository.upgradeSubscription(any()) } returns
                SubscriptionUpgradeResponse(ok = false, reason = "authentication_required")
            vm.upgrade("plan")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.actionMessage.orEmpty().contains("Action required"))
            coEvery { BillingRepository.upgradeSubscription(any()) } returns
                SubscriptionUpgradeResponse(ok = true, status = "upgraded")
            vm.upgrade("plan")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.actionMessage.orEmpty().contains("successful"))
        }

    @Test
    fun `nonfinal upgrade statuses never report success`() =
        runTest(dispatcher) {
            listOf("requires_action", "action_required", "payment_failed").forEach { status ->
                coEvery { BillingRepository.upgradeSubscription(any()) } returns
                    SubscriptionUpgradeResponse(ok = true, status = status)
                val vm = BillingViewModel()
                vm.upgrade("plan")
                advanceUntilIdle()
                val text = vm.uiState.value.actionMessage.orEmpty()
                assertFalse(status, text.contains("successful", ignoreCase = true))
                assertTrue(
                    status,
                    text.contains(if (status == "payment_failed") "Payment failed" else "Action required"),
                )
            }
        }
}
