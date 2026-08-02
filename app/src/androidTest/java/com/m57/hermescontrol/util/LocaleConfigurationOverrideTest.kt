package com.m57.hermescontrol.util

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Device-level regression for the foldable configuration-freeze defect.
 *
 * `createConfigurationContext` stores its argument as an *override*. When the
 * display changes, the framework rebuilds the context's configuration by
 * applying that override on top of the new base — `Configuration.updateFrom`,
 * which copies every field the override actually sets and leaves the rest to
 * the base. So an override built by copying the base configuration keeps
 * re-imposing the geometry captured at `attachBaseContext` time, for the life
 * of the process. Unfold the device and every reader still sees the cover
 * display.
 *
 * These tests exercise the real merge the framework performs, rather than
 * asserting on the returned [android.content.Context] — from the outside the
 * defect is invisible until the display actually changes.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class LocaleConfigurationOverrideTest {
    private val korean: Locale = Locale.forLanguageTag("ko")

    /** Geometry a foldable reports after the inner display comes up. */
    private fun postFoldBaseConfiguration(): Configuration {
        val current = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        return Configuration(current).apply {
            screenWidthDp = 1200
            screenHeightDp = 1000
            smallestScreenWidthDp = 900
            orientation = Configuration.ORIENTATION_LANDSCAPE
            fontScale = 1.3f
        }
    }

    @Test
    fun overrideLeavesGeometryUndefined() {
        val override = LocaleContextWrapper.overrideConfigurationFor(korean)

        assertEquals(
            "screenWidthDp must stay unset or it masks the post-fold width",
            Configuration.SCREEN_WIDTH_DP_UNDEFINED,
            override.screenWidthDp,
        )
        assertEquals(
            Configuration.SCREEN_HEIGHT_DP_UNDEFINED,
            override.screenHeightDp,
        )
        assertEquals(
            Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED,
            override.smallestScreenWidthDp,
        )
        assertEquals(
            Configuration.ORIENTATION_UNDEFINED,
            override.orientation,
        )
    }

    @Test
    fun overrideLeavesFontScaleUnset() {
        // updateFrom only copies fontScale when the delta's is > 0, so the
        // default 1.0 from setToDefaults() would silently discard the user's
        // system font scale.
        assertEquals(0f, LocaleContextWrapper.overrideConfigurationFor(korean).fontScale, 0f)
    }

    @Test
    fun geometrySurvivesTheMergeTheFrameworkPerforms() {
        val base = postFoldBaseConfiguration()
        val merged = Configuration(base).apply { updateFrom(LocaleContextWrapper.overrideConfigurationFor(korean)) }

        assertEquals("width was re-pinned to the pre-fold display", 1200, merged.screenWidthDp)
        assertEquals(1000, merged.screenHeightDp)
        assertEquals(900, merged.smallestScreenWidthDp)
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, merged.orientation)
        assertEquals(1.3f, merged.fontScale, 0f)
    }

    @Test
    fun localeStillApplies() {
        val base = postFoldBaseConfiguration()
        val merged = Configuration(base).apply { updateFrom(LocaleContextWrapper.overrideConfigurationFor(korean)) }

        assertEquals("ko", merged.locales[0].language)
    }

    @Test
    fun copyingTheBaseConfigurationWouldRePinGeometry() {
        // Control: the pre-fix shape, kept here so the assertions above are
        // demonstrably discriminating rather than vacuously true.
        val attachTime =
            Configuration(postFoldBaseConfiguration()).apply {
                screenWidthDp = 411
                smallestScreenWidthDp = 411
                orientation = Configuration.ORIENTATION_PORTRAIT
            }
        val copyingOverride = Configuration(attachTime).apply { setLocales(android.os.LocaleList(korean)) }

        val merged = Configuration(postFoldBaseConfiguration()).apply { updateFrom(copyingOverride) }

        assertEquals(411, merged.screenWidthDp)
        assertNotEquals(1200, merged.screenWidthDp)
    }
}
