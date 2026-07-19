// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.ui.connect

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ConnectUiState(
    val token: String = "",
    val baseUrl: String = ServerEndpoint.DEFAULT_BASE_URL,
    val transportWarning: String? = null,
    // Derived legacy fields retained for pairing-code compatibility.
    val host: String = "127.0.0.1",
    val port: String = "9119",
    val isConnecting: Boolean = false,
    val connectionSuccess: Boolean = false,
    val errorMessage: String? = null,
    val profileName: String = "",
    val saveProfile: Boolean = false,
    val profiles: List<ConnectionProfile> = emptyList(),
    val selectedProfile: ConnectionProfile? = null,
)

class ConnectViewModel(
    private val app: Application,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ConnectUiState())
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    init {
        loadSavedValues()
    }

    fun loadSavedValues() {
        val savedToken = AuthManager.getToken() ?: ""
        val savedBaseUrl = AuthManager.getBaseUrl()
        val profiles = AuthManager.getConnectionProfiles()
        val selectedId = AuthManager.getSelectedProfileId()
        val selectedProfile = profiles.firstOrNull { it.id == selectedId }
        val profileBaseUrl = selectedProfile?.resolvedBaseUrl ?: savedBaseUrl
        val endpoint =
            ServerEndpoint.parse(
                profileBaseUrl,
                CleartextPolicy.ALLOW_WITH_WARNING,
            )
        _uiState.update {
            it.copy(
                token = savedToken,
                baseUrl = endpoint.baseUrl.toString(),
                transportWarning = endpoint.securityWarning,
                host = endpoint.baseUrl.host,
                port = endpoint.baseUrl.port.toString(),
                profiles = profiles,
                selectedProfile = selectedProfile,
                profileName = selectedProfile?.name ?: "",
            )
        }
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
                profile.resolvedBaseUrl,
                CleartextPolicy.ALLOW_WITH_WARNING,
            )
        _uiState.update {
            it.copy(
                selectedProfile = profile,
                profileName = profile.name,
                baseUrl = endpoint.baseUrl.toString(),
                transportWarning = endpoint.securityWarning,
                host = endpoint.baseUrl.host,
                port = endpoint.baseUrl.port.toString(),
                token = token,
            )
        }
    }

    fun onTokenChange(value: String) {
        _uiState.update { it.copy(token = value.trim(), errorMessage = null) }
    }

    fun onBaseUrlChange(value: String) {
        val trimmed = value.trim()
        val parsed =
            runCatching {
                ServerEndpoint.parse(
                    trimmed,
                    CleartextPolicy.ALLOW_WITH_WARNING,
                )
            }.getOrNull()
        _uiState.update {
            it.copy(
                baseUrl = trimmed,
                transportWarning = parsed?.securityWarning,
                host = parsed?.baseUrl?.host ?: it.host,
                port = parsed?.baseUrl?.port?.toString() ?: it.port,
                errorMessage = null,
            )
        }
    }

    fun onHostChange(value: String) {
        val host = value.trim()
        if (host.isBlank()) {
            _uiState.update {
                it.copy(host = host, baseUrl = "", errorMessage = null)
            }
            return
        }
        val current =
            ServerEndpoint.parse(
                _uiState.value.baseUrl,
                CleartextPolicy.ALLOW_WITH_WARNING,
            )
        val normalizedHost = host.removePrefix("[").removeSuffix("]")
        onBaseUrlChange(
            current.baseUrl.newBuilder().host(normalizedHost).build().toString(),
        )
    }

    fun onPortChange(value: String) {
        val digits = value.filter { c -> c.isDigit() }
        val port = digits.toIntOrNull()
        if (port == null || port !in 1..65535) {
            _uiState.update { it.copy(port = digits, errorMessage = null) }
            return
        }
        val current =
            ServerEndpoint.parse(
                _uiState.value.baseUrl,
                CleartextPolicy.ALLOW_WITH_WARNING,
            )
        onBaseUrlChange(current.baseUrl.newBuilder().port(port).build().toString())
    }

    fun connect() {
        val state = _uiState.value
        if (state.token.isBlank()) {
            _uiState.update { it.copy(errorMessage = app.getString(R.string.connect_error_token_required)) }
            return
        }
        if (state.host.isBlank()) {
            _uiState.update { it.copy(errorMessage = app.getString(R.string.connect_error_host_required)) }
            return
        }
        val port = state.port.toIntOrNull()
        if (port == null || port !in 1..65535) {
            _uiState.update { it.copy(errorMessage = app.getString(R.string.connect_error_port_invalid)) }
            return
        }
        val endpoint =
            try {
                ServerEndpoint.parseForBuild(state.baseUrl)
            } catch (e: IllegalArgumentException) {
                _uiState.update { it.copy(errorMessage = e.message) }
                return
            }

        _uiState.update {
            it.copy(
                isConnecting = true,
                errorMessage = null,
                baseUrl = endpoint.baseUrl.toString(),
                transportWarning = endpoint.securityWarning,
            )
        }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    val tempApi =
                        ApiClient.createTempService(
                            endpoint.baseUrl.toString(),
                            state.token,
                        )
                    safeApiCall { tempApi.getStatus() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    // Persist only after successful validation.
                    AuthManager.setBaseUrl(endpoint.baseUrl.toString())
                    AuthManager.setToken(state.token)
                    ApiClient.rebuild()

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
                                    host = "",
                                    port = 0,
                                    baseUrl = endpoint.baseUrl.toString(),
                                )
                            } else {
                                ConnectionProfile(
                                    name = state.profileName,
                                    baseUrl = endpoint.baseUrl.toString(),
                                )
                            }
                        val updatedProfiles =
                            if (existingIndex >= 0) {
                                currentProfiles.mapIndexed { idx, p -> if (idx == existingIndex) targetProfile else p }
                            } else {
                                currentProfiles + targetProfile
                            }
                        AuthManager.saveConnectionProfiles(updatedProfiles)
                        AuthManager.setSelectedProfileId(targetProfile.id)
                        AuthManager.setProfileToken(targetProfile.id, state.token)
                    } else {
                        // No explicit profile name — store the connection on the default profile.
                        AuthManager.ensureDefaultSelected()
                        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
                        AuthManager.setProfileToken(AuthManager.DEFAULT_PROFILE_ID, state.token)
                    }
                    ApiClient.rebuild()
                    _uiState.update {
                        it.copy(isConnecting = false, connectionSuccess = true, errorMessage = null)
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
                    _uiState.update { it.copy(isConnecting = false, errorMessage = msg) }
                }
            }
        }
    }

    private fun applyLegacyPairing(
        host: String,
        portText: String,
        token: String,
    ) {
        val port = portText.toIntOrNull()
        if (port == null || port !in 1..65535) {
            _uiState.update {
                it.copy(errorMessage = app.getString(R.string.connect_error_port_invalid))
            }
            return
        }
        val endpoint = ServerEndpoint.fromLegacy(host, port)
        _uiState.update {
            it.copy(
                baseUrl = endpoint.baseUrl.toString(),
                transportWarning = endpoint.securityWarning,
                host = endpoint.baseUrl.host,
                port = endpoint.baseUrl.port.toString(),
                token = token,
                errorMessage = null,
            )
        }
        connect()
    }

    fun onPairingString(value: String) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return

        try {
            if (trimmed.startsWith("hermes://connect?", ignoreCase = true)) {
                val uri = android.net.Uri.parse(trimmed)
                val host = uri.getQueryParameter("host")
                val port = uri.getQueryParameter("port")
                val token = uri.getQueryParameter("token")
                val baseUrl =
                    uri.getQueryParameter("base_url")
                        ?.takeIf { it.isNotBlank() }
                        ?: uri.getQueryParameter("baseUrl")?.takeIf { it.isNotBlank() }
                if (baseUrl != null && token != null) {
                    onBaseUrlChange(baseUrl)
                    _uiState.update { it.copy(token = token) }
                    connect()
                    return
                }
                if (host != null && port != null && token != null) {
                    applyLegacyPairing(host, port, token)
                    return
                }
            }

            // Try decoding as Base64 JSON
            try {
                val decodedBytes = android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT)
                val decodedString = String(decodedBytes, Charsets.UTF_8)
                if (decodedString.startsWith("{") && decodedString.endsWith("}")) {
                    val json = OkHttpProvider.json.parseToJsonElement(decodedString).jsonObject
                    val host = json["host"]?.jsonPrimitive?.content
                    val port = json["port"]?.jsonPrimitive?.content
                    val token = json["token"]?.jsonPrimitive?.content
                    val baseUrl =
                        json["base_url"]
                            ?.jsonPrimitive
                            ?.content
                            ?.takeIf { it.isNotBlank() }
                            ?: json["baseUrl"]
                                ?.jsonPrimitive
                                ?.content
                                ?.takeIf { it.isNotBlank() }
                    if (baseUrl != null && token != null) {
                        onBaseUrlChange(baseUrl)
                        _uiState.update { it.copy(token = token) }
                        connect()
                        return
                    }
                    if (host != null && port != null && token != null) {
                        applyLegacyPairing(host, port, token)
                        return
                    }
                    // Decoded valid JSON but missing required fields
                    _uiState.update {
                        it.copy(
                            errorMessage = app.getString(R.string.connect_error_missing_fields),
                        )
                    }
                    return
                }
                // Decoded to valid string but not JSON shape — not a pairing string
                _uiState.update {
                    it.copy(
                        errorMessage = app.getString(R.string.connect_error_malformed),
                    )
                }
                return
            } catch (e: IllegalArgumentException) {
                // Not valid Base64 — check if input looks like a raw token
                if (trimmed.length >= 32 && trimmed.matches(Regex("[A-Za-z0-9_\\-]+"))) {
                    _uiState.update { it.copy(token = trimmed, errorMessage = null) }
                } else {
                    _uiState.update {
                        it.copy(
                            errorMessage = app.getString(R.string.connect_error_malformed),
                        )
                    }
                }
                return
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage =
                            String.format(
                                app.getString(R.string.connect_error_parse_failed),
                                e.message ?: "",
                            ),
                    )
                }
                return
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    errorMessage = String.format(app.getString(R.string.connect_error_parse_failed), e.message ?: ""),
                )
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
