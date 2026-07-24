package com.m57.hermescontrol.ui.keys

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensitiveClipboardInstrumentedTest {
    @Test
    fun createSensitiveClip_marksDescriptionSensitive() {
        val clip =
            createSensitiveClip(
                label = "EXAMPLE_API_KEY",
                text = "example-secret-value",
            )

        assertEquals("example-secret-value", clip.getItemAt(0).text.toString())
        assertTrue(
            clip
                .description
                .extras
                ?.getBoolean(sensitiveClipboardExtraKey()) == true,
        )
    }
}
