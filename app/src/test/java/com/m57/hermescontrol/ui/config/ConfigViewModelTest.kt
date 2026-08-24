package com.m57.hermescontrol.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
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
    private lateinit var viewModelStore: ViewModelStore
    private val viewModelJobs = mutableListOf<Job>()
    private var viewModelKey = 0

    @Before
    fun setUp() {
        viewModelStore = ViewModelStore()
        viewModelJobs.clear()
        viewModelKey = 0
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
    }

    @After
    fun tearDown() =
        runBlocking {
            viewModelStore.clear()
            viewModelJobs.joinAll()
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

            val viewModel = createViewModel()
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

            val viewModel = createViewModel()
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

            val viewModel = createViewModel()
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

            val viewModel = createViewModel()
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

            val viewModel = createViewModel()
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

            val viewModel = createViewModel()
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

            val viewModel = createViewModel()
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
    fun `refresh prunes scalar draft and pending change when field becomes object`() =
        runBlocking {
            val objectEditor = AtomicBoolean(false)
            stubStableLoadEndpoints()
            coEvery { mockApi.getConfig() } returns
                Response.success(mapOf("model" to JsonPrimitive(7)))
            coEvery { mockApi.getConfigSchema() } coAnswers {
                Response.success(
                    ConfigSchemaResponse(
                        fields =
                            mapOf(
                                "model" to
                                    SchemaField(
                                        type = if (objectEditor.get()) "object" else "number",
                                        category = "general",
                                    ),
                            ),
                        category_order = listOf("general"),
                    ),
                )
            }

            val viewModel = createViewModel()
            withTimeout(5_000) { viewModel.uiState.first { !it.isLoading && it.schema != null } }
            viewModel.setFieldDraft("model", "8", isValid = true)
            viewModel.updateField("model", JsonPrimitive(8))

            objectEditor.set(true)
            viewModel.loadAll()
            withTimeout(5_000) {
                viewModel.uiState.first {
                    !it.isLoading && it.schema?.fields?.get("model")?.type == "object"
                }
            }

            assertTrue(viewModel.uiState.value.fieldDrafts.isEmpty())
            assertTrue(viewModel.uiState.value.modifiedKeys.isEmpty())
            assertEquals(JsonPrimitive(7), viewModel.uiState.value.values?.get("model"))
            viewModel.saveConfig()
            coVerify(exactly = 0) { mockApi.updateConfig(any()) }
        }

    @Test
    fun `reconciliation prunes select edit when options change`() {
        val result =
            reconcileEditorDrafts(
                pendingChanges = mapOf("model" to JsonPrimitive("old-option")),
                fieldDrafts = mapOf("model" to ConfigFieldDraft("old-option", isValid = true)),
                previousFields = mapOf("model" to SchemaField(type = "string", options = listOf("old-option"))),
                refreshedFields = mapOf("model" to SchemaField(type = "string", options = listOf("new-option"))),
            )

        assertTrue(result.pendingChanges.isEmpty())
        assertTrue(result.fieldDrafts.isEmpty())
        assertTrue(result.invalidKeys.isEmpty())
    }

    @Test
    fun `reconciliation treats null and empty options as the same editor contract in both directions`() {
        val pending =
            mapOf(
                "null-to-empty" to JsonPrimitive("pending-one"),
                "empty-to-null" to JsonPrimitive("pending-two"),
            )
        val drafts =
            mapOf(
                "null-to-empty" to ConfigFieldDraft("invalid draft", isValid = false),
                "empty-to-null" to ConfigFieldDraft("valid dirty draft", isValid = true),
            )

        val result =
            reconcileEditorDrafts(
                pendingChanges = pending,
                fieldDrafts = drafts,
                previousFields =
                    mapOf(
                        "null-to-empty" to SchemaField(type = "string", options = null),
                        "empty-to-null" to SchemaField(type = "string", options = emptyList()),
                    ),
                refreshedFields =
                    mapOf(
                        "null-to-empty" to SchemaField(type = "string", options = emptyList()),
                        "empty-to-null" to SchemaField(type = "string", options = null),
                    ),
            )

        assertEquals(pending, result.pendingChanges)
        assertEquals(pending.keys, result.pendingChanges.keys)
        assertEquals(drafts, result.fieldDrafts)
        assertEquals(setOf("null-to-empty"), result.invalidKeys)
    }

    @Test
    fun `reconciliation preserves edits across descriptive and action metadata changes`() {
        val pending = mapOf("model" to JsonPrimitive("draft"))
        val drafts = mapOf("model" to ConfigFieldDraft("draft  ", isValid = true))
        val result =
            reconcileEditorDrafts(
                pendingChanges = pending,
                fieldDrafts = drafts,
                previousFields =
                    mapOf(
                        "model" to
                            SchemaField(
                                type = "string",
                                description = "Old description",
                                category = "old-category",
                                searchable = false,
                                clearable = false,
                            ),
                    ),
                refreshedFields =
                    mapOf(
                        "model" to
                            SchemaField(
                                type = "string",
                                description = "New description",
                                category = "new-category",
                                searchable = true,
                                clearable = true,
                            ),
                    ),
            )

        assertEquals(pending, result.pendingChanges)
        assertEquals(drafts, result.fieldDrafts)
        assertTrue(result.invalidKeys.isEmpty())
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

            val viewModel = createViewModel()
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

    private fun createViewModel(): ConfigViewModel {
        val viewModel =
            ViewModelProvider(
                viewModelStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = ConfigViewModel() as T
                },
            )["config-${viewModelKey++}", ConfigViewModel::class.java]
        viewModelJobs += viewModel.viewModelScope.coroutineContext[Job]!!
        return viewModel
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
