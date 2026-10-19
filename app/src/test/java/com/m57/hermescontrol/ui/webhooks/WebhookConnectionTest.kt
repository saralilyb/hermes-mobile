package com.m57.hermescontrol.ui.webhooks

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class WebhookConnectionTest {
    private val dispatcher = StandardTestDispatcher()
    private val a = MockWebServer()
    private val b = MockWebServer()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        a.start()
        b.start()
        mockkObject(AuthManager)
        CookieManager.setJarForTest(buildFakePersistentCookieJar())
        every { AuthManager.isGatedMode() } returns false
        select(a, "A")
        a.enqueue(MockResponse().setBody("{\"ok\":true}"))
        b.enqueue(MockResponse().setBody("{\"ok\":true}"))
    }

    private fun select(
        server: MockWebServer,
        id: String,
    ) {
        every { AuthManager.getSelectedProfileId() } returns id
        every { AuthManager.getToken() } returns "token-$id"
        every { AuthManager.endpointForBuild() } returns
            ServerEndpoint.parse(server.url("/").toString(), CleartextPolicy.ALLOW_WITH_WARNING)
        ApiClient.rebuild()
    }

    @After
    fun cleanup() {
        ApiClient.rebuild()
        a.shutdown()
        b.shutdown()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `switch between acceptance and dispatch never pairs A URL with B token`() =
        runTest(dispatcher) {
            val vm = WebhooksViewModel(dispatcher)
            vm.toggleWebhooks(true)
            select(b, "B")
            dispatcher.scheduler.runCurrent()
            // On the regression HEAD this receives A's configure request with token-B.
            val leaked = a.takeRequest(1, TimeUnit.SECONDS)
            assertNull("A endpoint received replacement credentials: ${leaked?.getHeader("Authorization")}", leaked)
            assertEquals(0, b.requestCount)
        }

    @Test
    fun `connection ABA before dispatch rejects old action`() =
        runTest(dispatcher) {
            val vm = WebhooksViewModel(dispatcher)
            vm.toggleWebhooks(true)
            select(b, "B")
            select(a, "A")
            dispatcher.scheduler.runCurrent()
            assertNull("rebuilt A must reject the old operation", a.takeRequest(1, TimeUnit.SECONDS))
            assertEquals(0, b.requestCount)
        }

    @Test
    fun `unchanged connection dispatches with its own bearer`() =
        runTest(dispatcher) {
            val vm = WebhooksViewModel(dispatcher)
            vm.toggleWebhooks(true)
            dispatcher.scheduler.runCurrent()
            val request = a.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer token-A", request.getHeader("Authorization"))
            assertEquals(0, b.requestCount)
        }
}
