package com.m57.hermescontrol.ui.config

import com.m57.hermescontrol.data.model.ConfigSchemaResponse
import com.m57.hermescontrol.data.model.RawConfigResponse
import com.m57.hermescontrol.data.model.SchemaField
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.util.concurrent.atomic.AtomicBoolean
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
            assertTrue(viewModel.uiState.value.isLoading)

            viewModel.updateField("model", JsonPrimitive("submitted"))
            viewModel.saveConfig()
            assertFalse(viewModel.uiState.value.isLoading)
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
            assertTrue(viewModel.uiState.value.isLoading)

            viewModel.setYamlText("model: yaml-saved")
            viewModel.saveYamlConfig()
            assertFalse(viewModel.uiState.value.isLoading)
            awaitModel(viewModel, "yaml-saved")

            releaseOldLoad.complete(Unit)
            withTimeout(5_000) { oldLoadReturned.await() }
            assertEquals("yaml-saved", viewModel.uiState.value.modelValue())

            viewModel.loadAll()
            awaitModel(viewModel, "later-refresh")
        }

    @Test
    fun `failed form save clears loading after canceling an older load`() =
        runBlocking {
            val oldLoadStarted = CompletableDeferred<Unit>()
            val releaseOldLoad = CompletableDeferred<Unit>()
            val configCalls = AtomicInteger()
            stubStableLoadEndpoints()
            coEvery { mockApi.getConfig() } coAnswers {
                if (configCalls.incrementAndGet() == 1) {
                    configResponse("initial")
                } else {
                    oldLoadStarted.complete(Unit)
                    withContext(NonCancellable) { releaseOldLoad.await() }
                    configResponse("stale")
                }
            }
            coEvery { mockApi.updateConfig(any()) } returns Response.error(500, "save failed".toResponseBody())

            val viewModel = ConfigViewModel()
            awaitModel(viewModel, "initial")
            viewModel.loadAll()
            withTimeout(5_000) { oldLoadStarted.await() }
            assertTrue(viewModel.uiState.value.isLoading)

            viewModel.updateField("model", JsonPrimitive("submitted"))
            viewModel.saveConfig()
            withTimeout(5_000) { viewModel.uiState.first { !it.isSaving } }

            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals("submitted", viewModel.uiState.value.modelValue())
            releaseOldLoad.complete(Unit)
            Unit
        }

    @Test
    fun `failed YAML save clears loading after canceling an older load`() =
        runBlocking {
            val oldLoadStarted = CompletableDeferred<Unit>()
            val releaseOldLoad = CompletableDeferred<Unit>()
            val configCalls = AtomicInteger()
            stubStableLoadEndpoints()
            coEvery { mockApi.getConfig() } coAnswers {
                if (configCalls.incrementAndGet() == 1) {
                    configResponse("initial")
                } else {
                    oldLoadStarted.complete(Unit)
                    withContext(NonCancellable) { releaseOldLoad.await() }
                    configResponse("stale")
                }
            }
            coEvery { mockApi.updateRawConfig(any()) } returns Response.error(500, "save failed".toResponseBody())

            val viewModel = ConfigViewModel()
            awaitModel(viewModel, "initial")
            viewModel.toggleYamlMode()
            withTimeout(5_000) { viewModel.uiState.first { it.yamlText == "model: initial" } }
            viewModel.loadAll()
            withTimeout(5_000) { oldLoadStarted.await() }
            assertTrue(viewModel.uiState.value.isLoading)

            viewModel.setYamlText("model: submitted")
            viewModel.saveYamlConfig()
            withTimeout(5_000) { viewModel.uiState.first { !it.yamlIsSaving } }

            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals("model: submitted", viewModel.uiState.value.yamlText)
            releaseOldLoad.complete(Unit)
            Unit
        }

    @Test
    fun `invalid field draft survives successful refresh and continues to block save`() =
        runBlocking {
            val configCalls = AtomicInteger()
            stubStableLoadEndpoints()
            coEvery { mockApi.getConfig() } coAnswers {
                configResponse(if (configCalls.incrementAndGet() == 1) "initial" else "refreshed")
            }

            val viewModel = ConfigViewModel()
            awaitModel(viewModel, "initial")
            viewModel.setFieldDraft("model", "unfinished-", isValid = false)

            viewModel.loadAll()
            awaitModel(viewModel, "refreshed")

            assertEquals(ConfigFieldDraft("unfinished-", isValid = false), viewModel.uiState.value.fieldDrafts["model"])
            assertEquals(setOf("model"), viewModel.uiState.value.invalidKeys)
            viewModel.updateField("model", JsonPrimitive("must-not-save"))
            viewModel.saveConfig()
            coVerify(exactly = 0) { mockApi.updateConfig(any()) }
        }

    @Test
    fun `valid text preserving draft and matching pending change survive successful refresh`() =
        runBlocking {
            val configCalls = AtomicInteger()
            stubStableLoadEndpoints()
            coEvery { mockApi.getConfig() } coAnswers {
                configResponse(if (configCalls.incrementAndGet() == 1) "initial" else "server-refresh")
            }

            val viewModel = ConfigViewModel()
            awaitModel(viewModel, "initial")
            viewModel.setFieldDraft("model", "draft text  ", isValid = true)
            viewModel.updateField("model", JsonPrimitive("draft text"))

            viewModel.loadAll()
            withTimeout(5_000) { viewModel.uiState.first { !it.isLoading && configCalls.get() >= 2 } }

            assertEquals(ConfigFieldDraft("draft text  ", isValid = true), viewModel.uiState.value.fieldDrafts["model"])
            assertEquals("draft text", viewModel.uiState.value.modelValue())
            assertEquals(setOf("model"), viewModel.uiState.value.modifiedKeys)
            assertTrue(viewModel.uiState.value.invalidKeys.isEmpty())
        }

    @Test
    fun `refresh prunes draft validation and pending change for removed schema field`() =
        runBlocking {
            val useRemovedSchema = AtomicBoolean(false)
            stubStableLoadEndpoints()
            coEvery { mockApi.getConfig() } returns configResponse("initial")
            coEvery { mockApi.getConfigSchema() } coAnswers {
                Response.success(
                    if (useRemovedSchema.get()) {
                        ConfigSchemaResponse(fields = emptyMap(), category_order = emptyList())
                    } else {
                        schema()
                    },
                )
            }

            val viewModel = ConfigViewModel()
            awaitModel(viewModel, "initial")
            viewModel.setFieldDraft("model", "invalid", isValid = false)
            viewModel.updateField("model", JsonPrimitive("pending"))

            useRemovedSchema.set(true)
            viewModel.loadAll()
            withTimeout(5_000) { viewModel.uiState.first { !it.isLoading && it.schema?.fields?.isEmpty() == true } }

            assertTrue(viewModel.uiState.value.fieldDrafts.isEmpty())
            assertTrue(viewModel.uiState.value.invalidKeys.isEmpty())
            assertTrue(viewModel.uiState.value.modifiedKeys.isEmpty())
            assertEquals("initial", viewModel.uiState.value.modelValue())
        }

    @Test
    fun `nested defaults support field and category resets without inventing missing defaults`() =
        runBlocking {
            val providerDefault = JsonObject(mapOf("custom" to JsonPrimitive("https://default.test")))
            val toolsetsDefault = JsonArray(listOf(JsonPrimitive("terminal"), JsonPrimitive("web")))
            val schema =
                ConfigSchemaResponse(
                    fields =
                        mapOf(
                            "terminal.backend" to SchemaField(type = "string", category = "runtime"),
                            "providers" to SchemaField(type = "object", category = "runtime"),
                            "toolsets" to SchemaField(type = "list", category = "runtime"),
                            "terminal.missing" to SchemaField(type = "string", category = "runtime"),
                        ),
                    category_order = listOf("runtime"),
                )
            coEvery { mockApi.getConfigSchema() } returns Response.success(schema)
            coEvery { mockApi.getConfig() } returns
                Response.success(
                    mapOf(
                        "terminal" to
                            JsonObject(
                                mapOf(
                                    "backend" to JsonPrimitive("docker"),
                                    "missing" to JsonPrimitive("keep-me"),
                                ),
                            ),
                        "providers" to JsonObject(mapOf("custom" to JsonPrimitive("https://current.test"))),
                        "toolsets" to JsonArray(listOf(JsonPrimitive("terminal"))),
                    ),
                )
            coEvery { mockApi.getConfigDefaults() } returns
                Response.success(
                    mapOf(
                        "terminal" to JsonObject(mapOf("backend" to JsonPrimitive("local"))),
                        "providers" to providerDefault,
                        "toolsets" to toolsetsDefault,
                    ),
                )
            coEvery { mockApi.getRawConfig() } returns Response.success(RawConfigResponse(path = "/tmp/config.yaml"))

            val viewModel = ConfigViewModel()
            withTimeout(5_000) { viewModel.uiState.first { !it.isLoading && it.defaults != null } }

            assertEquals(JsonPrimitive("local"), viewModel.uiState.value.defaults?.get("terminal.backend"))
            assertEquals(providerDefault, viewModel.uiState.value.defaults?.get("providers"))
            assertEquals(toolsetsDefault, viewModel.uiState.value.defaults?.get("toolsets"))
            assertFalse(viewModel.uiState.value.defaults.orEmpty().containsKey("terminal.missing"))

            viewModel.resetField("terminal.backend")
            assertEquals(JsonPrimitive("local"), viewModel.uiState.value.values?.get("terminal.backend"))
            assertTrue("terminal.backend" in viewModel.uiState.value.modifiedKeys)

            viewModel.resetCategoryToDefaults("runtime")
            assertEquals(providerDefault, viewModel.uiState.value.values?.get("providers"))
            assertEquals(toolsetsDefault, viewModel.uiState.value.values?.get("toolsets"))
            assertEquals(JsonPrimitive("keep-me"), viewModel.uiState.value.values?.get("terminal.missing"))
            assertFalse("terminal.missing" in viewModel.uiState.value.modifiedKeys)
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
