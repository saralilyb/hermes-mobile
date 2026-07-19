// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.ui.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R

@Composable
internal fun ProfileEditorDialog(
    isEditing: Boolean,
    name: String,
    baseUrl: String,
    transportWarning: String?,
    errorMessage: String?,
    token: String,
    onNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title =
        if (isEditing) {
            stringResource(R.string.settings_title_edit_profile)
        } else {
            stringResource(R.string.settings_title_add_profile)
        }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.settings_field_profile_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = {
                        Text(stringResource(R.string.auth_login_base_url_label))
                    },
                    placeholder = {
                        Text("https://hermes.example.com:9119")
                    },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                transportWarning?.let { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                errorMessage?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (isEditing) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = onTokenChange,
                        label = { Text(stringResource(R.string.settings_field_token)) },
                        singleLine = true,
                        visualTransformation =
                            if (token.isNotEmpty()) {
                                PasswordVisualTransformation()
                            } else {
                                VisualTransformation.None
                            },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = name.isNotBlank() && baseUrl.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun DeleteProfileConfirmDialog(
    profileName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_delete_confirm_title)) },
        text = {
            Text(stringResource(R.string.settings_delete_confirm_message, profileName))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
