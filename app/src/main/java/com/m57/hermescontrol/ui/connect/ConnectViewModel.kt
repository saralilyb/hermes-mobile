// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.ui.connect

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.config.resolveBaseUrl
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.AuthSessionState
import com.m57.hermescontrol.data.model.StatusResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.reconcilePressureStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConnectUiState(
    val token: String = "",
    val baseUrl: String = ServerEndpoint.DEFAULT_BASE_URL,
    val transportWarning: String? = null,
    val isConnecting: Boolean = false,
    val connectionSuccess: Boolean = false,
    val errorMessage: String? = null,
    val profileName: String = "",
    val saveProfile: Boolean = false,
    val profiles: List<ConnectionProfile> = emptyList(),
    val selectedProfile: ConnectionProfile? = null,
    val authMode: String = "token",
    val status: StatusResponse? = null,
)

class ConnectViewModel(
    private val app: Application,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ConnectUiState())
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()
    private var statusGeneration = 0L

    init {
        loadSavedValues()
    }

    fun loadSavedValues() {
        val savedToken = AuthManager.getToken() ?: ""
        val savedBaseUrl = AuthManager.getBaseUrl()
        val profiles = AuthManager.getConnectionProfiles()
        val selectedId = AuthManager.getSelectedProfileId()
        val selectedProfile = profiles.firstOrNull { it.id == selectedId }
        val resolvedBaseUrl = selectedProfile?.resolveBaseUrl(savedBaseUrl) ?: savedBaseUrl
        val endpoint =
            ServerEndpoint.parse(
                resolvedBaseUrl,
                CleartextPolicy.ALLOW_WITH_WARNING,
            )
        _uiState.update {
            it.copy(
                token = savedToken,
                baseUrl = endpoint.baseUrl.toString(),
                transportWarning = endpoint.securityWarning,
                profiles = profiles,
                selectedProfile = selectedProfile,
                profileName = selectedProfile?.name ?: "",
                authMode = selectedProfile?.wsAuthParam?.takeIf(String::isNotBlank) ?: AuthManager.getWsAuthParam(),
                status = null,
            )
        }
        loadStatus()
    }

    fun onProfileNameChange(value: String) {
        _uiState.update { it.copy(profileName = value, errorMessage = null) }
    }

    fun onSaveProfileChange(value: Boolean) {
        _uiState.update { it.copy(saveProfile = value) }
    }

    fun selectProfile(profile: ConnectionProfile) {
        AuthManager.setSelectedProfileId(profile.id)
        val token = AuthManager.getProfileToken(profile.id) ?: ""
        val endpoint =
            ServerEndpoint.parse(
                profile.resolveBaseUrl(AuthManager.getBaseUrl()),
                CleartextPolicy.ALLOW_WITH_WARNING,
            )
        _uiState.update {
            it.copy(
                selectedProfile = profile,
                profileName = profile.name,
                baseUrl = endpoint.baseUrl.toString(),
                transportWarning = endpoint.securityWarning,
                token = token,
                authMode = profile.wsAuthParam?.takeIf(String::isNotBlank) ?: AuthManager.getWsAuthParam(),
                status = null,
            )
        }
        ApiClient.rebuild()
        loadStatus()
    }

    fun onTokenChange(value: String) {
        statusGeneration += 1
        _uiState.update { it.copy(token = value.trim(), errorMessage = null, status = null) }
    }

    fun onBaseUrlChange(value: String) {
        statusGeneration += 1
        val trimmed = value.trim()
        val warning =
            runCatching {
                ServerEndpoint.parse(trimmed, CleartextPolicy.ALLOW_WITH_WARNING).securityWarning
            }.getOrNull()
        _uiState.update { it.copy(baseUrl = trimmed, transportWarning = warning, errorMessage = null, status = null) }
    }

    /** Probe only the persisted selected profile, using its normal cookie-or-token client. */
    fun loadStatus() {
        val generation = ++statusGeneration
        val state = _uiState.value
        val profile = state.selectedProfile ?: return
        val selectedId = AuthManager.getSelectedProfileId() ?: return
        val expectedUrl = profile.resolveBaseUrl(AuthManager.getBaseUrl())
        val expectedToken = AuthManager.getProfileToken(profile.id).orEmpty()
        val expectedMode = profile.wsAuthParam?.takeIf(String::isNotBlank) ?: AuthManager.getWsAuthParam()
        if (profile.id != selectedId || state.baseUrl != expectedUrl || state.authMode != expectedMode ||
            (expectedMode != "ticket" && state.token != expectedToken)
        ) {
            return
        }
        val fingerprint = listOf(profile.id, state.baseUrl, state.token, state.authMode)
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) { safeApiCall(reportAuthExpiry = false) { ApiClient.hermesApi.getStatus() } }
            val current = _uiState.value
            val currentFingerprint =
                listOf(current.selectedProfile?.id.orEmpty(), current.baseUrl, current.token, current.authMode)
            if (generation != statusGeneration || fingerprint != currentFingerprint) return@launch
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { currentState ->
                        currentState.copy(
                            status =
                                if (result.data.memory == null && result.data.disk == null) {
                                    null
                                } else {
                                    reconcilePressureStatus(currentState.status, result.data)
                                },
                        )
                    }
                }

                is NetworkResult.Failure -> _uiState.update { it.copy(status = null) }
            }
        }
    }

    fun connect() {
        val state = _uiState.value
        if (state.token.isBlank()) {
            _uiState.update { it.copy(errorMessage = app.getString(R.string.connect_error_token_required)) }
            return
        }
        val endpoint =
            runCatching { ServerEndpoint.parseForBuild(state.baseUrl) }.getOrNull()
        if (endpoint == null) {
            _uiState.update { it.copy(errorMessage = app.getString(R.string.connect_error_url_invalid)) }
            return
        }

        val generation = ++statusGeneration
        val requestFingerprint =
            listOf(
                state.selectedProfile?.id.orEmpty(),
                state.baseUrl,
                state.token,
                state.authMode,
            )
        _uiState.update { it.copy(isConnecting = true, errorMessage = null, status = null) }

        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    val tempApi = ApiClient.createTempService(endpoint.baseUrl.toString(), state.token)
                    safeApiCall(reportAuthExpiry = false) { tempApi.getStatus() }
                }
            val current = _uiState.value
            val currentFingerprint =
                listOf(
                    current.selectedProfile?.id.orEmpty(),
                    current.baseUrl,
                    current.token,
                    current.authMode,
                )
            if (generation != statusGeneration || requestFingerprint != currentFingerprint) return@launch
            when (result) {
                is NetworkResult.Success -> {
                    // Persist credentials to the selected (Default) profile upon successful verification.
                    AuthManager.setToken(state.token)
                    AuthManager.setBaseUrl(state.baseUrl)
                    // Direct mode uses the bearer token for REST and WebSocket
                    // auth. Clear any gated-session state left by a previous
                    // endpoint before rebuilding the clients.
                    AuthManager.setSessionCookie(null)
                    AuthManager.setWsAuthParam("token")
                    ApiClient.rebuild()
                    AuthSessionState.markAuthenticated()

                    if (state.saveProfile) {
                        val currentProfiles = AuthManager.getConnectionProfiles()
                        val existingIndex =
                            currentProfiles.indexOfFirst {
                                it.name.equals(
                                    state.profileName,
                                    ignoreCase = true,
                                )
                            }
                        val targetProfile =
                            if (existingIndex >= 0) {
                                currentProfiles[existingIndex].copy(
                                    baseUrl = state.baseUrl,
                                    wsAuthParam = "token",
                                )
                            } else {
                                ConnectionProfile(
                                    name = state.profileName,
                                    baseUrl = state.baseUrl,
                                    wsAuthParam = "token",
                                )
                            }
                        val updatedProfiles =
                            if (existingIndex >= 0) {
                                currentProfiles.mapIndexed { idx, p -> if (idx == existingIndex) targetProfile else p }
                            } else {
                                currentProfiles + targetProfile
                            }
                        AuthManager.saveConnectionProfilesAndSelect(
                            updatedProfiles,
                            targetProfile.id,
                            state.token,
                        )
                    } else {
                        // No explicit profile name — store the connection on the default profile.
                        AuthManager.ensureDefaultSelected()
                        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
                        AuthManager.setProfileToken(AuthManager.DEFAULT_PROFILE_ID, state.token)
                    }
                    ApiClient.rebuild()
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            connectionSuccess = true,
                            errorMessage = null,
                            authMode = "token",
                            status =
                                result.data.takeIf { status ->
                                    status.memory != null || status.disk != null
                                },
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    val msg =
                        when (val err = result.error) {
                            is NetworkError.Http -> {
                                when (err.code) {
                                    401 -> {
                                        AuthManager.setToken(null)
                                        if (AuthManager.getSelectedProfileId() != null) {
                                            AuthManager.setProfileToken(AuthManager.getSelectedProfileId()!!, null)
                                        }
                                        app.getString(R.string.connect_error_401)
                                    }

                                    403 -> {
                                        app.getString(R.string.connect_error_403)
                                    }

                                    else -> {
                                        String.format(app.getString(R.string.connect_error_http_code), err.code)
                                    }
                                }
                            }

                            is NetworkError.AuthExpired -> {
                                AuthManager.setToken(null)
                                if (AuthManager.getSelectedProfileId() != null) {
                                    AuthManager.setProfileToken(AuthManager.getSelectedProfileId()!!, null)
                                }
                                app.getString(R.string.connect_error_401)
                            }

                            is NetworkError.Connection -> {
                                val causeMessage = err.cause.message ?: ""
                                when {
                                    causeMessage.contains(
                                        "timeout",
                                        true,
                                    ) -> {
                                        app.getString(R.string.connect_error_timeout)
                                    }

                                    causeMessage.contains(
                                        "refused",
                                        true,
                                    ) -> {
                                        app.getString(R.string.connect_error_refused)
                                    }

                                    causeMessage.contains(
                                        "resolve",
                                        true,
                                    ) -> {
                                        app.getString(R.string.connect_error_resolve)
                                    }

                                    else -> {
                                        String.format(
                                            app.getString(R.string.connect_error_connection_failed),
                                            err.cause.message ?: "",
                                        )
                                    }
                                }
                            }

                            is NetworkError.Unknown -> {
                                val causeMessage = err.cause.message ?: ""
                                when {
                                    causeMessage.contains(
                                        "timeout",
                                        true,
                                    ) -> {
                                        app.getString(R.string.connect_error_timeout)
                                    }

                                    causeMessage.contains(
                                        "refused",
                                        true,
                                    ) -> {
                                        app.getString(R.string.connect_error_refused)
                                    }

                                    causeMessage.contains(
                                        "resolve",
                                        true,
                                    ) -> {
                                        app.getString(R.string.connect_error_resolve)
                                    }

                                    else -> {
                                        String.format(
                                            app.getString(R.string.connect_error_connection_failed),
                                            err.cause.message ?: "",
                                        )
                                    }
                                }
                            }
                        }
                    _uiState.update { it.copy(isConnecting = false, errorMessage = msg, status = null) }
                }
            }
        }
    }
}

class ConnectViewModelFactory(
    private val app: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ConnectViewModel(app) as T
}
