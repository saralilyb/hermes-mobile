package com.m57.hermescontrol.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.m57.hermescontrol.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen image viewer (issue #723).
 *
 * Opens from any chat image (bubble thumbnail, markdown `![alt](url)`, inline
 * attachment). Provides pinch-zoom + pan, a downswipe-to-dismiss gesture, and a
 * top bar with **Save** (writes to device Downloads via [MediaImageStore]) and
 * **Share** (Android share sheet) — both fully device-local, never touching the
 * Hermes server.
 *
 * The viewer resolves image bytes once through [ImageBytesResolver], then reuses
 * them for rendering, Save, and Share. Gateway paths use authenticated fetches;
 * local, data, and ordinary HTTP sources use their corresponding resolvers.
 */
@Composable
fun ImageViewerDialog(
    image: ImageViewerModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var isBusy by remember { mutableStateOf(false) }
    var pendingLegacySave by remember { mutableStateOf(false) }
    var resolvedImage by remember(image) {
        mutableStateOf<ImageBytesResolver.Result?>(null)
    }

    // Resolve string resources at composable scope (Lint forbids reading
    // resource *values* from LocalContext.current inside coroutine lambdas).
    val savedMsg = stringResource(R.string.image_viewer_saved)
    val saveFailedMsg = stringResource(R.string.image_viewer_save_failed)
    val loadFailedFmt = stringResource(R.string.image_viewer_load_failed)
    val shareTitle = stringResource(R.string.image_viewer_share_title)
    val shareFailedMsg = stringResource(R.string.image_viewer_share_failed)

    LaunchedEffect(image) {
        resolvedImage =
            ImageBytesResolver.resolve(
                context = context,
                model = image.model,
                fallbackMime = image.mimeType,
                gatewayPath = image.gatewayPath,
            )
    }

    val saveResolvedImage: (ImageBytesResolver.Result.Bytes) -> Unit = { resolved ->
        if (!isBusy) {
            isBusy = true
            scope.launch(Dispatchers.IO) {
                val name = image.name.ifBlank { "hermes-image.${resolved.extension}" }
                val uri =
                    MediaImageStore.saveToDownloads(
                        context,
                        resolved.bytes,
                        name,
                        resolved.mimeType,
                    )
                val result =
                    if (uri != null) {
                        savedMsg
                    } else {
                        saveFailedMsg
                    }
                withContext(Dispatchers.Main) {
                    isBusy = false
                    Toast.makeText(context, result, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val storagePermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            val resolved = resolvedImage as? ImageBytesResolver.Result.Bytes
            if (granted && pendingLegacySave && resolved != null) {
                saveResolvedImage(resolved)
            } else if (!granted) {
                Toast.makeText(context, saveFailedMsg, Toast.LENGTH_SHORT).show()
            }
            pendingLegacySave = false
        }

    val onSave: () -> Unit = {
        val resolved = resolvedImage as? ImageBytesResolver.Result.Bytes
        if (!isBusy && resolved != null && requiresLegacyStoragePermission(context)) {
            pendingLegacySave = true
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else if (!isBusy && resolved != null) {
            saveResolvedImage(resolved)
        }
    }

    val onShare: () -> Unit = {
        val resolved = resolvedImage as? ImageBytesResolver.Result.Bytes
        if (!isBusy && resolved != null) {
            isBusy = true
            scope.launch(Dispatchers.IO) {
                val name = image.name.ifBlank { "hermes-image.${resolved.extension}" }
                val intent =
                    MediaImageStore.buildShareIntent(
                        context,
                        resolved.bytes,
                        name,
                        resolved.mimeType,
                    )
                withContext(Dispatchers.Main) {
                    isBusy = false
                    if (intent != null) {
                        context.startActivity(
                            android.content.Intent
                                .createChooser(intent, shareTitle),
                        )
                    } else {
                        Toast
                            .makeText(
                                context,
                                shareFailedMsg,
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.scrim,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Resolve once so gateway images display through authenticated
                // bytes and save/share reuse the same download.
                when (val resolved = resolvedImage) {
                    null ->
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )

                    is ImageBytesResolver.Result.Error ->
                        Text(
                            text = String.format(loadFailedFmt, resolved.message),
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )

                    is ImageBytesResolver.Result.Bytes ->
                        AsyncImage(
                            model = resolved.bytes,
                            contentDescription =
                                image.name.ifBlank { stringResource(R.string.image_viewer_content_desc) },
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offsetX,
                                        translationY = offsetY,
                                    ).pointerInput(Unit) {
                                        detectTransformGestures(
                                            onGesture = { _, pan, zoom, _ ->
                                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                                scale = newScale
                                                offsetX = if (newScale > 1f) offsetX + pan.x else 0f
                                                offsetY += pan.y
                                                if (newScale <= 1f && offsetY > 120f) {
                                                    onDismiss()
                                                }
                                            },
                                        )
                                    },
                            contentScale = ContentScale.Fit,
                        )
                }

                // Top action bar — Save / Share / Close.
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.image_viewer_close),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Row {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp).padding(horizontal = 8.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            IconButton(
                                onClick = onSave,
                                enabled = resolvedImage is ImageBytesResolver.Result.Bytes,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Download,
                                    contentDescription = stringResource(R.string.image_viewer_save),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            IconButton(
                                onClick = onShare,
                                enabled = resolvedImage is ImageBytesResolver.Result.Bytes,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Share,
                                    contentDescription = stringResource(R.string.image_viewer_share),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun requiresLegacyStoragePermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) != PackageManager.PERMISSION_GRANTED
