package com.m57.hermescontrol.data.remote

import android.content.Context
import com.m57.hermescontrol.data.security.SecretStore
import com.m57.hermescontrol.data.security.SecureBlobException
import com.m57.hermescontrol.data.security.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie

/**
 * Persistence contract for server-scoped cookies.
 *
 * Implementations store cookies per logical server id (not the raw host) so
 * that switching [PersistentCookieJar] scopes never lets cookies from one
 * gateway leak into another (issue #470 — multi-server isolation).
 *
 * Splitting this into an interface lets unit tests inject an in-memory fake
 * ([com.m57.hermescontrol.data.remote.FakeEncryptedCookieStore]) instead of
 * spinning up Android Keystore-backed storage.
 */
interface CookieStore {
    /** Persist [cookies] for [serverId]. Replaces the previous set atomically. */
    suspend fun save(
        serverId: String,
        cookies: List<Cookie>,
    )

    /** Load the persisted cookie set for [serverId] (may be empty). */
    suspend fun load(serverId: String): List<Cookie>

    /** Drop all persisted cookies for [serverId] (e.g. on logout). */
    suspend fun clear(serverId: String)

    /** Drop everything across all servers. */
    suspend fun clearAll()
}

/**
 * Encrypted, per-server cookie persistence backed by independent atomic
 * Android Keystore AES-GCM blobs. Cookies are serialized with kotlinx.serialization
 * and written on [Dispatchers.IO].
 *
 * Legacy migration: the pre-#470 code stored a single raw
 * `hermes_session_at` value in `AuthManager`'s prefs under
 * [LEGACY_SESSION_COOKIE_KEY]. [load] transparently folds any such legacy
 * value into the requested [serverId] scope on first read so existing gated
 * sessions keep working without a re-login.
 */
class EncryptedCookieStore internal constructor(
    private val context: Context,
    private val storageFactory: (Context) -> SecretStore = ::SecureStorage,
) : CookieStore {
    constructor(context: Context) : this(context, ::SecureStorage)

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val storage by lazy { storageFactory(context) }

    override suspend fun save(
        serverId: String,
        cookies: List<Cookie>,
    ) = withContext(Dispatchers.IO) {
        val serialized = cookies.map { it.serialize() }
        storage.putString(SecureStorage.cookieKey(keyFor(serverId)), json.encodeToString(serialized))
    }

    override suspend fun load(serverId: String): List<Cookie> =
        withContext(Dispatchers.IO) {
            val scopedKey = SecureStorage.cookieKey(keyFor(serverId))
            var raw = storage.getString(scopedKey)
            val foldMarker = SecureStorage.authKey("${LEGACY_SESSION_COOKIE_KEY}_folded")
            if (serverId == PersistentCookieJar.DEFAULT_SERVER_ID &&
                storage.getString(foldMarker) != "true"
            ) {
                if (raw == null) {
                    val legacyKey = SecureStorage.authKey(LEGACY_SESSION_COOKIE_KEY)
                    val migrated =
                        storage.getString(legacyKey)
                            ?.takeIf { it.isNotBlank() }
                            ?.let(::wrapSessionCookie)
                    if (migrated != null) {
                        raw = json.encodeToString(listOf(migrated.serialize()))
                        storage.putString(scopedKey, raw)
                    }
                }
                storage.putString(foldMarker, "true")
            }
            raw ?: return@withContext emptyList()
            try {
                json
                    .decodeFromString<List<CookieHolder>>(raw)
                    .mapNotNull { it.toCookie() }
            } catch (error: Exception) {
                throw SecureBlobException(error)
            }
        }

    override suspend fun clear(serverId: String) =
        withContext(Dispatchers.IO) {
            storage.putString(SecureStorage.cookieKey(keyFor(serverId)), null)
            suppressLegacyCookieFallback()
        }

    override suspend fun clearAll() =
        withContext(Dispatchers.IO) {
            storage.deletePrefix(SecureStorage.cookiePrefix())
            suppressLegacyCookieFallback()
        }

    /** A logout or explicit clear must never resurrect the pre-scope cookie. */
    private fun suppressLegacyCookieFallback() {
        storage.putString(SecureStorage.authKey(LEGACY_SESSION_COOKIE_KEY), null)
        storage.putString(SecureStorage.authKey("${LEGACY_SESSION_COOKIE_KEY}_folded"), "true")
    }

    private fun keyFor(serverId: String) = "cookies::$serverId"

    companion object {
        const val PREFS_FILE = "hermes_secure_cookies"
        const val LEGACY_SESSION_COOKIE_KEY = "session_cookie"
    }
}
