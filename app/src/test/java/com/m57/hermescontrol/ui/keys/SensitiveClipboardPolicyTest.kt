package com.m57.hermescontrol.ui.keys

import org.junit.Assert.assertEquals
import org.junit.Test

class SensitiveClipboardPolicyTest {
    @Test
    fun sensitiveExtraKey_usesLegacyLiteralBeforeApi33() {
        assertEquals(
            "android.content.extra.IS_SENSITIVE",
            sensitiveClipboardExtraKey(sdkInt = 26),
        )
    }

    @Test
    fun sensitiveExtraKey_usesPlatformConstantFromApi33() {
        assertEquals(
            "android.content.extra.IS_SENSITIVE",
            sensitiveClipboardExtraKey(sdkInt = 33),
        )
    }
}
