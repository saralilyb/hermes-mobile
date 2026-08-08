// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that keeps the WebSocket connection alive while the app
 * is backgrounded and posts a notification when a new assistant reply
 * completes (MessageComplete event) while the app is not in the foreground.
 *
 * FGS type: `remoteMessaging` — indefinite listener that maintains a
 * long-lived connection to a remote server for receiving messages. This is
 * the correct type per Android 14+ policy (dataSync has a time budget and
 * is intended for finite sync operations).
 *
 * Channel importance: IMPORTANCE_MIN — the ongoing notification is a
 * persistent indicator, not an alert. PRIORITY_MIN matches.
 * The separate `hermes_chat` channel uses IMPORTANCE_HIGH for actual
 * message notifications, which is correct.
 *
 * Lifecycle:
 * - Started by [NotificationHelper.start] when the user sends a message and
 *   the app is about to go to the background (called from ChatScreen's
 *   onStop / onPause).
 * - Stopped by [NotificationHelper.stop] when the app returns to the
 *   foreground (called from ChatScreen's onStart / onResume).
 *
 * The service collects [WsEvent]s from [HermesWsClient] — the same stream
 * the ChatViewModel collects — and watches for [WsEvent.MessageComplete]
 * events that indicate the agent has finished replying.
 */
class ChatNotificationService : Service() {
    companion object {
        internal const val SERVICE_CHANNEL_ID = "hermes_service"
        internal const val CHAT_CHANNEL_ID = "hermes_chat"
        internal const val NOTIFICATION_ID = 1
        internal const val PENDING_NOTIFICATION_ID = 2

        private val isAppInForeground = AtomicBoolean(false)

        fun setAppForeground(foreground: Boolean) {
            isAppInForeground.set(foreground)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var eventCollector: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildForegroundNotification(getString(R.string.notif_waiting_replies)))
        startEventCollection()
    }

    private fun startEventCollection() {
        eventCollector =
            serviceScope.launch {
                HermesWsClient.sourcedEvents.collect { sourcedEvent ->
                    if (!isAppInForeground.get()) {
                        val event = sourcedEvent.event
                        val notificationProfileId = sourcedEvent.profileId
                        launch {
                            delay(500)
                            if (!isAppInForeground.get()) {
                                when (event) {
                                    is WsEvent.MessageComplete -> {
                                        val preview =
                                            event.text
                                                .take(100)
                                                .replace("\n", " ")
                                                .ifBlank { getString(R.string.notif_new_message) }
                                        showReplyNotification(
                                            preview,
                                            event.sessionId,
                                            notificationProfileId,
                                        )
                                    }

                                    is WsEvent.ClarifyRequest -> {
                                        showReplyNotification(
                                            getString(R.string.notif_clarification_needed),
                                            null,
                                            notificationProfileId,
                                        )
                                    }

                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun showReplyNotification(
        text: String,
        sessionId: String?,
        profileId: String?,
    ) {
        val builder =
            NotificationCompat
                .Builder(this, CHAT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(buildContentIntent(sessionId, profileId))

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(PENDING_NOTIFICATION_ID, builder.build())
    }

    private fun buildContentIntent(
        sessionId: String?,
        profileId: String?,
    ): PendingIntent {
        val intent =
            Intent(this, NotificationEntryActivity::class.java)
                .setPackage(packageName)
                .setAction(openChatAction(packageName))
        if (!sessionId.isNullOrBlank()) {
            intent.putExtra(EXTRA_NOTIFICATION_SESSION_ID, sessionId)
        }
        if (!profileId.isNullOrBlank()) {
            intent.putExtra(EXTRA_NOTIFICATION_PROFILE_ID, profileId)
        }
        return PendingIntent.getActivity(
            this,
            31 * (profileId?.hashCode() ?: 0) + (sessionId?.hashCode() ?: 0),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT,
        )
    }

    private fun buildForegroundNotification(text: String): Notification =
        NotificationCompat
            .Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel =
                NotificationChannel(
                    SERVICE_CHANNEL_ID,
                    getString(R.string.notif_channel_service_name),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = getString(R.string.notif_channel_service_desc)
                }

            val chatChannel =
                NotificationChannel(
                    CHAT_CHANNEL_ID,
                    getString(R.string.notif_channel_chat_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = getString(R.string.notif_channel_chat_desc)
                }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(chatChannel)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        eventCollector?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}

/**
 * Helper to start/stop the notification service from the UI layer.
 */
object NotificationHelper {
    fun start(context: Context) {
        if (AuthManager.getToken().isNullOrBlank()) return
        val intent = Intent(context, ChatNotificationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, ChatNotificationService::class.java))
    }

    fun setAppForeground(
        context: Context,
        foreground: Boolean,
    ) {
        ChatNotificationService.setAppForeground(foreground)
    }
}
