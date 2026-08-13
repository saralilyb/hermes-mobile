package com.m57.hermescontrol.data.security

import android.content.Context
import android.content.SharedPreferences

internal enum class MigrationState { NOT_STARTED, COPYING, VERIFIED, DUAL_WRITE }

/** Coordinates resumable copy/verify and the one-release legacy dual-write window. */
internal interface SecretStore {
    fun getString(logicalKey: String): String?

    fun putString(
        logicalKey: String,
        value: String?,
    )

    fun deletePrefix(prefix: String)
}

internal class SecureStorage(
    context: Context,
    private val migrationObserver: (Int) -> Unit = {},
) : SecretStore {
    private val appContext = context.applicationContext
    private val blobs = SecureBlobStore(appContext)
    private val legacy = LegacySecurePreferences(appContext)
    private val statePrefs = appContext.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)

    init {
        migrate()
    }

    override fun getString(logicalKey: String): String? =
        synchronized(OPERATION_LOCK) {
            if (state() == MigrationState.DUAL_WRITE) {
                reconcileFromLegacy(logicalKey)?.let { return@synchronized it.value }
            }
            when (val result = blobs.read(logicalKey)) {
                is SecureBlobStore.ReadResult.Value -> result.bytes.toString(Charsets.UTF_8)
                SecureBlobStore.ReadResult.Deleted -> null
                SecureBlobStore.ReadResult.Missing -> legacyValue(logicalKey)
            }
        }

    override fun putString(
        logicalKey: String,
        value: String?,
    ) = synchronized(OPERATION_LOCK) {
        if (logicalKey == authKey(DATABASE_PASSWORD) && value == null) {
            throw SecureBlobException()
        }
        // Index first so a process death between the blob write and index
        // update cannot leave an authenticated value that scoped deletion
        // will never discover.
        recordKey(logicalKey)
        // During the rollback window, legacy storage is the durable source
        // of truth. Commit it first; reads reconcile a failed second write.
        dualWrite(logicalKey, value)
        if (value == null) {
            blobs.delete(logicalKey)
        } else {
            blobs.write(logicalKey, value.toByteArray(Charsets.UTF_8))
        }
    }

    override fun deletePrefix(prefix: String) {
        synchronized(OPERATION_LOCK) {
            statePrefs.getStringSet(KEY_INDEX, emptySet()).orEmpty().filter { it.startsWith(prefix) }.forEach { key ->
                dualWrite(key, null)
                blobs.delete(key)
            }
        }
    }

    fun legacyAuthPreferences(): SharedPreferences? = legacy.auth()

    private fun migrate() {
        synchronized(OPERATION_LOCK) {
            val auth = legacy.auth()
            val cookies = legacy.cookies()
            if (auth == null && cookies == null) {
                setState(MigrationState.VERIFIED)
                return
            }
            if (state() == MigrationState.DUAL_WRITE) return
            if (state() == MigrationState.VERIFIED) {
                setState(MigrationState.DUAL_WRITE)
                return
            }
            val entries =
                buildList {
                    auth?.all?.forEach { (key, value) ->
                        encodeLegacyValue(authKey(key), value)?.let { encoded ->
                            add(authKey(key) to encoded)
                        }
                    }
                    cookies?.all?.forEach { (key, value) ->
                        encodeLegacyValue(cookieKey(key), value)?.let { encoded ->
                            add(cookieKey(key) to encoded)
                        }
                    }
                }
            setState(MigrationState.COPYING)
            entries.forEachIndexed { index, (key, value) ->
                recordKey(key)
                when (blobs.read(key)) {
                    SecureBlobStore.ReadResult.Missing -> {
                        blobs.write(key, value.toByteArray(Charsets.UTF_8))
                    }
                    SecureBlobStore.ReadResult.Deleted -> Unit
                    is SecureBlobStore.ReadResult.Value -> Unit
                }
                migrationObserver(index + 1)
            }
            entries.forEach { (key, value) ->
                val copied = blobs.read(key) as? SecureBlobStore.ReadResult.Value ?: throw SecureBlobException()
                if (!copied.bytes.contentEquals(value.toByteArray(Charsets.UTF_8))) throw SecureBlobException()
            }
            setState(MigrationState.VERIFIED)
            setState(MigrationState.DUAL_WRITE)
        }
    }

    private fun legacyValue(logicalKey: String): String? {
        if (state() == MigrationState.VERIFIED) return null
        val (prefs, key) = legacyTarget(logicalKey) ?: return null
        return encodeLegacyValue(logicalKey, prefs.all[key])
    }

    /**
     * While rollback remains supported, repair the new blob from the legacy
     * value before returning it. This closes an interrupted legacy-first write.
     */
    private fun reconcileFromLegacy(logicalKey: String): LegacyRead? {
        val (prefs, key) = legacyTarget(logicalKey) ?: return null
        val present = prefs.contains(key)
        val value = encodeLegacyValue(logicalKey, prefs.all[key])
        val current = blobs.read(logicalKey)
        if (present && value == null) {
            return recoverFromMalformedLegacy(logicalKey, current)
        }
        if (!present) {
            if (current is SecureBlobStore.ReadResult.Value) blobs.delete(logicalKey)
            return LegacyRead(value = null)
        }
        val bytes = value!!.toByteArray(Charsets.UTF_8)
        if (current !is SecureBlobStore.ReadResult.Value || !current.bytes.contentEquals(bytes)) {
            recordKey(logicalKey)
            blobs.write(logicalKey, bytes)
        }
        return LegacyRead(value)
    }

    /**
     * A malformed rollback value is not authenticated legacy plaintext. Keep a
     * valid new blob authoritative; otherwise tombstone ordinary credentials
     * so callers fail closed without entering a startup crash loop. Never mint
     * a replacement SQLCipher password over malformed legacy state.
     */
    private fun recoverFromMalformedLegacy(
        logicalKey: String,
        current: SecureBlobStore.ReadResult,
    ): LegacyRead =
        when (current) {
            is SecureBlobStore.ReadResult.Value -> {
                val value = current.bytes.toString(Charsets.UTF_8)
                dualWrite(logicalKey, value)
                LegacyRead(value)
            }
            SecureBlobStore.ReadResult.Deleted -> {
                dualWrite(logicalKey, null)
                LegacyRead(value = null)
            }
            SecureBlobStore.ReadResult.Missing -> {
                if (logicalKey == authKey(DATABASE_PASSWORD)) throw SecureBlobException()
                dualWrite(logicalKey, null)
                recordKey(logicalKey)
                blobs.delete(logicalKey)
                LegacyRead(value = null)
            }
        }

    private fun encodeLegacyValue(
        logicalKey: String,
        value: Any?,
    ): String? =
        when (value) {
            null -> null
            is String -> value
            is Boolean ->
                if (logicalKey == authKey(LEGACY_DEFAULT_MIGRATED)) {
                    value.toString()
                } else {
                    null
                }
            else -> null
        }

    private fun dualWrite(
        logicalKey: String,
        value: String?,
    ) {
        if (state() != MigrationState.DUAL_WRITE) return
        val (prefs, key) = legacyTarget(logicalKey) ?: return
        val committed =
            prefs.edit().apply {
                when {
                    value == null -> remove(key)
                    key == LEGACY_DEFAULT_MIGRATED -> putBoolean(key, value == "true")
                    else -> putString(key, value)
                }
            }.commit()
        if (!committed) throw SecureBlobException()
    }

    private fun recordKey(logicalKey: String) =
        synchronized(INDEX_LOCK) {
            val keys = statePrefs.getStringSet(KEY_INDEX, emptySet()).orEmpty() + logicalKey
            if (!statePrefs.edit().putStringSet(KEY_INDEX, keys).commit()) throw SecureBlobException()
        }

    private fun legacyTarget(logicalKey: String): Pair<SharedPreferences, String>? =
        when {
            logicalKey.startsWith(AUTH_PREFIX) -> legacy.auth()?.let { it to logicalKey.removePrefix(AUTH_PREFIX) }
            logicalKey.startsWith(
                COOKIE_PREFIX,
            ) -> legacy.cookies()?.let { it to logicalKey.removePrefix(COOKIE_PREFIX) }
            else -> null
        }

    private fun state(): MigrationState {
        val encoded = statePrefs.getString(STATE_KEY, null) ?: return MigrationState.NOT_STARTED
        return try {
            MigrationState.valueOf(encoded)
        } catch (error: IllegalArgumentException) {
            throw SecureBlobException(error)
        }
    }

    private fun setState(state: MigrationState) {
        if (!statePrefs.edit().putString(STATE_KEY, state.name).commit()) throw SecureBlobException()
    }

    companion object {
        private const val STATE_FILE = "secure_storage_migration"
        private const val STATE_KEY = "state"
        private const val KEY_INDEX = "logical_keys"
        private const val AUTH_PREFIX = "auth/"
        private const val COOKIE_PREFIX = "cookies/"
        private const val LEGACY_DEFAULT_MIGRATED = "legacy_default_migrated"
        private const val DATABASE_PASSWORD = "db_password"
        private val OPERATION_LOCK = Any()
        private val INDEX_LOCK = Any()

        fun authKey(key: String) = AUTH_PREFIX + key

        fun cookieKey(key: String) = COOKIE_PREFIX + key

        fun cookiePrefix() = COOKIE_PREFIX
    }

    private data class LegacyRead(val value: String?)
}
