package com.m57.hermescontrol.ui.config

import com.m57.hermescontrol.data.model.SchemaField
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Internal category ID that cannot collide with a server-provided category. */
private const val OTHER_CATEGORY_ID = "\u0000config-other"

fun isSyntheticOtherCategory(category: String): Boolean = category == OTHER_CATEGORY_ID

fun shouldShowCategoryReset(
    activeCategory: String,
    isSearching: Boolean,
): Boolean = !isSearching && !isSyntheticOtherCategory(activeCategory)

/**
 * One renderable row of the config form: a schema-driven field or an
 * uncovered ("Other") config path. All rows are FLAT dot-paths — the nested
 * config response is flattened once on load so the form does O(1) lookups.
 */
data class ConfigRow(
    val key: String,
    val field: SchemaField?,
    val value: JsonElement?,
) {
    /** Human label: last path segment, underscores → spaces. */
    val label: String =
        key.split(".").last().replace("_", " ").replaceFirstChar { it.uppercase() }

    /** Search/display text of the current value. */
    val valueText: String = value?.let(::jsonText) ?: ""

    val category: String = if (field == null) OTHER_CATEGORY_ID else field.category ?: "general"

    val isUncovered: Boolean = field == null
}

/**
 * Flatten a nested config map into dot-path → leaf-value pairs. Paths declared
 * as schema fields stay intact so object-valued editors receive the full JSON.
 */
fun flattenConfig(
    nested: Map<String, JsonElement>,
    terminalPaths: Set<String> = emptySet(),
): Map<String, JsonElement> {
    val flat = mutableMapOf<String, JsonElement>()

    fun walk(
        prefix: String,
        map: Map<String, JsonElement>,
    ) {
        for ((key, value) in map) {
            val dotPath = if (prefix.isEmpty()) key else "$prefix.$key"
            if (value is JsonObject && dotPath !in terminalPaths) {
                walk(dotPath, value)
            } else {
                flat[dotPath] = value
            }
        }
    }
    walk("", nested)
    return flat
}

/** Nest a dot-path changeset into the JSON shape expected by the config API. */
fun nestConfigChanges(changes: Map<String, JsonElement>): Map<String, JsonElement> {
    val root = mutableMapOf<String, Any>()
    for ((dotPath, value) in changes) {
        val parts = dotPath.split(".")
        var current = root
        for (part in parts.dropLast(1)) {
            val existing = current[part]
            require(existing == null || existing is MutableMap<*, *>) {
                "Conflicting config paths in changeset: $dotPath"
            }
            @Suppress("UNCHECKED_CAST")
            current =
                (existing as? MutableMap<String, Any>)
                    ?: mutableMapOf<String, Any>().also { current[part] = it }
        }
        require(current[parts.last()] !is MutableMap<*, *>) {
            "Conflicting config paths in changeset: $dotPath"
        }
        current[parts.last()] = value
    }

    fun convert(map: Map<String, Any>): Map<String, JsonElement> =
        map.mapValues { (_, value) ->
            if (value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                JsonObject(convert(value as Map<String, Any>))
            } else {
                value as JsonElement
            }
        }

    return convert(root)
}

/** True when [dotPath] is a schema key or lives under one (ancestor-or-self). */
fun isCoveredBySchema(
    dotPath: String,
    schemaKeys: Set<String>,
): Boolean = schemaKeys.any { dotPath == it || dotPath.startsWith("$it.") }

/** Flat dot-paths present in config but not covered by the schema (sorted). */
fun collectUncoveredPaths(
    flatValues: Map<String, JsonElement>,
    schemaKeys: Set<String>,
): List<String> =
    flatValues.keys
        .filterNot { isCoveredBySchema(it, schemaKeys) }
        .sorted()

/** Complete, deterministic category order, even when the API order is partial. */
fun orderedCategories(
    categoryOrder: List<String>,
    schemaCategories: Collection<String>,
    hasUncoveredPaths: Boolean,
): List<String> {
    val available = schemaCategories.toSet()
    val ordered = categoryOrder.filter { it in available }.distinct().toMutableList()
    ordered += (available - ordered.toSet()).sorted()
    if (hasUncoveredPaths) ordered += OTHER_CATEGORY_ID
    return ordered
}

