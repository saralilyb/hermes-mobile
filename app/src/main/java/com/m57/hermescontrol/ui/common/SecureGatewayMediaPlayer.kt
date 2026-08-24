package com.m57.hermescontrol.ui.common

import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.remote.GatewayFileClient
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
internal fun SecureGatewayMediaPlayer(
    request: SecureGatewayMediaRequest,
    onClose: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var prepared by remember(request) { mutableStateOf(false) }
    var playing by remember(request) { mutableStateOf(false) }
    var position by remember(request) { mutableIntStateOf(0) }
    var duration by remember(request) { mutableIntStateOf(0) }
    var error by remember(request) { mutableStateOf(false) }
    var seeking by remember(request) { mutableStateOf(false) }
    val seekDescription = stringResource(R.string.media_player_seek)
    val dataSource = remember(request) { GatewayMediaDataSource(GatewayFileClient.openSeekable(request.path)) }
    val player =
        remember(request) {
            MediaPlayer().apply {
                setDataSource(dataSource)
                setOnPreparedListener {
                    prepared = true
                    duration = it.duration.coerceAtLeast(0)
                    it.start()
                    playing = true
                }
                setOnCompletionListener {
                    playing = false
                    position = duration
                }
                setOnErrorListener { _, _, _ ->
                    playing = false
                    error = true
                    true
                }
                prepareAsync()
            }
        }

    LaunchedEffect(player, dataSource) {
        while (true) {
            if (!dataSource.isCurrent()) {
                dataSource.close()
                runCatching { player.stop() }
                runCatching { player.reset() }
                runCatching { player.release() }
                prepared = false
                playing = false
                error = true
                return@LaunchedEffect
            }
            if (playing && !seeking) position = runCatching { player.currentPosition }.getOrDefault(position)
            delay(250)
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP && runCatching { player.isPlaying }.getOrDefault(false)) {
                    player.pause()
                    playing = false
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            dataSource.close()
            runCatching { player.stop() }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = request.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.media_player_close))
                    }
                }

                if (request.isVideo) {
                    AndroidView(
                        factory = { context ->
                            SurfaceView(context).also { surface ->
                                surface.holder.addCallback(
                                    object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) {
                                            runCatching { player.setDisplay(holder) }
                                        }

                                        override fun surfaceChanged(
                                            holder: SurfaceHolder,
                                            format: Int,
                                            width: Int,
                                            height: Int,
                                        ) = Unit

                                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                                            runCatching { player.setDisplay(null) }
                                        }
                                    },
                                )
                            }
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .background(Color.Black)
                                .semantics {
                                    contentDescription = request.title
                                },
                    )
                }

                if (!prepared && !error) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                if (error) {
                    Text(
                        text = stringResource(R.string.media_player_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Slider(
                    value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                    onValueChange = {
                        seeking = true
                        position = it.toInt()
                    },
                    onValueChangeFinished = {
                        if (prepared) player.seekTo(position)
                        seeking = false
                    },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    enabled = prepared && !error && duration > 0,
                    modifier =
                        Modifier.semantics {
                            contentDescription = seekDescription
                        },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${formatMediaTime(position)} / ${formatMediaTime(duration)}")
                    IconButton(
                        onClick = {
                            if (playing) {
                                player.pause()
                                playing = false
                            } else {
                                if (duration > 0 && position >= duration) player.seekTo(0)
                                player.start()
                                playing = true
                            }
                        },
                        enabled = prepared && !error,
                    ) {
                        Icon(
                            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription =
                                stringResource(
                                    if (playing) R.string.media_player_pause else R.string.media_player_play,
                                ),
                        )
                    }
                }
            }
        }
    }
}

private fun formatMediaTime(milliseconds: Int): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
    return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
