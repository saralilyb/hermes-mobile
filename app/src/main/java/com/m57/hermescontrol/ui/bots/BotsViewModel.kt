package com.m57.hermescontrol.ui.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MAX_ACTIVE_NOW_BOTS = 12
private const val PRESENCE_WINDOW_SECONDS = 90L
private const val LOAD_ERROR_MESSAGE = "Unable to load bots"

data class BotsUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val profiles: List<ProfileInfo> = emptyList(),
    val searchQuery: String = "",
    val showHidden: Boolean = false,
    val errorMessage: String? = null,
    val nowSeconds: Long = 0L,
) {
    val hasHiddenBots: Boolean
        get() = profiles.any { it.isHidden }

    val activeNowBots: List<ProfileInfo>
        get() =
            profiles
                .filter { it.isActiveAt(nowSeconds) }
                .sortedWith(compareByDescending<ProfileInfo> { lastActive(it) }.thenBy { it.name })
                .take(MAX_ACTIVE_NOW_BOTS)

    val displayProfiles: List<ProfileInfo>
        get() {
            val query = searchQuery.trim().lowercase()
            return profiles
                .filter { showHidden || !it.isHidden }
                .filter {
                    query.isBlank() || it.name.lowercase().contains(query) ||
                        it.effectiveTitle.lowercase().contains(query) ||
                        it.effectiveDescription.lowercase().contains(query)
                }
                .sortedWith(compareByDescending<ProfileInfo> { lastActive(it) }.thenBy { it.name })
        }
}

private fun lastActive(profile: ProfileInfo): Long =
    listOfNotNull(
        profile.worker_session?.last_active,
        profile.canonical_session?.last_active,
        profile.last_session?.last_active,
    ).maxOrNull() ?: Long.MIN_VALUE

internal fun ProfileInfo.isActiveAt(nowSeconds: Long): Boolean =
    lastActive(this) >= nowSeconds - PRESENCE_WINDOW_SECONDS

class BotsViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    autoLoad: Boolean = true,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) : ViewModel() {
    private val _uiState = MutableStateFlow(BotsUiState())
    val uiState: StateFlow<BotsUiState> = _uiState.asStateFlow()

    init {
        if (autoLoad) loadBots()
    }

    fun loadBots(isRefresh: Boolean = false) {
        _uiState.update {
            if (isRefresh) {
                it.copy(isRefreshing = true, errorMessage = null)
            } else {
                it.copy(isLoading = true, errorMessage = null)
            }
        }
        viewModelScope.launch(ioDispatcher) {
            when (val result = safeApiCall { ApiClient.hermesApi.getProfiles() }) {
                is NetworkResult.Success ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            profiles = result.data.profiles,
                            nowSeconds = clockSeconds(),
                        )
                    }
                is NetworkResult.Failure ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = LOAD_ERROR_MESSAGE,
                        )
                    }
            }
        }
    }

    fun setSearchQuery(query: String) = _uiState.update { it.copy(searchQuery = query) }

    fun toggleShowHidden() = _uiState.update { it.copy(showHidden = !it.showHidden) }
}
