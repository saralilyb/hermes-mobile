package com.m57.hermescontrol.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationPendingIntentTest {
    @Test
    fun replyIntentIsExplicitAndPackageScoped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionId = "session-123"

        val intent = buildNotificationReplyIntent(context, sessionId)

        assertEquals(
            "${context.packageName}.ACTION_NOTIFICATION_REPLY",
            intent.action,
        )
        assertEquals(
            NotificationReplyReceiver::class.java.name,
            intent.component?.className,
        )
        assertEquals(
            sessionId,
            intent.getStringExtra(NotificationReplyReceiver.EXTRA_SESSION_ID),
        )
    }

    @Test
    fun contentIntentIsExplicitAndPackageScoped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionId = "session-123"

        val intent = buildNotificationContentIntent(context, sessionId)

        assertEquals(
            "${context.packageName}.ACTION_OPEN_CHAT_FROM_NOTIFICATION",
            intent.action,
        )
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            intent.flags,
        )
        assertEquals(
            sessionId,
            intent.getStringExtra(NotificationReplyReceiver.EXTRA_SESSION_ID),
        )
    }

    @Test
    fun pendingIntentsAreOneShotWithRequiredMutability() {
        assertTrue(replyPendingIntentFlags and PendingIntent.FLAG_ONE_SHOT != 0)
        assertTrue(replyPendingIntentFlags and PendingIntent.FLAG_MUTABLE != 0)
        assertTrue(replyPendingIntentFlags and PendingIntent.FLAG_UPDATE_CURRENT != 0)

        assertTrue(contentPendingIntentFlags and PendingIntent.FLAG_ONE_SHOT != 0)
        assertTrue(contentPendingIntentFlags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(contentPendingIntentFlags and PendingIntent.FLAG_UPDATE_CURRENT != 0)
    }
}
