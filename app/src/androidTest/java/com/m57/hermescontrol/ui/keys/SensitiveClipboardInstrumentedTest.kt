package com.m57.hermescontrol.ui.keys

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensitiveClipboardInstrumentedTest {
    @Test
    fun copySensitiveText_marksPrimaryClipSensitive() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)

        copySensitiveText(
            context = context,
            label = "EXAMPLE_API_KEY",
            text = "example-secret-value",
        )

        val clip = clipboard.primaryClip
        assertNotNull(clip)
        assertEquals("example-secret-value", clip?.getItemAt(0)?.text.toString())
        assertTrue(
            clip
                ?.description
                ?.extras
                ?.getBoolean(sensitiveClipboardExtraKey()) == true,
        )
    }
}
