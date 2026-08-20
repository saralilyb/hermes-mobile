package com.m57.hermescontrol.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LocaleContextWrapperTest {
    @Test
    fun systemReturnsDeviceDefault() {
        assertEquals(Locale.getDefault(), LocaleContextWrapper.localeForCode("system"))
    }

    @Test
    fun emptyReturnsDeviceDefault() {
        assertEquals(Locale.getDefault(), LocaleContextWrapper.localeForCode(""))
    }

    @Test
    fun bareLanguageCode() {
        val locale = LocaleContextWrapper.localeForCode("ko")
        assertEquals("ko", locale.language)
        assertEquals("", locale.country)
    }

    @Test
    fun japaneseLanguageTag() {
        assertEquals("ja", LocaleContextWrapper.localeForCode("ja").toLanguageTag())
    }

    @Test
    fun regionSeparator_r() {
        val locale = LocaleContextWrapper.localeForCode("zh-rCN")
        assertEquals("zh", locale.language)
        assertEquals("CN", locale.country)
    }

    @Test
    fun regionSeparator_hyphen() {
        val locale = LocaleContextWrapper.localeForCode("pt-BR")
        assertEquals("pt", locale.language)
        assertEquals("BR", locale.country)
    }

    @Test
    fun regionSeparator_underscore() {
        val locale = LocaleContextWrapper.localeForCode("en_US")
        assertEquals("en", locale.language)
        assertEquals("US", locale.country)
    }

    // ── shouldWrap ────────────────────────────────────────────────────────
    // Wrapping pins a Configuration onto the returned context. On a foldable
    // that context keeps reporting the display the process attached to, so the
    // "follow the system" preference — which gains nothing from a wrap — must
    // not pay for one.

    @Test
    fun systemPreferenceIsNotWrapped() {
        assertFalse(LocaleContextWrapper.shouldWrap(LocaleContextWrapper.SYSTEM_LANGUAGE))
    }

    @Test
    fun emptyPreferenceIsNotWrapped() {
        assertFalse(LocaleContextWrapper.shouldWrap(""))
    }

    @Test
    fun explicitLanguageIsWrapped() {
        assertTrue(LocaleContextWrapper.shouldWrap("ko"))
    }

    @Test
    fun explicitLanguageWithRegionIsWrapped() {
        assertTrue(LocaleContextWrapper.shouldWrap("pt-BR"))
    }
}
