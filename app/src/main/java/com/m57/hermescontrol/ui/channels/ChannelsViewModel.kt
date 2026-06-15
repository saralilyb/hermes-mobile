package com.m57.hermescontrol.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.MessagingPlatform
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChannelsUiState(
    val isLoading: Boolean = false,
    val platforms: List<MessagingPlatform> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class ChannelsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ChannelsUiState())
    val uiState: StateFlow<ChannelsUiState> = _uiState.asStateFlow()

    fun loadPlatforms() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getMessagingPlatforms()
                    }
                if (response.isSuccessful) {
                    _uiState.update { it.copy(isLoading = false, platforms = response.body().orEmpty()) }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load platforms: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Failed to load platforms: ${e.message}") }
            }
        }
    }

    fun configurePlatform(
        platformId: String,
        update: MessagingPlatformUpdate,
    ) {
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.configurePlatform(platformId, update)
                    }
                if (response.isSuccessful) {
                    _uiState.update { it.copy(toastMessage = "$platformId configured successfully") }
                    loadPlatforms()
                } else {
                    _uiState.update { it.copy(toastMessage = "Failed to configure platform: HTTP ${response.code()}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = "Failed to configure platform: ${e.message}") }
            }
        }
    }

    fun configurePlatform(
        platformId: String,
        config: Map<String, String>,
    ) {
        configurePlatform(platformId, MessagingPlatformUpdate(env = config))
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
