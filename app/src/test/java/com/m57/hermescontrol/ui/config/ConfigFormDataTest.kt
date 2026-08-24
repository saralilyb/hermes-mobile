package com.m57.hermescontrol.ui.config

import com.m57.hermescontrol.data.model.ConfigSchemaResponse
import com.m57.hermescontrol.data.model.RawConfigResponse
import com.m57.hermescontrol.data.model.SchemaField
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the config form's data layer: flattening the nested
 * config response into dot-paths, "Other" path discovery, and the search
 * matcher (which must cover keys, labels, descriptions, categories, CURRENT
 * VALUES and select options — the value/option coverage was missing before
 * the redo).
 */
class ConfigFormDataTest {
    // ── flattenConfig ──────────────────────────────────────────────────────

    @Test
    fun `flattenConfig walks nested objects into dot paths`() {
        val nested =
            JsonObject(
                mapOf(
                    "model" to JsonPrimitive("gpt-4o"),
                    "terminal" to
                        JsonObject(
                            mapOf(
                                "backend" to JsonPrimitive("local"),
                                "timeout" to JsonPrimitive(30),
                            ),
                        ),
                ),
            )
        val flat = flattenConfig(nested)
        assertEquals(
            mapOf(
                "model" to JsonPrimitive("gpt-4o"),
                "terminal.backend" to JsonPrimitive("local"),
                "terminal.timeout" to JsonPrimitive(30),
            ),
            flat,
        )
    }

    @Test
    fun `flattenConfig keeps arrays and nulls as leaf values`() {
        val nested =
            JsonObject(
                mapOf(
                    "toolsets" to JsonArray(listOf(JsonPrimitive("terminal"), JsonPrimitive("web"))),
                    "unset" to JsonNull,
                ),
            )
        val flat = flattenConfig(nested)
        assertEquals(2, flat.size)
        assertEquals(
            JsonArray(listOf(JsonPrimitive("terminal"), JsonPrimitive("web"))),
            flat["toolsets"],
        )
        assertEquals(JsonNull, flat["unset"])
    }

    @Test
    fun `flattenConfig on already-flat map is identity`() {
        val flat = mapOf("a.b" to JsonPrimitive(1), "c" to JsonPrimitive("x"))
        assertEquals(flat, flattenConfig(flat))
    }

    @Test
    fun `flattenConfig preserves schema object fields as editable values`() {
        val nested =
            mapOf(
                "providers" to
                    JsonObject(
                        mapOf(
                            "custom" to JsonObject(mapOf("url" to JsonPrimitive("https://example.test"))),
                        ),
                    ),
            )

        assertEquals(
            nested["providers"],
            flattenConfig(nested, terminalPaths = setOf("providers"))["providers"],
        )
    }

    @Test
    fun `flattenConfig gives nested defaults the same schema terminal semantics as current values`() {
        val nestedDefaults =
            mapOf(
                "terminal" to JsonObject(mapOf("backend" to JsonPrimitive("local"))),
                "providers" to JsonObject(mapOf("custom" to JsonPrimitive("https://example.test"))),
                "toolsets" to JsonArray(listOf(JsonPrimitive("terminal"), JsonPrimitive("web"))),
            )

        assertEquals(
            mapOf(
                "terminal.backend" to JsonPrimitive("local"),
                "providers" to nestedDefaults.getValue("providers"),
                "toolsets" to nestedDefaults.getValue("toolsets"),
            ),
            flattenConfig(nestedDefaults, terminalPaths = setOf("terminal.backend", "providers", "toolsets")),
        )
    }

    @Test
    fun `nestConfigChanges round trips flat scalar and object values`() {
        val flat =
            linkedMapOf(
                "terminal.backend" to JsonPrimitive("docker"),
                "providers" to
                    JsonObject(
                        mapOf("custom" to JsonObject(mapOf("url" to JsonPrimitive("https://example.test")))),
                    ),
            )

        assertEquals(
            mapOf(
                "terminal" to JsonObject(mapOf("backend" to JsonPrimitive("docker"))),
                "providers" to flat.getValue("providers"),
            ),
            nestConfigChanges(flat),
        )
    }

    @Test
    fun `flatten and nest preserve a schema object editor value`() {
        val nested =
            mapOf(
                "providers" to
                    JsonObject(
                        mapOf("custom" to JsonObject(mapOf("enabled" to JsonPrimitive(true)))),
                    ),
            )

        val flat = flattenConfig(nested, terminalPaths = setOf("providers"))

        assertEquals(nested, nestConfigChanges(flat))
    }

    // ── isCoveredBySchema ──────────────────────────────────────────────────

