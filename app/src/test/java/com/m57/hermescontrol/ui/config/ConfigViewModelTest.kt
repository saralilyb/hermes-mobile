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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelTest {
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
    fun `form save rejects an older load and a later refresh still publishes`() =
        runBlocking {
            val oldLoadStarted = CompletableDeferred<Unit>()
            val releaseOldLoad = CompletableDeferred<Unit>()
            val oldLoadReturned = CompletableDeferred<Unit>()
            val configCalls = AtomicInteger()
            stubStableLoadEndpoints()
            coEvery { mockApi.getConfig() } coAnswers {
                when (configCalls.incrementAndGet()) {
                    1 -> configResponse("initial")
                    2 -> {
                        oldLoadStarted.complete(Unit)
                        withContext(NonCancellable) { releaseOldLoad.await() }
                        oldLoadReturned.complete(Unit)
                        configResponse("pre-save")
                    }
                    3 -> configResponse("saved")
                    else -> configResponse("later-refresh")
                }
            }
            coEvery { mockApi.updateConfig(any()) } returns Response.success(Unit)

            val viewModel = ConfigViewModel()
            awaitModel(viewModel, "initial")
            viewModel.loadAll()
            withTimeout(5_000) { oldLoadStarted.await() }

            viewModel.updateField("model", JsonPrimitive("submitted"))
            viewModel.saveConfig()
            awaitModel(viewModel, "saved")

            releaseOldLoad.complete(Unit)
            withTimeout(5_000) { oldLoadReturned.await() }
            assertEquals("saved", viewModel.uiState.value.modelValue())

            viewModel.loadAll()
            awaitModel(viewModel, "later-refresh")
        }

    @Test
    fun `YAML save rejects an older load and a later refresh still publishes`() =
        runBlocking {
            val oldLoadStarted = CompletableDeferred<Unit>()
            val releaseOldLoad = CompletableDeferred<Unit>()
            val oldLoadReturned = CompletableDeferred<Unit>()
            val configCalls = AtomicInteger()
            stubStableLoadEndpoints()
            coEvery { mockApi.getConfig() } coAnswers {
                when (configCalls.incrementAndGet()) {
                    1 -> configResponse("initial")
                    2 -> {
                        oldLoadStarted.complete(Unit)
                        withContext(NonCancellable) { releaseOldLoad.await() }
                        oldLoadReturned.complete(Unit)
                        configResponse("pre-save")
                    }
                    3 -> configResponse("yaml-saved")
                    else -> configResponse("later-refresh")
                }
            }
            coEvery { mockApi.updateRawConfig(any()) } returns Response.success(Unit)

            val viewModel = ConfigViewModel()
            awaitModel(viewModel, "initial")
            viewModel.toggleYamlMode()
            withTimeout(5_000) { viewModel.uiState.first { it.yamlText == "model: initial" } }
            viewModel.loadAll()
            withTimeout(5_000) { oldLoadStarted.await() }

            viewModel.setYamlText("model: yaml-saved")
            viewModel.saveYamlConfig()
            awaitModel(viewModel, "yaml-saved")

            releaseOldLoad.complete(Unit)
            withTimeout(5_000) { oldLoadReturned.await() }
            assertEquals("yaml-saved", viewModel.uiState.value.modelValue())

            viewModel.loadAll()
            awaitModel(viewModel, "later-refresh")
        }

    private fun stubStableLoadEndpoints() {
        coEvery { mockApi.getConfigSchema() } returns Response.success(schema())
        coEvery { mockApi.getConfigDefaults() } returns configResponse("default")
        coEvery { mockApi.getRawConfig() } returns
            Response.success(RawConfigResponse(path = "/tmp/config.yaml", yaml = "model: initial"))
    }

    private suspend fun awaitModel(
        viewModel: ConfigViewModel,
        expected: String,
    ) {
        withTimeout(5_000) {
            viewModel.uiState.first { it.modelValue() == expected && !it.isSaving && !it.yamlIsSaving }
        }
    }

    private fun ConfigUiState.modelValue(): String? = (values?.get("model") as? JsonPrimitive)?.content

    private fun configResponse(model: String): Response<Map<String, JsonElement>> =
        Response.success(mapOf("model" to JsonPrimitive(model)))

    private fun schema() =
        ConfigSchemaResponse(
            fields = mapOf("model" to SchemaField(type = "string", category = "general")),
            category_order = listOf("general"),
        )
}
