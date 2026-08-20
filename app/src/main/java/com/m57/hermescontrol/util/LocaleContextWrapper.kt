package com.m57.hermescontrol.util

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Static helpers for applying a user-selected in-app display language.
 *
 * Hermes Mobile's [com.m57.hermescontrol.MainActivity] extends the plain
 * `ComponentActivity` (not `AppCompatActivity`), so we apply the locale through
 * `attachBaseContext` + [ContextWrapper.wrap] rather than relying on
 * AppCompatDelegate. Works on minSdk 26.
 */
object LocaleContextWrapper {
    const val SYSTEM_LANGUAGE = "system"

    /**
     * Whether [code] asks for an explicit in-app language override.
     *
     * `"system"` (and an empty preference) must NOT be wrapped. A wrap is not
     * free even when the resolved locale equals the device default:
     * [Context.createConfigurationContext] pins a full [Configuration] copy —
     * including `screenWidthDp`, `screenHeightDp`, `smallestScreenWidthDp` and
     * `orientation` — onto the returned context's resources. Any Compose code
     * reading `LocalConfiguration` from that context then sees the window
     * geometry captured at `attachBaseContext` time, which is the wrong size on
     * a foldable that changes displays while the process is alive. Only users
     * who deliberately selected a language should pay that cost.
     */
    fun shouldWrap(code: String): Boolean = code.isNotEmpty() && code != SYSTEM_LANGUAGE

    /**
     * Resolve a language code ("system", "en", "ko", …) into a [Locale].
     *
     * "system" returns the device's default locale. Android resource-style
     * region separators (for example, "zh-rCN") are normalized to BCP 47.
     */
    fun localeForCode(code: String): Locale =
        when {
            code.isEmpty() || code == SYSTEM_LANGUAGE -> Locale.getDefault()
            else -> Locale.forLanguageTag(code.replace("-r", "-").replace('_', '-'))
        }

    /**
     * Wrap [base] so the configuration carries [locale].
     *
     * On API 33+ we use [Context.createConfigurationContext] which is the only
     * reliable path once the deprecated `Configuration.locale` / `setLocale`
     * APIs were removed from the public surface.
     */
    fun wrap(
        base: Context,
        locale: Locale,
    ): Context = base.createConfigurationContext(overrideConfigurationFor(locale))

    /**
     * Build the configuration override handed to
     * [Context.createConfigurationContext] — locale only, nothing else.
     *
     * Deliberately a SPARSE override, not a copy of the base configuration. An
     * override masks the base on every field it sets, so
     * `Configuration(base.resources.configuration)` would pin
     * `screenWidthDp` / `screenHeightDp` / `smallestScreenWidthDp` /
     * `orientation` to their attach-time values for the life of the context —
     * exactly the geometry a foldable changes while the process is alive.
     *
     * [Configuration]'s no-arg constructor calls `setToDefaults()`, which
     * leaves the size fields UNDEFINED (correctly unset) but sets `fontScale`
     * to 1.0. A non-zero `fontScale` IS treated as an override, so it must be
     * zeroed or the user's system font scale would be silently discarded.
     *
     * Exposed (rather than inlined into [wrap]) so an instrumented test can
     * assert the override really is sparse; the pinning defect is invisible
     * from the returned [Context] alone.
     */
    fun overrideConfigurationFor(locale: Locale): Configuration =
        Configuration().apply {
            fontScale = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setLocales(LocaleList(locale))
            } else {
                @Suppress("DEPRECATION")
                setLocale(locale)
            }
        }

    /**
     * Convenience: wrap [base] using a stored language code.
     *
     * Returns [base] untouched for the "follow the system" preference — see
     * [shouldWrap]. Wrapping there would buy nothing (the locale is already the
     * device default) while freezing the window geometry into the returned
     * context.
     */
    fun wrapWithCode(
        base: Context,
        code: String,
    ): Context =
        if (shouldWrap(code)) {
            wrap(base, localeForCode(code))
        } else {
            base
        }
}
