package com.m57.hermescontrol.util

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
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
     * "system" returns the device's default locale. Codes containing a region
     * separator ("zh-rCN", "pt-BR") are split into language + country.
     */
    fun localeForCode(code: String): Locale =
        when {
            code.isEmpty() || code == SYSTEM_LANGUAGE -> Locale.getDefault()
            code.contains("-r", ignoreCase = true) -> {
                val parts = code.split("-r", ignoreCase = true)
                Locale(parts[0], parts.getOrElse(1) { "" })
            }
            code.contains("-") -> {
                val parts = code.split("-")
                Locale(parts[0], parts.getOrElse(1) { "" })
            }
            code.contains("_") -> {
                val parts = code.split("_")
                Locale(parts[0], parts.getOrElse(1) { "" })
            }
            else -> Locale(code)
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
    ): Context {
        // Deliberately a SPARSE override, not a copy of the base configuration.
        // An override masks the base on every field it sets, so
        // `Configuration(base.resources.configuration)` would pin
        // screenWidthDp/screenHeightDp/smallestScreenWidthDp/orientation to
        // their attach-time values for the life of the context — exactly the
        // geometry a foldable changes while the process is alive.
        //
        // `Configuration()` calls setToDefaults(), which leaves the size fields
        // UNDEFINED (correctly unset) but sets fontScale to 1.0. A non-zero
        // fontScale IS treated as an override, so it must be zeroed or the
        // user's system font scale would be silently discarded.
        val newConfig =
            android.content.res.Configuration().apply {
                fontScale = 0f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setLocales(android.os.LocaleList(locale))
                } else {
                    @Suppress("DEPRECATION")
                    setLocale(locale)
                }
            }
        return base.createConfigurationContext(newConfig)
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
