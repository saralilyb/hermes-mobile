package com.m57.hermescontrol.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.UpdateRawConfigRequest
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConfigUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val path: String? = null,
    val yamlText: String? = null,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class ConfigViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()

    fun loadRawConfig() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getRawConfig()
                    }
                if (response.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            path = response.body()?.path,
                            yamlText = response.body()?.yaml,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load config: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load config: ${e.message}",
                    )
                }
            }
        }
    }

    fun saveRawConfig(yamlText: String) {
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.updateRawConfig(UpdateRawConfigRequest(yamlText))
                    }
                if (response.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            yamlText = yamlText,
                            toastMessage = "Configuration saved successfully",
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            toastMessage = "Failed to save config: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        toastMessage = "Failed to save config: ${e.message}",
                    )
                }
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
