package com.m57.hermescontrol.ui.gateway

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.StatusResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GatewayUiState(
    val isLoading: Boolean = false,
    val isActionRunning: Boolean = false,
    val status: StatusResponse? = null,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class GatewayViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) :
    ViewModel(),
        ToastHost {
    private val _uiState = MutableStateFlow(GatewayUiState())
    val uiState: StateFlow<GatewayUiState> = _uiState.asStateFlow()

    private var profileId: String? = null

    fun onProfileChanged(newProfileId: String) {
        if (profileId == newProfileId) return
        profileId = newProfileId
        _uiState.value = GatewayUiState()
        loadStatus()
    }

    fun loadStatus() {
        val requestedProfileId = profileId
        safeLaunchLoad(
            ioDispatcher = ioDispatcher,
            apiCall = { safeApiCall { ApiClient.hermesApi.getStatus() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null, status = null) } },
            onSuccess = { data ->
                if (profileId == requestedProfileId) {
                    _uiState.update { it.copy(isLoading = false, status = data) }
                }
            },
            onError = { errorMsg ->
                if (profileId == requestedProfileId) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load status: $errorMsg",
                            status = null,
                        )
                    }
                }
            },
        )
    }

    fun startGateway() {
        runGatewayAction("start") { safeApiCall { ApiClient.hermesApi.startGateway() } }
    }

    fun stopGateway() {
        runGatewayAction("stop") { safeApiCall { ApiClient.hermesApi.stopGateway() } }
    }

    fun restartGateway() {
        runGatewayAction("restart") { safeApiCall { ApiClient.hermesApi.restartGateway() } }
    }

    private fun runGatewayAction(
        actionName: String,
        apiCall: suspend () -> NetworkResult<Unit>,
    ) {
        _uiState.update { it.copy(isActionRunning = true) }
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { apiCall() }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            toastMessage = "Gateway ${actionName}ed successfully",
                        )
                    }
                    loadStatus()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            toastMessage = "Failed to $actionName gateway: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
