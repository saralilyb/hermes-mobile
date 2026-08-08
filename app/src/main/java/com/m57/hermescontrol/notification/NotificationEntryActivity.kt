package com.m57.hermescontrol.notification

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager

/**
 * Private trampoline for chat-notification taps.
 *
 * MainActivity must remain exported for the launcher, so it never consumes
 * notification extras. This non-exported activity receives the immutable
 * content PendingIntent, validates its application action and source profile,
 * stores the in-process navigation request, and then opens MainActivity.
 */
class NotificationEntryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (
            val decision =
                resolveNotificationEntry(
                    action = intent?.action,
                    applicationId = packageName,
                    sessionId =
                        intent?.getStringExtra(
                            EXTRA_NOTIFICATION_SESSION_ID,
                        ),
                    sourceProfileId =
                        intent?.getStringExtra(EXTRA_NOTIFICATION_PROFILE_ID),
                    activeProfileId = AuthManager.getSelectedProfileId(),
                )
        ) {
            is NotificationEntryDecision.OpenChat -> {
                NavigationController.queuePendingSession(
                    sessionId = decision.sessionId,
                    profileId = decision.profileId,
                )
                NavigationController.navigateTo(ChatScreen)
            }

            NotificationEntryDecision.OpenApp -> Unit
            NotificationEntryDecision.Reject -> {
                finish()
                return
            }
        }

        startActivity(
            Intent(this, MainActivity::class.java)
                .setFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
        )
        finish()
    }
}
