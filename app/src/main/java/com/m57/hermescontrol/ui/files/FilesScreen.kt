@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.m57.hermescontrol.ui.files

import android.content.Intent
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ManagedFileEntry
import com.m57.hermescontrol.data.remote.readBytesLimited
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SecureGatewayMediaPlayer
import com.m57.hermescontrol.ui.common.SecureGatewayMediaRequest
import com.m57.hermescontrol.ui.common.SecureMediaOpenRoute
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.routeManagedFileOpen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

private val filesContentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
private val filesItemSpacing = Arrangement.spacedBy(8.dp)

@Composable
fun FilesScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: FilesViewModel = viewModel { FilesViewModel() },
    launchDownloadedFile: ((DownloadedFile) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mediaPlayerRequest by remember { mutableStateOf<SecureGatewayMediaRequest?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.refresh()
    }

    val uploadLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
            onResult = { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                val fileName =
                    uri.lastPathSegment?.substringAfterLast("/")?.takeIf { it.isNotBlank() }
                        ?: "upload.bin"
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                scope.launch {
                    val bytes =
                        runCatching {
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)?.use { stream ->
                                    stream.readBytesLimited()
                                }
                            }
                        }.getOrElse {
                            if (it is CancellationException) throw it
                            viewModel.showToast(
                                FilesUiText(
                                    R.string.files_error_read_selected,
                                    listOf(it.message.orEmpty()),
                                ),
                            )
                            return@launch
                        }
                    if (bytes == null) {
                        viewModel.showToast(FilesUiText(R.string.files_error_selected_unavailable_or_too_large))
                        return@launch
                    }
                    viewModel.uploadFile(fileName, bytes, mimeType)
                }
            },
        )

    DisposableEffect(Unit) {
        onDispose { viewModel.clearTransientState() }
    }

    val hasEntries = state.entries.isNotEmpty()

    ToastEffect(
        toastMessage = state.toastMessage?.resolve(context),
        onClearToast = viewModel::clearToast,
    )

    // ── Create-directory dialog ────────────────────────────────────────
    if (state.isCreatingDir) {
        CreateDirDialog(
            name = state.newDirName,
            onNameChange = viewModel::setNewDirName,
            onConfirm = viewModel::createDir,
            onDismiss = viewModel::dismissCreateDir,
        )
    }

    // ── Delete confirmation dialog ─────────────────────────────────────
    state.deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.files_dialog_delete_title))
                }
            },
            text = {
                Text(
                    stringResource(
                        R.string.files_dialog_delete_message,
                        entry.name,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmDelete,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text(stringResource(R.string.files_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_files)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = viewModel::refresh,
        actions = {
            if (state.parentPath != null) {
                IconButton(onClick = viewModel::navigateUp) {
                    Icon(
                        imageVector = Icons.Filled.ArrowUpward,
                        contentDescription = stringResource(R.string.files_action_up),
                    )
                }
            }
            IconButton(onClick = { uploadLauncher.launch("*/*") }) {
                Icon(
                    imageVector = Icons.Filled.Upload,
                    contentDescription = stringResource(R.string.files_action_upload),
                )
            }
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading && !hasEntries -> {
                    SkeletonListState(modifier = Modifier.padding(paddingValues))
                }

                state.errorMessage != null && !hasEntries -> {
                    ErrorState(
                        message = state.errorMessage?.resolve(context).orEmpty(),
                        onRetry = viewModel::refresh,
                        modifier = Modifier.padding(paddingValues),
                    )
                }

                !hasEntries -> {
                    EmptyState(
                        title = stringResource(R.string.files_empty_title),
                        subtitle = stringResource(R.string.files_empty_desc),
                        onAction = viewModel::refresh,
                        actionLabel = stringResource(R.string.content_desc_refresh),
                        modifier = Modifier.padding(paddingValues),
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = filesContentPadding,
                        verticalArrangement = filesItemSpacing,
                    ) {
                        // ── Breadcrumb bar ────────────────────────────────
                        item(key = "breadcrumb") {
                            BreadcrumbBar(
                                crumbs = state.crumbs,
                                onCrumbClick = viewModel::navigateToCrumb,
                            )
                        }

                        // ── Up row (when not at root) ─────────────────────
                        if (state.parentPath != null) {
                            item(key = "up") {
                                FileRow(
                                    icon = Icons.Filled.ArrowUpward,
                                    name = "..",
                                    subtitle = stringResource(R.string.files_action_up),
                                    isBusy = false,
                                    onClick = viewModel::navigateUp,
                                )
                            }
                        }

                        // ── Entries ───────────────────────────────────────
                        items(
                            items = state.entries,
                            key = { it.path },
                        ) { entry ->
                            FileRow(
                                entry = entry,
                                isBusy = entry.path in state.busyPaths || state.openingPath == entry.path,
                                onClick = {
                                    if (entry.isDirectory) {
                                        viewModel.navigateTo(entry)
                                    } else {
                                        when (val route = routeManagedFileOpen(entry)) {
                                            is SecureMediaOpenRoute.Player -> mediaPlayerRequest = route.request
                                            SecureMediaOpenRoute.DownloadAndOpen ->
                                                openManagedFile(entry, viewModel, context, scope, launchDownloadedFile)

                                            SecureMediaOpenRoute.OpenLocal -> Unit
                                        }
                                    }
                                },
                                onDelete = { viewModel.requestDelete(entry) },
                            )
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = viewModel::openCreateDir,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Filled.CreateNewFolder,
                    contentDescription = stringResource(R.string.files_action_new_folder),
                )
            }
        }
    }

    mediaPlayerRequest?.let { request ->
        SecureGatewayMediaPlayer(request = request, onClose = { mediaPlayerRequest = null })
    }
}

// ── Breadcrumb bar ───────────────────────────────────────────────────────────

@Composable
private fun BreadcrumbBar(
    crumbs: List<Pair<String, String>>,
    onCrumbClick: (String) -> Unit,
) {
    if (crumbs.isEmpty()) {
        Text(
            text = stringResource(R.string.files_root_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        return
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, (path, name) ->
            if (index > 0) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .clickable { onCrumbClick(path) }
                        .padding(horizontal = 2.dp),
            )
        }
    }
}

// ── File row ─────────────────────────────────────────────────────────────────

@Composable
private fun FileRow(
    entry: ManagedFileEntry? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector =
        entry?.let { fileIcon(it) } ?: Icons.Filled.Folder,
    name: String = entry?.name ?: "",
    subtitle: String? = null,
    isBusy: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val resolvedSubtitle = subtitle ?: entry?.let { fileSubtitle(it) }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !isBusy, onClick = onClick),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (resolvedSubtitle != null) {
                    Text(
                        text = resolvedSubtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else if (onDelete != null && entry != null && !entry.isDirectory) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.files_action_delete_named, entry.name),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ── Create directory dialog ──────────────────────────────────────────────────

@Composable
private fun CreateDirDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_dialog_new_folder_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.files_new_folder_label)) },
                placeholder = { Text(stringResource(R.string.files_new_folder_placeholder)) },
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.files_action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun fileIcon(entry: ManagedFileEntry): androidx.compose.ui.graphics.vector.ImageVector =
    when {
        entry.isDirectory -> Icons.Filled.Folder
        entry.mimeType?.startsWith("image/") == true -> Icons.Outlined.Image
        entry.mimeType?.startsWith("text/") == true -> Icons.Outlined.Description
        else -> Icons.Outlined.AttachFile
    }

@Composable
private fun fileSubtitle(entry: ManagedFileEntry): String {
    val context = LocalContext.current
    val size =
        entry.size?.let { bytes -> Formatter.formatShortFileSize(context, bytes) } ?: "—"
    val mtime =
        entry.mtime
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { seconds -> Date((seconds * 1000.0).toLong()) }
            ?.let { date ->
                val formattedDate = DateFormat.getMediumDateFormat(context).format(date)
                val formattedTime = DateFormat.getTimeFormat(context).format(date)
                stringResource(
                    R.string.files_metadata_date_time,
                    formattedDate,
                    formattedTime,
                )
            }
    return if (mtime == null) {
        size
    } else {
        stringResource(R.string.files_metadata_size_and_time, size, mtime)
    }
}

/**
 * Download a managed file **in-app** through the authenticated client and open
 * it on the device via a `FileProvider`-shared URI + `ACTION_VIEW`.
 *
 * Fetching through [FilesViewModel.downloadFile] reuses the normal
 * Bearer/session-cookie client. The file is materialized locally before
 * viewing, so credentials never leave the app in a browser URL.
 */
private fun openManagedFile(
    entry: ManagedFileEntry,
    viewModel: FilesViewModel,
    context: android.content.Context,
    scope: CoroutineScope,
    launchDownloadedFile: ((DownloadedFile) -> Unit)? = null,
) {
    viewModel.downloadFile(entry, File(context.cacheDir, "gateway_media")) { result ->
        when (result) {
            is com.m57.hermescontrol.data.remote.NetworkResult.Success -> {
                val data = result.data
                if (launchDownloadedFile != null) {
                    launchDownloadedFile(data)
                    return@downloadFile
                }
                scope.launch {
                    val intent =
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val uri =
                                    FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        data.cacheFile,
                                    )
                                Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, data.mimeType)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            }
                        }.getOrElse {
                            if (it is CancellationException) throw it
                            viewModel.showToast(
                                FilesUiText(
                                    R.string.files_error_open,
                                    listOf(it.message.orEmpty()),
                                ),
                            )
                            return@launch
                        }
                    runCatching { context.startActivity(intent) }.onFailure {
                        viewModel.showToast(
                            FilesUiText(
                                R.string.files_error_open,
                                listOf(it.message.orEmpty()),
                            ),
                        )
                    }
                }
            }

            is com.m57.hermescontrol.data.remote.NetworkResult.Failure -> {
                val message =
                    when (val error = result.error) {
                        is com.m57.hermescontrol.data.remote.NetworkError.AuthExpired ->
                            FilesUiText(R.string.files_error_session_expired)

                        is com.m57.hermescontrol.data.remote.NetworkError.Http ->
                            when (error.code) {
                                403 -> FilesUiText(R.string.files_error_access_forbidden)
                                404 -> FilesUiText(R.string.files_error_not_found)
                                413 -> FilesUiText(R.string.files_error_too_large_to_open)
                                else ->
                                    FilesUiText(
                                        R.string.files_error_open_failed,
                                        listOf(error.message),
                                    )
                            }

                        else ->
                            FilesUiText(
                                R.string.files_error_open_failed,
                                listOf(error.message),
                            )
                    }
                viewModel.showToast(message)
            }
        }
    }
}
