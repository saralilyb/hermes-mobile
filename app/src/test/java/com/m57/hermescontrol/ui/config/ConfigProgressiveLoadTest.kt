package com.m57.hermescontrol.ui.config

import com.m57.hermescontrol.data.model.ConfigSchemaResponse
import com.m57.hermescontrol.data.model.RawConfigResponse
import com.m57.hermescontrol.data.model.SchemaField
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigProgressiveLoadTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<HermesApiService>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns api
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `optional endpoints do not block structured editor`() =
        runTest(dispatcher) {
            val defaults = CompletableDeferred<Response<Map<String, JsonElement>>>()
            val raw = CompletableDeferred<Response<RawConfigResponse>>()
            coEvery { api.getConfig() } returns Response.success(mapOf("name" to JsonPrimitive("server")))
            coEvery { api.getConfigSchema() } returns
                Response.success(
                    ConfigSchemaResponse(
                        mapOf("name" to SchemaField("string", category = "general")),
                        listOf("general"),
                    ),
                )
            coEvery { api.getConfigDefaults() } coAnswers { defaults.await() }
            coEvery { api.getRawConfig() } coAnswers { raw.await() }
            val vm = ConfigViewModel(ioDispatcher = dispatcher)
            dispatcher.scheduler.runCurrent()
            try {
                assertFalse(vm.uiState.value.isLoading)
                assertEquals(JsonPrimitive("server"), vm.uiState.value.values?.get("name"))
                vm.updateField("name", JsonPrimitive("draft"))
            } finally {
                defaults.complete(Response.success(mapOf("name" to JsonPrimitive("default"))))
                raw.complete(Response.success(RawConfigResponse(path = "config.yaml")))
                dispatcher.scheduler.advanceUntilIdle()
            }
            assertEquals(JsonPrimitive("draft"), vm.uiState.value.values?.get("name"))
            assertEquals(JsonPrimitive("default"), vm.uiState.value.defaults?.get("name"))
        }
}
