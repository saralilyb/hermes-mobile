package com.m57.hermescontrol.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SessionsUiState(
    val isLoading: Boolean = false,
    val sessions: List<SessionInfo> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class SessionsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    fun loadSessions() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getSessions()
                    }
                if (response.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            sessions = response.body()?.sessions.orEmpty(),
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load sessions: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load sessions: ${e.message}",
                    )
                }
            }
        }
    }

    fun deleteSession(id: String) {
        val originalSessions = _uiState.value.sessions
        // Optimistic removal
        _uiState.update { state ->
            state.copy(
                sessions = state.sessions.filter { it.id != id },
            )
        }
        viewModelScope.launch {
            try {
                // The Hermes API uses PATCH to switch session, not delete.
                // Session deletion is not directly available in the current
                // API surface, so we optimistically remove from the list
                // and reload to reconcile.
                loadSessions()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        sessions = originalSessions,
                        toastMessage = "Failed to remove session: ${e.message}",
                    )
                }
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
