package com.m57.hermescontrol.ui.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.EnvVarConfig
import com.m57.hermescontrol.data.model.EnvVarRevealRequest
import com.m57.hermescontrol.data.model.EnvVarUpdate
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class KeysUiState(
    val isLoading: Boolean = false,
    val envVars: Map<String, EnvVarConfig> = emptyMap(),
    val revealedValues: Map<String, String> = emptyMap(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class KeysViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(KeysUiState())
    val uiState: StateFlow<KeysUiState> = _uiState.asStateFlow()

    fun loadKeys(reveal: Boolean = false) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getEnvVars()
                    }
                if (response.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            envVars = response.body().orEmpty(),
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load keys: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Failed to load keys: ${e.message}") }
            }
        }
    }

    fun revealKey(key: String) {
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.revealEnvVar(EnvVarRevealRequest(key))
                    }
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    _uiState.update { state ->
                        state.copy(
                            revealedValues = state.revealedValues + (body.key to body.value),
                        )
                    }
                } else {
                    _uiState.update { it.copy(toastMessage = "Failed to reveal key: HTTP ${response.code()}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = "Failed to reveal key: ${e.message}") }
            }
        }
    }

    fun hideKey(key: String) {
        _uiState.update { state ->
            state.copy(
                revealedValues = state.revealedValues.toMutableMap().apply { remove(key) },
            )
        }
    }

    fun updateKey(
        key: String,
        value: String,
    ) {
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.updateEnvVar(EnvVarUpdate(key, value))
                    }
                if (response.isSuccessful) {
                    _uiState.update { it.copy(toastMessage = "Key updated successfully") }
                    loadKeys()
                } else {
                    _uiState.update { it.copy(toastMessage = "Failed to update key: HTTP ${response.code()}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = "Failed to update key: ${e.message}") }
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
