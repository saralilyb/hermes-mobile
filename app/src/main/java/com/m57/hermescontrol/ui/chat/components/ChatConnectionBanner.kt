package com.m57.hermescontrol.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.theme.LocalHermesStatusColors

internal enum class ConnectionBannerAction {
    NONE,
    RECONNECT,
    RELOGIN,
}

/** Only an authoritative authentication failure should ask for credentials. */
internal fun connectionBannerAction(connectionStatus: ConnectionStatus): ConnectionBannerAction =
    when (connectionStatus) {
        ConnectionStatus.DISCONNECTED,
        ConnectionStatus.NO_NETWORK,
        -> ConnectionBannerAction.RECONNECT

        ConnectionStatus.AUTH_EXPIRED -> ConnectionBannerAction.RELOGIN
        ConnectionStatus.RECONNECTING,
        ConnectionStatus.CONNECTING,
        ConnectionStatus.CONNECTED,
        -> ConnectionBannerAction.NONE
    }

/**
 * Top-anchored banner shown when the WebSocket connection is in a non-healthy
 * state (RECONNECTING / DISCONNECTED / NO_NETWORK / AUTH_EXPIRED).
 *
 * Extracted from the original ChatScreen.kt — see issue #621.
 */
@Composable
fun ChatConnectionBanner(
    connectionStatus: ConnectionStatus,
    onReconnect: () -> Unit,
    onReloginClick: () -> Unit,
) {
    val isShown =
        connectionStatus != ConnectionStatus.CONNECTED &&
            connectionStatus != ConnectionStatus.CONNECTING
    AnimatedVisibility(
        visible = isShown,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = LocalHermesStatusColors.current.errorContainer,
            contentColor = LocalHermesStatusColors.current.error,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (connectionStatus == ConnectionStatus.RECONNECTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp).padding(end = 8.dp),
                            strokeWidth = 2.dp,
                            color = LocalHermesStatusColors.current.error,
                        )
                    }
                    Text(
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        text =
                            when (connectionStatus) {
                                ConnectionStatus.RECONNECTING -> stringResource(R.string.chat_status_reconnecting)
                                ConnectionStatus.DISCONNECTED -> stringResource(R.string.chat_status_disconnected)
                                ConnectionStatus.NO_NETWORK -> stringResource(R.string.chat_status_no_network)
                                ConnectionStatus.AUTH_EXPIRED -> stringResource(R.string.chat_status_auth_expired)
                                else -> ""
                            },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                when (connectionBannerAction(connectionStatus)) {
                    ConnectionBannerAction.RECONNECT -> {
                        TextButton(
                            onClick = onReconnect,
                            colors =
                                ButtonDefaults.textButtonColors(
                                    contentColor = LocalHermesStatusColors.current.error,
                                ),
                        ) {
                            Text(stringResource(R.string.chat_action_reconnect))
                        }
                    }

                    ConnectionBannerAction.RELOGIN -> {
                        TextButton(
                            onClick = onReloginClick,
                            colors =
                                ButtonDefaults.textButtonColors(
                                    contentColor = LocalHermesStatusColors.current.error,
                                ),
                        ) {
                            Text(stringResource(R.string.chat_action_relogin))
                        }
                    }

                    ConnectionBannerAction.NONE -> {}
                }
            }
        }
    }
}
