package com.m57.hermescontrol.ui.config

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ConfigSchemaResponse
import com.m57.hermescontrol.data.model.ConfigUpdateRequest
import com.m57.hermescontrol.data.model.RawConfigResponse
import com.m57.hermescontrol.data.model.UpdateRawConfigRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

data class ConfigUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    /** Flattened dot-path → value map (see [flattenConfig]). */
    val values: Map<String, JsonElement>? = null,
    val schema: ConfigSchemaResponse? = null,
    val defaults: Map<String, JsonElement>? = null,
    /** Config paths present but not covered by the schema (the "Other" tab). */
    val uncoveredPaths: List<String> = emptyList(),
    val path: String? = null,
    val yamlText: String? = null,
    val modifiedKeys: Set<String> = emptySet(),
    val invalidKeys: Set<String> = emptySet(),
    val activeCategory: String = "",
    val searchQuery: String = "",
    val yamlMode: Boolean = false,
    val yamlIsLoading: Boolean = false,
    val yamlIsSaving: Boolean = false,
    val yamlLoadError: String? = null,
    val errorMessage: String? = null,
    val toastMessage: ConfigUiText? = null,
)

data class ConfigUiText(
    @StringRes val resourceId: Int,
    val args: List<Any> = emptyList(),
)

internal sealed interface RawYamlLoadResult {
    data class Loaded(val yaml: String) : RawYamlLoadResult

    data class Error(val message: String) : RawYamlLoadResult
}

internal fun rawYamlLoadResult(response: RawConfigResponse): RawYamlLoadResult =
    response.yaml?.let(RawYamlLoadResult::Loaded)
        ?: RawYamlLoadResult.Error("Raw config response did not include YAML")

internal data class StructuredRefreshAfterYamlSave(
    val values: Map<String, JsonElement>?,
    val uncoveredPaths: List<String>? = null,
    val activeCategory: String? = null,
    val errorMessage: String?,
)

internal data class ReconciledStructuredConfig(
    val values: Map<String, JsonElement>,
    val uncoveredPaths: List<String>,
    val activeCategory: String,
)

internal fun reconcileStructuredConfig(
    config: Map<String, JsonElement>,
    schema: ConfigSchemaResponse,
    activeCategory: String,
): ReconciledStructuredConfig {
    val values = flattenConfig(config, schema.fields.keys)
    val uncoveredPaths = collectUncoveredPaths(values, schema.fields.keys)
    val categories =
        orderedCategories(
            categoryOrder = schema.category_order,
            schemaCategories = schema.fields.values.map { it.category ?: "general" },
            hasUncoveredPaths = uncoveredPaths.isNotEmpty(),
        )
    return ReconciledStructuredConfig(
        values = values,
        uncoveredPaths = uncoveredPaths,
        activeCategory = activeCategory.takeIf(categories::contains) ?: categories.firstOrNull().orEmpty(),
    )
}

internal fun structuredRefreshAfterYamlSave(
    result: NetworkResult<Map<String, JsonElement>>,
    schema: ConfigSchemaResponse,
    activeCategory: String,
): StructuredRefreshAfterYamlSave =
    when (result) {
        is NetworkResult.Success -> {
            val reconciled = reconcileStructuredConfig(result.data, schema, activeCategory)
            StructuredRefreshAfterYamlSave(
                values = reconciled.values,
                uncoveredPaths = reconciled.uncoveredPaths,
                activeCategory = reconciled.activeCategory,
                errorMessage = null,
            )
        }

        is NetworkResult.Failure ->
            StructuredRefreshAfterYamlSave(
                values = null,
                errorMessage = result.error.message,
            )
    }

class ConfigViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()

    private val pendingChanges = mutableMapOf<String, JsonElement>()
    private var loadJob: Job? = null
    private var loadGeneration = 0L

    init {
        loadAll()
    }

    fun loadAll() {
        val current = _uiState.value
        if (current.isSaving || current.yamlIsSaving) return
        val generation = ++loadGeneration
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                try {
                    coroutineScope {
                        val configDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getConfig() } }
                        val schemaDeferred =
                            async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getConfigSchema() } }
                        val defaultsDeferred =
                            async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getConfigDefaults() } }
                        val rawDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getRawConfig() } }

                        val configResult = configDeferred.await()
                        val schemaResult = schemaDeferred.await()
                        val defaultsResult = defaultsDeferred.await()
                        val rawResult = rawDeferred.await()

                        if (generation != loadGeneration) return@coroutineScope
                        if (
                            configResult is NetworkResult.Success &&
                            schemaResult is NetworkResult.Success
                        ) {
                            val schema = schemaResult.data
                            val reconciled =
                                reconcileStructuredConfig(
                                    configResult.data,
                                    schema,
                                    _uiState.value.activeCategory,
                                )
                            val values = reconciled.values
                            val defaults = (defaultsResult as? NetworkResult.Success)?.data
                            val path = (rawResult as? NetworkResult.Success)?.data?.path

                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    values = reapplyPendingChanges(values, pendingChanges),
                                    schema = schema,
                                    defaults = defaults,
                                    uncoveredPaths = reconciled.uncoveredPaths,
                                    path = path,
                                    activeCategory = reconciled.activeCategory,
                                    modifiedKeys = pendingChanges.keys.toSet(),
                                )
                            }
                        } else {
                            val errorMsg =
                                (
                                    (configResult as? NetworkResult.Failure)
                                        ?: (schemaResult as? NetworkResult.Failure)
                                )?.error?.message.orEmpty()
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    errorMessage = errorMsg,
                                    toastMessage = ConfigUiText(R.string.config_load_failed, listOf(errorMsg)),
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (generation != loadGeneration) return@launch
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = e.message.orEmpty(),
                            toastMessage = ConfigUiText(R.string.config_load_failed, listOf(e.message.orEmpty())),
                        )
                    }
                }
            }
    }

    fun updateField(
        key: String,
        value: JsonElement,
    ) {
        val state = _uiState.value
        if (!canEditConfigForm(state.yamlMode, state.isSaving, state.yamlIsSaving)) return
        pendingChanges[key] = value
        _uiState.update {
            it.copy(
                values = it.values?.toMutableMap()?.apply { this[key] = value },
                modifiedKeys = pendingChanges.keys.toSet(),
            )
        }
    }

    fun setFieldValidity(
        key: String,
        isValid: Boolean,
    ) {
        val state = _uiState.value
        if (!canEditConfigForm(state.yamlMode, state.isSaving, state.yamlIsSaving)) return
        _uiState.update {
            it.copy(invalidKeys = if (isValid) it.invalidKeys - key else it.invalidKeys + key)
        }
    }

    /** Reset ONE field to its default value (pending until Save). */
    fun resetField(key: String) {
        val state = _uiState.value
        if (!canEditConfigForm(state.yamlMode, state.isSaving, state.yamlIsSaving)) return
        val defaultVal = state.defaults?.get(key) ?: return
        pendingChanges[key] = defaultVal
        _uiState.update {
            it.copy(
                values = it.values?.toMutableMap()?.apply { this[key] = defaultVal },
                modifiedKeys = pendingChanges.keys.toSet(),
                invalidKeys = it.invalidKeys - key,
            )
        }
    }

    /** Clear a clearable field to blank (e.g. timezone → system default). */
    fun clearField(key: String) {
        val state = _uiState.value
        if (!canEditConfigForm(state.yamlMode, state.isSaving, state.yamlIsSaving)) return
        val blank = JsonPrimitive("")
        pendingChanges[key] = blank
        _uiState.update {
            it.copy(
                values = it.values?.toMutableMap()?.apply { this[key] = blank },
                modifiedKeys = pendingChanges.keys.toSet(),
                invalidKeys = it.invalidKeys - key,
            )
        }
    }

    fun setActiveCategory(category: String) {
        _uiState.update {
            if (!canEditConfigForm(it.yamlMode, it.isSaving, it.yamlIsSaving)) return@update it
            it.copy(activeCategory = category)
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update {
            if (!canEditConfigForm(it.yamlMode, it.isSaving, it.yamlIsSaving)) return@update it
            it.copy(searchQuery = query)
        }
    }

    fun toggleYamlMode() {
        val current = _uiState.value
        if (!canSwitchConfigMode(current.isSaving, current.yamlIsSaving)) return
        if (current.yamlMode) {
            _uiState.update { it.copy(yamlMode = false, yamlText = null, yamlLoadError = null) }
        } else {
            _uiState.update { it.copy(yamlMode = true) }
            loadYaml()
        }
    }

    fun loadYaml() {
        val current = _uiState.value
        if (!current.yamlMode || current.yamlIsLoading || current.yamlIsSaving) return
        _uiState.update { it.copy(yamlIsLoading = true, yamlText = null, yamlLoadError = null) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getRawConfig() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    when (val loadResult = rawYamlLoadResult(result.data)) {
                        is RawYamlLoadResult.Loaded -> {
                            _uiState.update {
                                it.copy(
                                    yamlIsLoading = false,
                                    yamlText = loadResult.yaml,
                                    yamlLoadError = null,
                                )
                            }
                        }

                        is RawYamlLoadResult.Error -> {
                            _uiState.update {
                                it.copy(
                                    yamlIsLoading = false,
                                    yamlText = null,
                                    yamlLoadError = loadResult.message,
                                    toastMessage =
                                        ConfigUiText(
                                            R.string.config_load_yaml_failed,
                                            listOf(loadResult.message),
                                        ),
                                )
                            }
                        }
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            yamlIsLoading = false,
                            yamlText = null,
                            yamlLoadError = result.error.message,
                            toastMessage =
                                ConfigUiText(
                                    R.string.config_load_yaml_failed,
                                    listOf(result.error.message),
                                ),
                        )
                    }
                }
            }
        }
    }

    fun setYamlText(text: String) {
        _uiState.update {
            if (isYamlDocumentEditable(it.yamlText, it.yamlIsLoading, it.yamlLoadError) && !it.yamlIsSaving) {
                it.copy(yamlText = text)
            } else {
                it
            }
        }
    }

    fun saveConfig() {
        val state = _uiState.value
        if (state.yamlMode || pendingChanges.isEmpty() || state.isSaving || state.invalidKeys.isNotEmpty()) return
        val submitted = pendingChanges.toMap()
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val changeset = nestConfigChanges(submitted)
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.updateConfig(
                            ConfigUpdateRequest(config = changeset),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    acknowledgeSubmittedChanges(pendingChanges, submitted)
                    val configResult =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getConfig() }
                        }
                    val refreshed =
                        (configResult as? NetworkResult.Success)?.data?.let {
                            flattenConfig(it, uiState.value.schema?.fields?.keys ?: emptySet())
                        }
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            values =
                                refreshed?.let {
                                        server ->
                                    reapplyPendingChanges(server, pendingChanges)
                                } ?: it.values,
                            modifiedKeys = pendingChanges.keys.toSet(),
                            toastMessage = ConfigUiText(R.string.config_saved),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            toastMessage = ConfigUiText(R.string.config_save_failed, listOf(result.error.message)),
                        )
                    }
                }
            }
        }
    }

    fun saveYamlConfig() {
        val state = _uiState.value
        if (
            !canSaveYamlDocument(
                yamlMode = state.yamlMode,
                yamlText = state.yamlText,
                isLoading = state.yamlIsLoading,
                isSaving = state.yamlIsSaving,
                loadError = state.yamlLoadError,
            )
        ) {
            return
        }
        val yamlText = state.yamlText ?: return
        _uiState.update { it.copy(yamlIsSaving = true) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.updateRawConfig(
                            UpdateRawConfigRequest(yaml_text = yamlText),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    // Raw YAML is authoritative; discard stale form edits only after success.
                    pendingChanges.clear()
                    val configResult =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getConfig() }
                        }
                    val current = uiState.value
                    val structuredRefresh =
                        current.schema?.let { schema ->
                            structuredRefreshAfterYamlSave(configResult, schema, current.activeCategory)
                        } ?: StructuredRefreshAfterYamlSave(
                            values = null,
                            errorMessage = "Config schema unavailable after YAML save",
                        )
                    _uiState.update {
                        it.copy(
                            yamlIsSaving = false,
                            values = structuredRefresh.values,
                            uncoveredPaths = structuredRefresh.uncoveredPaths ?: it.uncoveredPaths,
                            activeCategory = structuredRefresh.activeCategory ?: it.activeCategory,
                            modifiedKeys = emptySet(),
                            invalidKeys = emptySet(),
                            errorMessage = structuredRefresh.errorMessage,
                            toastMessage = ConfigUiText(R.string.config_yaml_saved),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            yamlIsSaving = false,
                            toastMessage = ConfigUiText(R.string.config_save_yaml_failed, listOf(result.error.message)),
                        )
                    }
                }
            }
        }
    }

    fun resetCategoryToDefaults(category: String) {
        val state = _uiState.value
        if (!canEditConfigForm(state.yamlMode, state.isSaving, state.yamlIsSaving)) return
        val schema = state.schema ?: return
        val defaults = state.defaults ?: return

        val categoryFields =
            schema.fields.filter { (_, field) ->
                (field.category ?: "general") == category
            }

        var count = 0
        val replacedKeys = mutableSetOf<String>()
        val updatedValues = state.values?.toMutableMap()
        for ((key, _) in categoryFields) {
            val defaultVal = defaults[key]
            if (defaultVal != null) {
                pendingChanges[key] = defaultVal
                updatedValues?.set(key, defaultVal)
                replacedKeys += key
                count++
            }
        }
        _uiState.update {
            it.copy(
                values = updatedValues ?: it.values,
                modifiedKeys = pendingChanges.keys.toSet(),
                invalidKeys = invalidKeysAfterReset(it.invalidKeys, replacedKeys),
            )
        }

        if (count > 0) {
            _uiState.update {
                it.copy(toastMessage = ConfigUiText(R.string.config_reset_count, listOf(count)))
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
