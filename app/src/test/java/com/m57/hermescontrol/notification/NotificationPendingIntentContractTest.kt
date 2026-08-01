package com.m57.hermescontrol.notification

import android.app.PendingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPendingIntentContractTest {
    @Test
    fun notificationActionsArePackageScoped() {
        val packageName = "sh.slb.example"

        assertEquals(
            "$packageName.ACTION_NOTIFICATION_REPLY",
            notificationReplyAction(packageName),
        )
        assertEquals(
            "$packageName.ACTION_OPEN_CHAT_FROM_NOTIFICATION",
            notificationContentAction(packageName),
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