/** Parse only finite JSON numbers; invalid text remains editor-local state. */
fun parseFiniteNumber(text: String): JsonPrimitive? {
    text.toLongOrNull()?.let { return JsonPrimitive(it) }
    val value = text.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
    return if (value >= Long.MIN_VALUE && value <= Long.MAX_VALUE && value == value.toLong().toDouble()) {
        JsonPrimitive(value.toLong())
    } else {
        JsonPrimitive(value)
    }
}

/** Reapply form edits over a newly fetched server snapshot. */
fun reapplyPendingChanges(
    serverValues: Map<String, JsonElement>,
    pendingChanges: Map<String, JsonElement>,
): Map<String, JsonElement> = serverValues + pendingChanges

/** Remove only submitted entries that were not edited again while saving. */
fun acknowledgeSubmittedChanges(
    pendingChanges: MutableMap<String, JsonElement>,
    submitted: Map<String, JsonElement>,
) {
    submitted.forEach { (key, value) -> pendingChanges.remove(key, value) }
}

/** A raw document is editable only after its GET completed successfully. */
fun isYamlDocumentEditable(
    yamlText: String?,
    isLoading: Boolean,
    loadError: String?,
): Boolean = yamlText != null && !isLoading && loadError == null

fun canSaveYamlDocument(
    yamlMode: Boolean,
    yamlText: String?,
    isLoading: Boolean,
    isSaving: Boolean,
    loadError: String?,
): Boolean = yamlMode && !isSaving && isYamlDocumentEditable(yamlText, isLoading, loadError)

/** Prevent mode changes while either editor is committing its snapshot. */
fun canSwitchConfigMode(
    isFormSaving: Boolean,
    isYamlSaving: Boolean,
): Boolean = !isFormSaving && !isYamlSaving

/** Form mutations are accepted only while the form is the active, idle editor. */
fun canEditConfigForm(
    yamlMode: Boolean,
    isFormSaving: Boolean,
    isYamlSaving: Boolean,
): Boolean = !yamlMode && canSwitchConfigMode(isFormSaving, isYamlSaving)

/** Reset values are valid defaults, so their previous validation errors are stale. */
fun invalidKeysAfterReset(
    invalidKeys: Set<String>,
    replacedKeys: Set<String>,
): Set<String> = invalidKeys - replacedKeys

/** Parse JSON only when its shape matches the declared schema editor type. */
fun parseStructuredJson(
    text: String,
    schemaType: String,
): JsonElement? {
    if (text.isBlank()) return null
    val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) }.getOrNull()
    return when (schemaType) {
        "object" -> parsed as? JsonObject
        "list" -> parsed as? JsonArray
        else -> null
    }
}

/**
 * Match a row against a search query. Covers the key, human label, schema
 * description, category, the CURRENT VALUE, and every select option — so
 * searching "gpt-4o", "daytona" or "kittentts" finds the right field even
 * though none of them appear in a key or description.
 */
fun rowMatchesQuery(
    row: ConfigRow,
    query: String,
): Boolean {
    if (query.isBlank()) return true
    val q = query.lowercase()
    if (row.key.lowercase().contains(q)) return true
    if (row.label.lowercase().contains(q)) return true
    row.field?.let { field ->
        if (field.description?.lowercase()?.contains(q) == true) return true
        if (field.category?.lowercase()?.contains(q) == true) return true
        field.options?.let { options ->
            if (options.any { it.lowercase().contains(q) }) return true
        }
    }
    return row.valueText.lowercase().contains(q)
}

/** Stable text form of a JSON value for display and search. */
fun jsonText(value: JsonElement): String =
    when (value) {
        is JsonPrimitive -> value.content
        is JsonArray -> value.toString()
        is JsonObject -> value.toString()
    }