    @Test
    fun `isCoveredBySchema matches exact key and ancestors`() {
        val schema = setOf("tts.provider", "model")
        assertTrue(isCoveredBySchema("tts.provider", schema))
        assertTrue(isCoveredBySchema("model", schema))
        // Ancestor relationship: key below a schema key is covered
        assertTrue(isCoveredBySchema("tts.provider.x", schema))
        assertFalse(isCoveredBySchema("tts.other", schema))
        // Prefix boundary: "tts.providers" must NOT match "tts.provider"
        assertFalse(isCoveredBySchema("tts.providers.custom", schema))
    }

    // ── collectUncoveredPaths ──────────────────────────────────────────────

    @Test
    fun `collectUncoveredPaths finds only schema-orphaned leaves`() {
        val flat =
            mapOf(
                "model" to JsonPrimitive("x"),
                "tts.provider" to JsonPrimitive("edge"),
                "tts.providers.custom.voice" to JsonPrimitive("amy"),
                "dashboard.host" to JsonPrimitive("0.0.0.0"),
            )
        val schema = setOf("model", "tts.provider")
        assertEquals(
            listOf("dashboard.host", "tts.providers.custom.voice"),
            collectUncoveredPaths(flat, schema),
        )
    }

    // ── rowMatchesQuery ────────────────────────────────────────────────────

    private fun row(
        key: String,
        field: SchemaField?,
        value: kotlinx.serialization.json.JsonElement? = null,
    ) = ConfigRow(key = key, field = field, value = value)

    @Test
    fun `search matches dot key and human label`() {
        val r = row("model_context_length", SchemaField(type = "number"), JsonPrimitive(8192))
        assertTrue(rowMatchesQuery(r, "model_context"))
        assertTrue(rowMatchesQuery(r, "context length"))
    }

    @Test
    fun `search matches description and category`() {
        val r =
            row(
                "stt.local.model",
                SchemaField(type = "select", description = "Local faster-whisper model size", category = "stt"),
            )
        assertTrue(rowMatchesQuery(r, "whisper"))
        assertTrue(rowMatchesQuery(r, "stt"))
    }

    @Test
    fun `search matches current value`() {
        val r = row("model", SchemaField(type = "string"), JsonPrimitive("anthropic/claude-sonnet-4.6"))
        assertTrue(rowMatchesQuery(r, "claude-sonnet"))
        assertFalse(rowMatchesQuery(r, "gpt-4o"))
    }

    @Test
    fun `search matches select options`() {
        val r =
            row(
                "terminal.backend",
                SchemaField(type = "select", options = listOf("local", "docker", "ssh", "modal", "daytona")),
                JsonPrimitive("local"),
            )
        assertTrue(rowMatchesQuery(r, "daytona"))
        assertTrue(rowMatchesQuery(r, "docker"))
    }

    @Test
    fun `search matches uncovered paths by key and value`() {
        val r = row("plugins.myplugin.enabled", field = null, value = JsonPrimitive("true"))
        assertTrue(rowMatchesQuery(r, "myplugin"))
        assertTrue(rowMatchesQuery(r, "true"))
    }

    @Test
    fun `search is case-insensitive and blank query matches all`() {
        val r = row("Model", SchemaField(type = "string"), JsonPrimitive("OpenAI"))
        assertTrue(rowMatchesQuery(r, "openai"))
        assertTrue(rowMatchesQuery(r, "MODEL"))
        assertTrue(rowMatchesQuery(r, ""))
        assertTrue(rowMatchesQuery(r, "   "))
    }

    @Test
    fun `search does not match unrelated keys`() {
        val r = row("memory.provider", SchemaField(type = "select"), JsonPrimitive("builtin"))
        assertFalse(rowMatchesQuery(r, "terminal"))
    }

    // ── jsonText ───────────────────────────────────────────────────────────

