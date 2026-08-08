package com.m57.hermescontrol.notification

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NotificationEntryManifestTest {
    @Test
    fun notificationEntryActivity_isPrivateAndEphemeral() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info =
            context.packageManager.getActivityInfo(
                ComponentName(context, NotificationEntryActivity::class.java),
                PackageManager.GET_META_DATA,
            )

        assertFalse(info.exported)
        assertTrue(info.flags and ActivityInfo.FLAG_NO_HISTORY != 0)
        assertTrue(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
    }

    @Test
    fun notificationReplyReceiver_isNotRegistered() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component =
            ComponentName(
                context,
                "com.m57.hermescontrol.notification.NotificationReplyReceiver",
            )

        try {
            context.packageManager.getReceiverInfo(
                component,
                PackageManager.GET_META_DATA,
            )
            fail("Quick Reply receiver must not be registered")
        } catch (_: PackageManager.NameNotFoundException) {
            // Expected: profile-agnostic transports cannot send replies safely.
        }
    }
}
