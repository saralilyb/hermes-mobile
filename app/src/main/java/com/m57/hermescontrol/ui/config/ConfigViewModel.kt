package com.m57.hermescontrol.ui.config

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ConfigSchemaResponse
import com.m57.hermescontrol.data.model.ConfigUpdateRequest
import com.m57.hermescontrol.data.model.UpdateRawConfigRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.Dispatchers
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
    val activeCategory: String = "",
    val searchQuery: String = "",
    val yamlMode: Boolean = false,
    val yamlIsLoading: Boolean = false,
    val yamlIsSaving: Boolean = false,
    val errorMessage: String? = null,
    val toastMessage: ConfigUiText? = null,
)

data class ConfigUiText(
    @StringRes val resourceId: Int,
    val args: List<Any> = emptyList(),
)

class ConfigViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()

    private val pendingChanges = mutableMapOf<String, JsonElement>()

    init {
        loadAll()
    }

    fun loadAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                coroutineScope {
                    val configDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getConfig() } }
                    val schemaDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getConfigSchema() } }
                    val defaultsDeferred =
                        async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getConfigDefaults() } }
                    val rawDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getRawConfig() } }

                    val configResult = configDeferred.await()
                    val schemaResult = schemaDeferred.await()
                    val defaultsResult = defaultsDeferred.await()
                    val rawResult = rawDeferred.await()

                    if (configResult is NetworkResult.Success) {
                        val schema = (schemaResult as? NetworkResult.Success)?.data
                        val values = flattenConfig(configResult.data, schema?.fields?.keys ?: emptySet())
                        val defaults = (defaultsResult as? NetworkResult.Success)?.data
                        val path = (rawResult as? NetworkResult.Success)?.data?.path
                        val categories =
                            orderedCategories(
                                categoryOrder = schema?.category_order.orEmpty(),
                                schemaCategories = schema?.fields?.values?.map { it.category ?: "general" }.orEmpty(),
                                hasUncoveredPaths =
                                    collectUncoveredPaths(values, schema?.fields?.keys ?: emptySet()).isNotEmpty(),
                            )

                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                values = values,
                                schema = schema,
                                defaults = defaults,
                                uncoveredPaths =
                                    collectUncoveredPaths(
                                        values,
                                        schema?.fields?.keys ?: emptySet(),
                                    ),
                                path = path,
                                activeCategory = categories.firstOrNull().orEmpty(),
                            )
                        }
                    } else {
                        val errorMsg = (configResult as? NetworkResult.Failure)?.error?.message ?: "Unknown error"
                        _uiState.update {
                            it.copy(isLoading = false, errorMessage = "Failed to load config: $errorMsg")
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Failed to load config: ${e.message}")
                }
            }
        }
    }

    fun updateField(
        key: String,
        value: JsonElement,
    ) {
        pendingChanges[key] = value
        _uiState.update {
            it.copy(
                values = it.values?.toMutableMap()?.apply { this[key] = value },
                modifiedKeys = pendingChanges.keys.toSet(),
            )
        }
    }

    /** Reset ONE field to its default value (pending until Save). */
    fun resetField(key: String) {
        val state = _uiState.value
        val defaultVal = state.defaults?.get(key) ?: return
        pendingChanges[key] = defaultVal
        _uiState.update {
            it.copy(
                values = it.values?.toMutableMap()?.apply { this[key] = defaultVal },
                modifiedKeys = pendingChanges.keys.toSet(),
            )
        }
    }

    /** Clear a clearable field to blank (e.g. timezone → system default). */
    fun clearField(key: String) {
        val blank = JsonPrimitive("")
        pendingChanges[key] = blank
        _uiState.update {
            it.copy(
                values = it.values?.toMutableMap()?.apply { this[key] = blank },
                modifiedKeys = pendingChanges.keys.toSet(),
            )
        }
    }

    fun setActiveCategory(category: String) {
        _uiState.update { it.copy(activeCategory = category) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun toggleYamlMode() {
        val current = _uiState.value
        if (current.yamlMode) {
            _uiState.update { it.copy(yamlMode = false, yamlText = null) }
        } else {
            _uiState.update { it.copy(yamlMode = true, yamlIsLoading = true) }
            viewModelScope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        safeApiCall { ApiClient.hermesApi.getRawConfig() }
                    }
                when (result) {
                    is NetworkResult.Success -> {
                        _uiState.update {
                            it.copy(
                                yamlIsLoading = false,
                                yamlText = result.data.yaml ?: "",
                            )
                        }
                    }

                    is NetworkResult.Failure -> {
                        _uiState.update {
                            it.copy(
                                yamlIsLoading = false,
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
    }

    fun setYamlText(text: String) {
        _uiState.update { it.copy(yamlText = text) }
    }

    fun saveConfig() {
        if (pendingChanges.isEmpty()) return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val changeset = nestConfigChanges(pendingChanges)
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
                    pendingChanges.clear()
                    val configResult =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getConfig() }
                        }
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            values =
                                (configResult as? NetworkResult.Success)?.data?.let {
                                    flattenConfig(it, uiState.value.schema?.fields?.keys ?: emptySet())
                                }
                                    ?: it.values,
                            modifiedKeys = emptySet(),
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
        val yamlText = _uiState.value.yamlText ?: return
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
                    val configResult =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getConfig() }
                        }
                    _uiState.update {
                        it.copy(
                            yamlIsSaving = false,
                            values =
                                (configResult as? NetworkResult.Success)?.data?.let {
                                    flattenConfig(it, uiState.value.schema?.fields?.keys ?: emptySet())
                                }
                                    ?: it.values,
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
        val schema = state.schema ?: return
        val defaults = state.defaults ?: return

        val categoryFields =
            schema.fields.filter { (_, field) ->
                (field.category ?: "general") == category
            }

        var count = 0
        val updatedValues = state.values?.toMutableMap()
        for ((key, _) in categoryFields) {
            val defaultVal = defaults[key]
            if (defaultVal != null) {
                pendingChanges[key] = defaultVal
                updatedValues?.set(key, defaultVal)
                count++
            }
        }
        _uiState.update {
            it.copy(
                values = updatedValues ?: it.values,
                modifiedKeys = pendingChanges.keys.toSet(),
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
