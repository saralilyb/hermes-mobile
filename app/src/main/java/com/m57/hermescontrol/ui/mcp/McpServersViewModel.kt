package com.m57.hermescontrol.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.AddMcpServerRequest
import com.m57.hermescontrol.data.model.McpCatalogEntry
import com.m57.hermescontrol.data.model.McpCatalogInstallRequest
import com.m57.hermescontrol.data.model.McpOAuthFlowResponse
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.data.model.McpServerToggleRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class AddServerMode { HTTP, Stdio }

data class McpServersUiState(
    val isLoading: Boolean = false,
    val servers: List<McpServer> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    // Add server form
    val showAddForm: Boolean = false,
    val addMode: AddServerMode = AddServerMode.HTTP,
    val addServerName: String = "",
    val addServerUrl: String = "",
    val addServerCommand: String = "",
    val addServerArgs: String = "",
    val addServerAuth: String = "none", // "none" | "header" | "oauth"
    val addServerBearerToken: String = "",
    val addingServer: Boolean = false,
    // Env vars for editing
    val editingEnvFor: String? = null,
    val envKeyInput: String = "",
    val envValueInput: String = "",
    // Catalog
    val catalogQuery: String = "",
    val catalogEntries: List<McpCatalogEntry> = emptyList(),
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val installingCatalogEntry: String? = null,
    val catalogInstallEnv: Map<String, String> = emptyMap(),
    val activeOAuthFlow: McpOAuthFlowResponse? = null,
)

class McpServersViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(McpServersUiState())
    val uiState: StateFlow<McpServersUiState> = _uiState.asStateFlow()

    // ── Data loading ──────────────────────────────────────────

    fun loadServers() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getMcpServers() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        servers = data.servers.orEmpty(),
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load MCP servers: $errorMsg",
                    )
                }
            },
        )
    }

    // ── Server toggle ────────────────────────────────────────

    fun toggleServer(server: McpServer) {
        val originalEnabled = server.enabled
        val targetEnabled = !originalEnabled

        _uiState.update { state ->
            state.copy(
                servers =
                    state.servers.map {
                        if (it.name == server.name) it.copy(enabled = targetEnabled) else it
                    },
            )
        }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.toggleMcpServer(
                            server.name,
                            McpServerToggleRequest(targetEnabled),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Server '${server.name}' ${if (targetEnabled) "enabled" else "disabled"}",
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    revertToggle(server.name, originalEnabled, "Failed to toggle server: ${result.error.message}")
                }
            }
        }
    }

    fun testServer(name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(toastMessage = "Testing server '$name'…") }
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.testMcpServer(name) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Server '$name' tested — OK") }
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Server '$name' test failed: ${result.error.message}") }
                }
            }
        }
    }

    fun deleteServer(name: String) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.deleteMcpServer(name) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Server '$name' deleted") }
                    loadServers()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to delete server: ${result.error.message}") }
                }
            }
        }
    }

    fun restartServer(name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(toastMessage = "Restarting server '$name'…") }
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.restartMcpServer(name) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Server '$name' restarted") }
                    loadServers()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to restart server: ${result.error.message}") }
                }
            }
        }
    }

    // ── Add server form ──────────────────────────────────────

    fun toggleAddForm() {
        _uiState.update { it.copy(showAddForm = !it.showAddForm) }
    }

    fun setAddMode(mode: AddServerMode) {
        _uiState.update { it.copy(addMode = mode) }
    }

    fun updateAddServerName(v: String) {
        _uiState.update { it.copy(addServerName = v) }
    }

    fun updateAddServerUrl(v: String) {
        _uiState.update { it.copy(addServerUrl = v) }
    }

    fun updateAddServerCommand(v: String) {
        _uiState.update { it.copy(addServerCommand = v) }
    }

    fun updateAddServerArgs(v: String) {
        _uiState.update { it.copy(addServerArgs = v) }
    }

    fun updateAddServerAuth(v: String) {
        _uiState.update { it.copy(addServerAuth = v) }
    }

    fun updateAddServerBearerToken(v: String) {
        _uiState.update { it.copy(addServerBearerToken = v) }
    }

    fun submitAddServer() {
        val state = _uiState.value
        if (state.addServerName.isBlank()) {
            _uiState.update { it.copy(toastMessage = "Server name is required") }
            return
        }
        _uiState.update { it.copy(addingServer = true) }
        viewModelScope.launch {
            val request =
                AddMcpServerRequest(
                    name = state.addServerName.trim(),
                    url = if (state.addMode == AddServerMode.HTTP) state.addServerUrl.trim().ifBlank { null } else null,
                    command =
                        if (state.addMode == AddServerMode.Stdio) {
                            state.addServerCommand.trim().ifBlank { null }
                        } else {
                            null
                        },
                    args =
                        if (state.addMode == AddServerMode.Stdio && state.addServerArgs.isNotBlank()) {
                            state.addServerArgs
                                .trim()
                                .split("\\s+".toRegex())
                                .filter { it.isNotEmpty() }
                        } else {
                            null
                        },
                    auth =
                        if (state.addMode == AddServerMode.HTTP && state.addServerAuth != "none") {
                            state.addServerAuth
                        } else {
                            null
                        },
                    bearerToken =
                        if (state.addMode == AddServerMode.HTTP && state.addServerAuth == "header") {
                            state.addServerBearerToken.trim().ifBlank { null }
                        } else {
                            null
                        },
                )
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.addMcpServer(request) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            addingServer = false,
                            showAddForm = false,
                            addServerName = "",
                            addServerUrl = "",
                            addServerCommand = "",
                            addServerArgs = "",
                            addServerAuth = "none",
                            addServerBearerToken = "",
                            toastMessage = "Server '${request.name}' added",
                        )
                    }
                    loadServers()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            addingServer = false,
                            toastMessage = "Failed to add server: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    // ── Env var editing ──────────────────────────────────────

    fun startEditingEnv(server: McpServer) {
        _uiState.update { it.copy(editingEnvFor = server.name, envKeyInput = "", envValueInput = "") }
    }

    fun stopEditingEnv() {
        _uiState.update { it.copy(editingEnvFor = null, envKeyInput = "", envValueInput = "") }
    }

    fun updateEnvKey(v: String) {
        _uiState.update { it.copy(envKeyInput = v) }
    }

    fun updateEnvValue(v: String) {
        _uiState.update { it.copy(envValueInput = v) }
    }

    fun addEnvVar(serverName: String) {
        val state = _uiState.value
        val key = state.envKeyInput.trim()
        val value = state.envValueInput.trim()
        if (key.isBlank()) {
            _uiState.update { it.copy(toastMessage = "Key is required") }
            return
        }
        viewModelScope.launch {
            val existingEnv = state.servers.find { it.name == serverName }?.env ?: emptyMap()
            val updatedEnv = existingEnv + (key to value)
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.updateMcpServer(serverName, mapOf("env" to updatedEnv)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            envKeyInput = "",
                            envValueInput = "",
                            toastMessage = "Env var '$key' added",
                        )
                    }
                    loadServers()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to add env var: ${result.error.message}") }
                }
            }
        }
    }

    fun removeEnvVar(
        serverName: String,
        key: String,
    ) {
        viewModelScope.launch {
            val state = _uiState.value
            val existingEnv = state.servers.find { it.name == serverName }?.env ?: emptyMap()
            val updatedEnv = existingEnv - key
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.updateMcpServer(serverName, mapOf("env" to updatedEnv)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Env var '$key' removed") }
                    loadServers()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to remove env var: ${result.error.message}") }
                }
            }
        }
    }

    // ── Catalog ──────────────────────────────────────────────

    fun loadCatalog() {
        _uiState.update { it.copy(catalogLoading = true, catalogError = null) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getMcpCatalog() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            catalogLoading = false,
                            catalogEntries = result.data.entries.orEmpty(),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            catalogLoading = false,
                            catalogError = "Failed to load catalog: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun updateCatalogQuery(v: String) {
        _uiState.update { it.copy(catalogQuery = v) }
    }

    fun installCatalogEntry(entry: McpCatalogEntry) {
        val state = _uiState.value
        _uiState.update { it.copy(installingCatalogEntry = entry.name) }
        viewModelScope.launch {
            val request =
                McpCatalogInstallRequest(
                    name = entry.name,
                    env = state.catalogInstallEnv.ifEmpty { null },
                )
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.installMcpCatalogEntry(request) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            installingCatalogEntry = null,
                            catalogInstallEnv = emptyMap(),
                            toastMessage = "Catalog entry '${entry.name}' installed",
                        )
                    }
                    loadServers()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            installingCatalogEntry = null,
                            toastMessage = "Failed to install: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun updateCatalogEnvVar(
        key: String,
        value: String,
    ) {
        _uiState.update { it.copy(catalogInstallEnv = it.catalogInstallEnv + (key to value)) }
    }

    // ── OAuth ─────────────────────────────────────────────────

    private var oauthStartJob: Job? = null
    private var oauthStartGeneration = 0
    private var oauthPollJob: Job? = null
    private var oauthDeadlineJob: Job? = null
    private var oauthFlowDeadlineMs: Long? = null

    fun startMcpOAuthFlow(
        server: McpServer,
        onOpenBrowser: (String) -> Boolean,
    ) {
        val startGeneration = ++oauthStartGeneration
        val flowDeadlineMs = monotonicTimeMs() + McpOAuthPolicy.FLOW_TIMEOUT_MS
        oauthStartJob?.cancel()
        oauthPollJob?.cancel()
        oauthPollJob = null
        oauthDeadlineJob?.cancel()
        oauthDeadlineJob = null
        oauthFlowDeadlineMs = null
        _uiState.update {
            it.copy(
                activeOAuthFlow = null,
                toastMessage = "Starting OAuth authorization…",
            )
        }
        oauthStartJob =
            viewModelScope.launch {
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            safeApiCall {
                                ApiClient.hermesApi.authMcpServer(server.name)
                            }
                        }
                    if (oauthStartGeneration != startGeneration) return@launch
                    when (result) {
                        is NetworkResult.Success -> {
                            val flow = result.data
                            when (McpOAuthPolicy.classify(flow.status)) {
                                OAuthFlowState.SUCCEEDED -> completeOAuthFlow()
                                OAuthFlowState.FAILED -> {
                                    failOAuthFlow(
                                        flow.error
                                            ?: "OAuth server rejected the request",
                                    )
                                }
                                OAuthFlowState.PENDING -> {
                                    if (McpOAuthPolicy.remainingFlowTimeMs(
                                            deadlineMs = flowDeadlineMs,
                                            nowMs = monotonicTimeMs(),
                                        ) == 0L
                                    ) {
                                        failOAuthFlow("OAuth authorization timed out; try again")
                                        return@launch
                                    }
                                    oauthFlowDeadlineMs = flowDeadlineMs
                                    val url =
                                        McpOAuthPolicy.authorizationUrlOrNull(
                                            flow.authorizationUrl,
                                        )
                                    if (url == null) {
                                        failOAuthFlow(
                                            "OAuth server returned an unsafe " +
                                                "authorization URL",
                                        )
                                        return@launch
                                    }
                                    _uiState.update {
                                        it.copy(
                                            activeOAuthFlow =
                                                flow.copy(
                                                    authorizationUrl = url,
                                                ),
                                        )
                                    }
                                    startOAuthDeadline(flow.flowId)
                                    if (onOpenBrowser(url)) {
                                        startPollingOAuthFlow(flow.flowId, url)
                                    } else {
                                        reportOAuthBrowserLaunchFailure()
                                    }
                                }
                            }
                        }
                        is NetworkResult.Failure -> {
                            failOAuthFlow(
                                "Failed to start OAuth: " +
                                    result.error.message,
                            )
                        }
                    }
                } finally {
                    if (oauthStartGeneration == startGeneration) {
                        oauthStartJob = null
                    }
                }
            }
    }

    private fun startOAuthDeadline(flowId: String) {
        val deadlineMs = oauthFlowDeadlineMs ?: return
        val remainingFlowTimeMs =
            McpOAuthPolicy.remainingFlowTimeMs(
                deadlineMs = deadlineMs,
                nowMs = monotonicTimeMs(),
            )
        oauthDeadlineJob?.cancel()
        oauthDeadlineJob =
            viewModelScope.launch {
                delay(remainingFlowTimeMs)
                if (_uiState.value.activeOAuthFlow?.flowId == flowId) {
                    oauthPollJob?.cancel()
                    oauthPollJob = null
                    failOAuthFlow("OAuth authorization timed out; try again")
                }
            }
    }

    private fun startPollingOAuthFlow(
        flowId: String,
        authorizationUrl: String,
    ) {
        oauthPollJob?.cancel()
        oauthPollJob =
            viewModelScope.launch {
                val deadlineMs = oauthFlowDeadlineMs
                if (deadlineMs == null) {
                    failOAuthFlow("OAuth authorization state was lost; try again")
                    return@launch
                }
                val remainingFlowTimeMs =
                    McpOAuthPolicy.remainingFlowTimeMs(
                        deadlineMs = deadlineMs,
                        nowMs = monotonicTimeMs(),
                    )
                if (remainingFlowTimeMs == 0L) {
                    failOAuthFlow("OAuth authorization timed out; try again")
                    return@launch
                }
                var consecutiveFailures = 0
                val reachedTerminalState =
                    withTimeoutOrNull(remainingFlowTimeMs) {
                        repeat(McpOAuthPolicy.MAX_POLL_ATTEMPTS) {
                            delay(McpOAuthPolicy.POLL_INTERVAL_MS)
                            val result =
                                withContext(Dispatchers.IO) {
                                    safeApiCall { ApiClient.hermesApi.getMcpOAuthFlowStatus(flowId) }
                                }
                            if (_uiState.value.activeOAuthFlow?.flowId != flowId) {
                                return@withTimeoutOrNull true
                            }
                            when (result) {
                                is NetworkResult.Success -> {
                                    consecutiveFailures = 0
                                    val flow = result.data
                                    when (McpOAuthPolicy.classify(flow.status)) {
                                        OAuthFlowState.SUCCEEDED -> {
                                            completeOAuthFlow()
                                            return@withTimeoutOrNull true
                                        }
                                        OAuthFlowState.FAILED -> {
                                            failOAuthFlow(flow.error ?: "Unexpected OAuth flow status")
                                            return@withTimeoutOrNull true
                                        }
                                        OAuthFlowState.PENDING -> {
                                            _uiState.update {
                                                it.copy(
                                                    activeOAuthFlow =
                                                        flow.copy(
                                                            authorizationUrl = authorizationUrl,
                                                        ),
                                                )
                                            }
                                        }
                                    }
                                }
                                is NetworkResult.Failure -> {
                                    if (McpOAuthPolicy.isTerminalPollError(result.error)) {
                                        failOAuthFlow("OAuth authorization expired; try again")
                                        return@withTimeoutOrNull true
                                    }
                                    consecutiveFailures += 1
                                    if (consecutiveFailures >= McpOAuthPolicy.MAX_CONSECUTIVE_POLL_FAILURES) {
                                        failOAuthFlow("OAuth status check failed; try again")
                                        return@withTimeoutOrNull true
                                    }
                                }
                            }
                        }
                        false
                    }
                if (reachedTerminalState != true &&
                    _uiState.value.activeOAuthFlow?.flowId == flowId
                ) {
                    failOAuthFlow("OAuth authorization timed out; try again")
                }
            }
    }

    fun reportOAuthBrowserLaunchFailure() {
        _uiState.update {
            it.copy(toastMessage = "No browser could open the OAuth authorization page")
        }
    }

    fun retryMcpOAuthBrowser(onOpenBrowser: (String) -> Boolean) {
        val flow = _uiState.value.activeOAuthFlow ?: return
        val url = McpOAuthPolicy.authorizationUrlOrNull(flow.authorizationUrl) ?: return
        val deadlineMs = oauthFlowDeadlineMs
        if (deadlineMs == null ||
            McpOAuthPolicy.remainingFlowTimeMs(deadlineMs, monotonicTimeMs()) == 0L
        ) {
            failOAuthFlow("OAuth authorization timed out; try again")
            return
        }
        if (onOpenBrowser(url)) {
            startPollingOAuthFlow(flow.flowId, url)
        } else {
            reportOAuthBrowserLaunchFailure()
        }
    }

    private fun completeOAuthFlow() {
        oauthPollJob = null
        oauthDeadlineJob?.cancel()
        oauthDeadlineJob = null
        oauthFlowDeadlineMs = null
        _uiState.update {
            it.copy(
                activeOAuthFlow = null,
                toastMessage = "OAuth authorization successful",
            )
        }
        loadServers()
    }

    private fun failOAuthFlow(message: String) {
        oauthPollJob = null
        oauthDeadlineJob?.cancel()
        oauthDeadlineJob = null
        oauthFlowDeadlineMs = null
        _uiState.update {
            it.copy(
                activeOAuthFlow = null,
                toastMessage = "OAuth failed: $message",
            )
        }
    }

    fun dismissOAuthFlow() {
        oauthStartGeneration += 1
        oauthStartJob?.cancel()
        oauthStartJob = null
        oauthPollJob?.cancel()
        oauthPollJob = null
        oauthDeadlineJob?.cancel()
        oauthDeadlineJob = null
        oauthFlowDeadlineMs = null
        _uiState.update { it.copy(activeOAuthFlow = null) }
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun revertToggle(
        name: String,
        originalEnabled: Boolean,
        errorMsg: String,
    ) {
        _uiState.update { state ->
            state.copy(
                servers =
                    state.servers.map {
                        if (it.name == name) it.copy(enabled = originalEnabled) else it
                    },
                toastMessage = errorMsg,
            )
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun monotonicTimeMs(): Long = System.nanoTime() / 1_000_000L
}
