package com.m57.hermescontrol.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.io.File

/** The only production compatibility boundary allowed to reference deprecated security-crypto APIs. */
internal class LegacySecurePreferences(private val context: Context) {
    fun auth(): SharedPreferences? = openIfPresent(AUTH_FILE)

    fun cookies(): SharedPreferences? = openIfPresent(COOKIE_FILE)

    private fun openIfPresent(name: String): SharedPreferences? {
        // EncryptedSharedPreferences.create creates keysets. Never invoke it on a fresh install.
        val file = legacyFile(name)
        if (!file.exists() && !file.resolveSibling(file.name + ".bak").exists()) return null
        return try {
            val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                name,
                masterKey,
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (error: Exception) {
            // Existing ciphertext that cannot be opened is corruption or a
            // Keystore failure, never a fresh install. Propagate so callers do
            // not silently mint a replacement token or SQLCipher password.
            throw SecureBlobException(error)
        }
    }

    private fun legacyFile(name: String) =
        context.applicationInfo.dataDir
            .let(::File)
            .resolve("shared_prefs/$name.xml")

    companion object {
        const val AUTH_FILE = "hermes_secure_prefs"
        const val COOKIE_FILE = "hermes_secure_cookies"
    }
}
