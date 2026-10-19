package com.m57.hermescontrol.theme

import com.m57.hermescontrol.ui.sessions.projectDisplayText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectColorTest {
    @Test fun boundedParserRejectsInvalidAndNonfiniteComponents() {
        listOf(
            null,
            "",
            "url(https://example.com)",
            "#12",
            "hsl(361 20% 30%)",
            "hsl(NaN 20% 30%)",
            "hsl(1 101% 30%)",
            "x".repeat(10000),
        ).forEach { assertNull(parseProjectColor(it)) }
        assertNotNull(parseProjectColor("#123abc"))
        assertNotNull(parseProjectColor("hsl(210 68% 58%)"))
        assertNotNull(parseProjectColor("hsl(210, 68%, 58%)"))
    }

    @Test fun displayRemovesBidiOverridesAndIsolatesWholeLabel() {
        assertEquals("\u2068abc/path\u2069", projectDisplayText("\u202eabc/path\u2069"))
        assertEquals(514, projectDisplayText("a".repeat(10000)).length)
    }
}
