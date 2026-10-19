package com.m57.hermescontrol.ui.webhooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.CreateWebhookRequest
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.data.model.WebhookSubscription
import com.m57.hermescontrol.data.model.WebhookToggleSubscriptionRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.RestConnectionFence
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WebhooksUiState(
    val isLoading: Boolean = false,
    val isActionRunning: Boolean = false,
    val enabled: Boolean = false,
    val baseUrl: String? = null,
    val subscriptions: List<WebhookSubscription> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    // Create form state
    val showCreateDialog: Boolean = false,
    val createFormName: String = "",
    val createFormDescription: String = "",
    val createFormEvents: String = "",
    val createFormDeliver: String = "log",
    val createFormSecret: String = "",
    val createFormError: String? = null,
    // Delete confirmation state
    val deleteTarget: WebhookSubscription? = null,
    val isDeleting: Boolean = false,
    // Toggle tracking
    val togglingName: String? = null,
)

class WebhooksViewModel(
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) :
    ViewModel(),
        ToastHost {
    private val _uiState = MutableStateFlow(WebhooksUiState())
    val uiState: StateFlow<WebhooksUiState> = _uiState.asStateFlow()

    private var connection: RestConnectionFence? = null
    private var loadGeneration = 0L
    private var mutationGeneration = 0L

    private fun bindConnection(): RestConnectionFence {
        if (connection?.isCurrent() == false) {
            _uiState.value = WebhooksUiState()
        }
        return RestConnectionFence.capture().also { connection = it }
    }

    fun loadWebhooks() {
        val fence = bindConnection()
        val load = ++loadGeneration
        val mutation = mutationGeneration
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    if (!fence.isCurrent()) return@withContext null
                    safeApiCall(retries = 0) { ApiClient.hermesApi.getWebhooks() }
                }
            if (!fence.isCurrent() || load != loadGeneration || mutation != mutationGeneration) return@launch
            _uiState.update { state ->
                when (result) {
                    is NetworkResult.Success ->
                        state.copy(
                            isLoading = false,
                            enabled = if (state.isActionRunning) state.enabled else result.data.enabled,
                            baseUrl = result.data.base_url,
                            subscriptions = result.data.subscriptions.orEmpty(),
                        )
                    is NetworkResult.Failure ->
                        state.copy(
                            isLoading = false,
                            errorMessage = "Failed to load webhooks: ${result.error.message}",
                        )
                    null -> state.copy(isLoading = false)
                }
            }
        }
    }

    fun toggleWebhooks(targetEnabled: Boolean) {
        val fence = bindConnection()
        if (_uiState.value.isActionRunning) return
        mutationGeneration++
        val originalEnabled = _uiState.value.enabled
        _uiState.update {
            it.copy(
                enabled = targetEnabled,
                isActionRunning = true,
                isLoading = false,
                toastMessage = null,
            )
        }

        viewModelScope.launch {
            try {
                val result =
                    withContext(ioDispatcher) {
                        if (!fence.isCurrent()) return@withContext null
                        safeApiCall(retries = 0) {
                            ApiClient.hermesApi.configurePlatform(
                                "webhook",
                                MessagingPlatformUpdate(enabled = targetEnabled),
                            )
                        }
                    }
                if (!fence.isCurrent()) return@launch
                val accepted = result is NetworkResult.Success && result.data.ok == true
                _uiState.update {
                    it.copy(
                        enabled = if (accepted) targetEnabled else originalEnabled,
                        toastMessage =
                            if (accepted) {
                                "Webhooks global status ${if (targetEnabled) "enabled" else "disabled"}"
                            } else {
                                "Failed to toggle webhooks"
                            },
                    )
                }
            } finally {
                if (fence.isCurrent()) {
                    mutationGeneration++
                    _uiState.update { it.copy(isActionRunning = false, isLoading = false) }
                }
            }
        }
    }

    // ── Create dialog ────────────────────────────────────────────────

    fun showCreateDialog() {
        _uiState.update {
            it.copy(
                showCreateDialog = true,
                createFormName = "",
                createFormDescription = "",
                createFormEvents = "",
                createFormDeliver = "log",
                createFormSecret = "",
                createFormError = null,
            )
        }
    }

    fun dismissCreateDialog() {
        _uiState.update { it.copy(showCreateDialog = false, createFormError = null) }
    }

    fun updateCreateFormName(value: String) {
        _uiState.update { it.copy(createFormName = value, createFormError = null) }
    }

    fun updateCreateFormDescription(value: String) {
        _uiState.update { it.copy(createFormDescription = value) }
    }

    fun updateCreateFormEvents(value: String) {
        _uiState.update { it.copy(createFormEvents = value) }
    }

    fun updateCreateFormDeliver(value: String) {
        _uiState.update { it.copy(createFormDeliver = value) }
    }

    fun updateCreateFormSecret(value: String) {
        _uiState.update { it.copy(createFormSecret = value) }
    }

    fun createSubscription() {
        val state = _uiState.value
        val name = state.createFormName.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(createFormError = "Name is required") }
            return
        }

        val events =
            state.createFormEvents
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        _uiState.update { it.copy(isActionRunning = true, createFormError = null) }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.createWebhook(
                            CreateWebhookRequest(
                                name = name,
                                description = state.createFormDescription.trim().ifEmpty { null },
                                events = events.ifEmpty { null },
                                deliver = state.createFormDeliver.ifBlank { null },
                                secret = state.createFormSecret.trim().ifEmpty { null },
                            ),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            showCreateDialog = false,
                            toastMessage = "Subscription \"$name\" created",
                        )
                    }
                    loadWebhooks()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            createFormError = result.error.message,
                        )
                    }
                }
            }
        }
    }

    // ── Per-subscription toggle ──────────────────────────────────────

    fun toggleSubscription(
        name: String,
        enabled: Boolean,
    ) {
        _uiState.update { it.copy(togglingName = name) }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.setWebhookEnabled(
                            name = name,
                            body = WebhookToggleSubscriptionRequest(enabled = enabled),
                        )
                    }
                }
            _uiState.update { it.copy(togglingName = null) }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Subscription \"$name\" ${if (enabled) "enabled" else "disabled"}",
                        )
                    }
                    loadWebhooks()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(toastMessage = "Failed to toggle \"$name\": ${result.error.message}")
                    }
                }
            }
        }
    }

    // ── Delete confirmation ─────────────────────────────────────────

    fun promptDeleteSubscription(sub: WebhookSubscription) {
        _uiState.update { it.copy(deleteTarget = sub) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(deleteTarget = null) }
    }

    fun confirmDeleteSubscription() {
        val target = _uiState.value.deleteTarget ?: return
        _uiState.update { it.copy(isDeleting = true) }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.deleteWebhook(target.name) }
                }
            _uiState.update { it.copy(isDeleting = false, deleteTarget = null) }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(toastMessage = "Subscription \"${target.name}\" deleted")
                    }
                    loadWebhooks()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(toastMessage = "Failed to delete \"${target.name}\": ${result.error.message}")
                    }
                }
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