    @Test
    fun `jsonText renders primitives, objects and arrays`() {
        assertEquals("42", jsonText(JsonPrimitive(42)))
        assertEquals("hello", jsonText(JsonPrimitive("hello")))
        assertEquals("{\"a\":1}", jsonText(JsonObject(mapOf("a" to JsonPrimitive(1)))))
        assertEquals("[1,2]", jsonText(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))))
    }

    @Test
    fun `orderedCategories appends omitted categories and Other deterministically`() {
        assertEquals(
            listOf("models", "general", "tools"),
            orderedCategories(listOf("models", "missing", "models"), listOf("tools", "general", "models"), true)
                .filterNot(::isSyntheticOtherCategory),
        )
        assertEquals(listOf("general", "tools"), orderedCategories(emptyList(), listOf("tools", "general"), false))
    }

    @Test
    fun `category reset is hidden for search and synthetic Other`() {
        val otherCategory = orderedCategories(emptyList(), emptyList(), true).single()

        assertTrue(shouldShowCategoryReset("general", isSearching = false))
        assertFalse(shouldShowCategoryReset("general", isSearching = true))
        assertFalse(shouldShowCategoryReset(otherCategory, isSearching = false))
    }

    @Test
    fun `parseFiniteNumber rejects invalid and nonfinite intermediate text`() {
        assertEquals(JsonPrimitive(42), parseFiniteNumber("42"))
        assertEquals(JsonPrimitive(0.5), parseFiniteNumber("0.5"))
        assertEquals(null, parseFiniteNumber("-"))
        assertEquals(null, parseFiniteNumber("NaN"))
        assertEquals(null, parseFiniteNumber("Infinity"))
        assertEquals(null, parseFiniteNumber("1e999"))
    }

    @Test
    fun `parseFiniteNumber preserves long precision boundaries`() {
        assertEquals(JsonPrimitive(Long.MAX_VALUE), parseFiniteNumber(Long.MAX_VALUE.toString()))
        assertEquals(JsonPrimitive(Long.MIN_VALUE), parseFiniteNumber(Long.MIN_VALUE.toString()))
        assertEquals(JsonPrimitive(9_007_199_254_740_993L), parseFiniteNumber("9007199254740993"))
    }

    @Test
    fun `refresh reapplies pending edits and save acknowledgement preserves newer edits`() {
        val pending: MutableMap<String, JsonElement> =
            mutableMapOf("model" to JsonPrimitive("submitted"), "theme" to JsonPrimitive("dark"))
        val submitted = pending.toMap()
        pending["model"] = JsonPrimitive("newer")

        acknowledgeSubmittedChanges(pending, submitted)

        assertEquals(mapOf("model" to JsonPrimitive("newer")), pending)
        assertEquals(
            mapOf("model" to JsonPrimitive("newer"), "server" to JsonPrimitive(true)),
            reapplyPendingChanges(mapOf("model" to JsonPrimitive("server"), "server" to JsonPrimitive(true)), pending),
        )
    }

    @Test
    fun `synthetic Other category cannot collide with server Other category`() {
        val categories = orderedCategories(listOf("Other"), listOf("Other"), hasUncoveredPaths = true)
        assertEquals("Other", categories.first())
        assertTrue(isSyntheticOtherCategory(categories.last()))
        assertFalse(categories.first() == categories.last())
    }

    @Test
    fun `yaml document requires a successful completed load before editing`() {
        assertFalse(isYamlDocumentEditable(null, isLoading = false, loadError = null))
        assertFalse(isYamlDocumentEditable(null, isLoading = false, loadError = "GET failed"))
        assertFalse(isYamlDocumentEditable("loaded", isLoading = true, loadError = null))
        assertTrue(isYamlDocumentEditable("", isLoading = false, loadError = null))
        assertFalse(canSaveYamlDocument(true, null, false, false, "GET failed"))
        assertFalse(canSaveYamlDocument(false, "loaded", false, false, null))
        assertTrue(canSaveYamlDocument(true, "loaded", false, false, null))
    }

    @Test
    fun `raw YAML load mapper distinguishes empty YAML from a null payload`() {
        assertEquals(RawYamlLoadResult.Loaded(""), rawYamlLoadResult(RawConfigResponse(yaml = "")))

        val malformed = rawYamlLoadResult(RawConfigResponse(yaml = null))
        assertTrue(malformed is RawYamlLoadResult.Error)
        assertTrue((malformed as RawYamlLoadResult.Error).message.isNotBlank())
    }

    @Test
    fun `successful YAML save keeps structured form unavailable when refresh fails`() {
        val outcome =
            structuredRefreshAfterYamlSave(
                NetworkResult.Failure(NetworkError.Http(503, "refresh unavailable")),
                schema = testSchema(),
                activeCategory = "general",
            )

        assertEquals(null, outcome.values)
        assertEquals("refresh unavailable", outcome.errorMessage)
    }

    @Test
    fun `successful YAML save exposes only successfully refreshed structured values`() {
        val outcome =
            structuredRefreshAfterYamlSave(
                NetworkResult.Success(
                    mapOf(
                        "model" to JsonPrimitive("server-authoritative"),
                        "terminal" to JsonObject(mapOf("backend" to JsonPrimitive("docker"))),
                    ),
                ),
                schema = testSchema("model", "terminal.backend"),
                activeCategory = "general",
            )

        assertEquals(
            mapOf(
                "model" to JsonPrimitive("server-authoritative"),
                "terminal.backend" to JsonPrimitive("docker"),
            ),
            outcome.values,
        )
        assertEquals(null, outcome.errorMessage)
    }

    @Test
    fun `YAML refresh adds and removes authoritative Other paths`() {
        val schema = testSchema()
        val withOther =
            reconcileStructuredConfig(
                mapOf(
                    "model" to JsonPrimitive("server"),
                    "plugin" to JsonObject(mapOf("enabled" to JsonPrimitive(true))),
                ),
                schema,
                activeCategory = "general",
            )
        assertEquals(listOf("plugin.enabled"), withOther.uncoveredPaths)

        val withoutOther =
            reconcileStructuredConfig(
                mapOf("model" to JsonPrimitive("server")),
                schema,
                activeCategory = withOther.activeCategory,
            )
        assertTrue(withoutOther.uncoveredPaths.isEmpty())
    }

    @Test
    fun `YAML refresh falls back when active Other category disappears`() {
        val schema = testSchema()
        val withOther =
            reconcileStructuredConfig(
                mapOf("model" to JsonPrimitive("server"), "unknown" to JsonPrimitive(true)),
                schema,
                activeCategory = "general",
            )
        val otherCategory = orderedCategories(emptyList(), emptyList(), true).single()

        val refreshed =
            reconcileStructuredConfig(
                mapOf("model" to JsonPrimitive("server")),
                schema,
                activeCategory = otherCategory,
            )

        assertEquals(listOf("unknown"), withOther.uncoveredPaths)
        assertEquals("general", refreshed.activeCategory)
    }

    private fun testSchema(vararg paths: String): ConfigSchemaResponse {
        val effectivePaths = paths.ifEmpty { arrayOf("model") }
        return ConfigSchemaResponse(
            fields = effectivePaths.associateWith { SchemaField(type = "string", category = "general") },
            category_order = listOf("general"),
        )
    }

    @Test
    fun `save fencing blocks cross-mode transitions and mutations`() {
        assertFalse(canSwitchConfigMode(isFormSaving = false, isYamlSaving = true))
        assertFalse(canSwitchConfigMode(isFormSaving = true, isYamlSaving = false))
        assertTrue(canSwitchConfigMode(isFormSaving = false, isYamlSaving = false))
        assertFalse(canEditConfigForm(yamlMode = true, isFormSaving = false, isYamlSaving = false))
        assertFalse(canEditConfigForm(yamlMode = false, isFormSaving = false, isYamlSaving = true))
        assertTrue(canEditConfigForm(yamlMode = false, isFormSaving = false, isYamlSaving = false))
    }

    @Test
    fun `category reset clears validation only for replaced fields`() {
        assertEquals(
            setOf("other.invalid"),
            invalidKeysAfterReset(
                invalidKeys = setOf("category.invalid", "other.invalid"),
                replacedKeys = setOf("category.invalid", "category.valid"),
            ),
        )
    }

    @Test
    fun `invalid field draft survives unrelated viewport state changes until explicitly cleared`() {
        val invalid = ConfigUiState().withFieldDraft("terminal.timeout", "-", isValid = false)

        val afterRowLeavesViewport = invalid.copy(searchQuery = "unrelated")

        assertEquals(ConfigFieldDraft("-", isValid = false), afterRowLeavesViewport.fieldDrafts["terminal.timeout"])
        assertTrue(
            "invalid draft must continue to disable Save",
            "terminal.timeout" in afterRowLeavesViewport.invalidKeys,
        )
        val cleared = afterRowLeavesViewport.withoutFieldDraft("terminal.timeout")
        assertTrue(cleared.fieldDrafts.isEmpty())
        assertTrue(cleared.invalidKeys.isEmpty())
    }

    @Test
    fun `draft owner keeps exact valid editor text separate from committed value`() {
        val state =
            ConfigUiState(values = mapOf("temperature" to JsonPrimitive(42)))
                .withFieldDraft("temperature", "42.0", isValid = true)

        assertEquals("42.0", state.fieldDrafts.getValue("temperature").text)
        assertEquals(JsonPrimitive(42), state.values?.get("temperature"))
        assertFalse("temperature" in state.invalidKeys)
    }

    @Test
    fun `parseStructuredJson enforces object and list schema shapes`() {
        val objectValue = JsonObject(mapOf("enabled" to JsonPrimitive(true)))
        val listValue = JsonArray(listOf(JsonPrimitive("one")))
        assertEquals(objectValue, parseStructuredJson(objectValue.toString(), "object"))
        assertEquals(listValue, parseStructuredJson(listValue.toString(), "list"))
        assertEquals(null, parseStructuredJson(listValue.toString(), "object"))
        assertEquals(null, parseStructuredJson(objectValue.toString(), "list"))
        assertEquals(null, parseStructuredJson("{", "object"))
        assertEquals(null, parseStructuredJson("42", "list"))
    }
}
