// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.ui.chat.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.idForProfile
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatViewModel
import com.m57.hermescontrol.ui.chat.ClarifyUi
import com.m57.hermescontrol.ui.chat.SecretPromptUi
import com.m57.hermescontrol.ui.chat.SudoPromptUi

@Composable
fun ChatLifecycleEffects(
    sessionId: String?,
    connectionStatus: ConnectionStatus,
    currentSessionId: String?,
    messages: List<ChatMessage>,
    errorMessage: String?,
    backgroundCompleteMessage: String?,
    openError: String?,
    isSearchActive: Boolean,
    currentSearchMatchIndex: Int,
    searchMatchIndices: List<Int>,
    clarifyRequest: ClarifyUi?,
    sudoPrompt: SudoPromptUi?,
    secretPrompt: SecretPromptUi?,
    listState: LazyListState,
    scrollController: ChatScrollController,
    snackbarHostState: SnackbarHostState,
    viewModel: ChatViewModel,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val serverState by AuthManager.serverStore.stateFlow.collectAsStateWithLifecycle()
    val activeProfileId =
        serverState.selectedProfileId ?: AuthManager.DEFAULT_PROFILE_ID

    // Publish the notification session ID to the ViewModel synchronously
    SideEffect {
        viewModel.initialSessionId = sessionId
    }

    // Switch to session from notification/history
    val pendingSessionTarget = NavigationController.pendingSessionTarget
    LaunchedEffect(
        sessionId,
        pendingSessionTarget,
        connectionStatus,
        activeProfileId,
    ) {
        val pendingSessionId =
            pendingSessionTarget?.idForProfile(activeProfileId)
        if (
            pendingSessionTarget != null &&
            pendingSessionId == null &&
            pendingSessionTarget == NavigationController.pendingSessionTarget
        ) {
            NavigationController.pendingSessionTarget = null
        }

        if (connectionStatus != ConnectionStatus.CONNECTED) return@LaunchedEffect

        val target =
            if (!sessionId.isNullOrBlank()) sessionId else pendingSessionId
        if (!target.isNullOrBlank()) {
            viewModel.switchSession(target)
            if (
                target == pendingSessionId &&
                pendingSessionTarget == NavigationController.pendingSessionTarget
            ) {
                NavigationController.pendingSessionTarget = null
            }
        }
    }

    // Land instantly at the bottom on a session switch (issue #682).
    LaunchedEffect(currentSessionId) {
        if (currentSessionId != null) {
            scrollController.jumpToBottom(animated = false)
        }
    }

    // Refresh chat state when the app returns to the foreground. MainActivity
    // owns socket/service foreground transitions so they apply on every screen.
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        viewModel.refreshSettings()
                        viewModel.refreshCurrentSession()
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Request POST_NOTIFICATIONS permission on Android 13+
    val requestNotificationPermission =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { /* granted */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(permission)
            }
        }
    }

    // Show error as snackbar
    LaunchedEffect(errorMessage) {
        errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // Show background-complete as a non-blocking snackbar (issue #527)
    LaunchedEffect(backgroundCompleteMessage) {
        backgroundCompleteMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearBackgroundComplete()
        }
    }

    // Show attachment-open failures as a non-blocking snackbar (issue #724)
    LaunchedEffect(openError) {
        openError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearOpenError()
        }
    }

    // Sudo / secret prompt dialogs (issue #524)
    sudoPrompt?.let { prompt ->
        SudoPromptDialog(
            onConfirm = viewModel::respondToSudo,
            onDismiss = viewModel::dismissSudo,
        )
    }

    secretPrompt?.let { prompt ->
        SecretPromptDialog(
            onConfirm = viewModel::respondToSecret,
            onDismiss = viewModel::dismissSecret,
        )
    }

    // Scroll to current search match (serialized through the controller so it
    // doesn't compete with auto-follow / FAB / send scrolls).
    LaunchedEffect(isSearchActive, currentSearchMatchIndex, searchMatchIndices) {
        if (isSearchActive &&
            currentSearchMatchIndex >= 0 &&
            currentSearchMatchIndex < searchMatchIndices.size &&
            messages.isNotEmpty()
        ) {
            val targetIndex = searchMatchIndices[currentSearchMatchIndex]
            scrollController.scrollToSearchMatch(targetIndex.coerceIn(0, messages.lastIndex))
        }
    }
}
