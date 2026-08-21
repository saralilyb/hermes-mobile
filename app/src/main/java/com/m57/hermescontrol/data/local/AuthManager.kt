// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.data.local

import android.content.Context
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.config.ServerStore
import com.m57.hermescontrol.data.config.ServerStoreMigration
import com.m57.hermescontrol.data.config.ServerStoreSerializer
import com.m57.hermescontrol.data.config.ServerUrlMigration
import com.m57.hermescontrol.data.config.resolvedBaseUrl
import com.m57.hermescontrol.data.config.resolvedHost
import com.m57.hermescontrol.data.config.resolvedPort
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.security.SecretStore
import com.m57.hermescontrol.data.security.SecureStorage
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Singleton that manages encrypted storage of the Hermes dashboard token
 * and connection settings.
 *
 * Must call [init] with a Context before any other method.
 */
object AuthManager {
    const val DEFAULT_PROFILE_ID = "default"
    const val DEFAULT_PROFILE_NAME = "Default"
    private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
    private const val KEY_SESSION_COOKIE = "session_cookie"
    private const val KEY_LEGACY_TOKEN = "auth_token"
    private const val KEY_LEGACY_DEFAULT_MIGRATED = "legacy_default_migrated"
    private const val DATABASE_PASSWORD_BYTES = 32
    private val databasePasswordLock = Any()

    @Volatile
    private var secureStorage: SecretStore? = null

    @Volatile
    internal var secureStorageFactory: (Context) -> SecretStore = ::SecureStorage

    @Volatile
    private var _serverStore: ServerStore? = null

    @Volatile
    private var appScope: CoroutineScope? = null

    val serverStore: ServerStore
        get() =
            _serverStore ?: throw IllegalStateException(
                "AuthManager not initialized. Call init(context) first.",
            )

    private val _themePreferenceFlow = MutableStateFlow<ThemePreference>(ThemePreference.SYSTEM)
    val themePreferenceFlow: StateFlow<ThemePreference> = _themePreferenceFlow.asStateFlow()

    private val _useDynamicColorsFlow = MutableStateFlow<Boolean>(true)
    val useDynamicColorsFlow: StateFlow<Boolean> = _useDynamicColorsFlow.asStateFlow()

    private val _themePresetFlow = MutableStateFlow<ThemePreset>(ThemePreset.DEFAULT)
    val themePresetFlow: StateFlow<ThemePreset> = _themePresetFlow.asStateFlow()

    private val _tokenFlow = MutableStateFlow<String?>(null)
    val tokenFlow: StateFlow<String?> = _tokenFlow.asStateFlow()

    private val _selectedProfileIdFlow = MutableStateFlow(DEFAULT_PROFILE_ID)
    val selectedProfileIdFlow: StateFlow<String> = _selectedProfileIdFlow.asStateFlow()

    /** Initialise Keystore-backed secure storage and app settings. */
    fun init(context: Context) {
        if (_serverStore != null) return
        synchronized(this) {
            if (_serverStore != null) return

            val dataStore =
                androidx.datastore.core.DataStoreFactory.create(
                    serializer = ServerStoreSerializer,
                    migrations =
                        listOf(
                            ServerStoreMigration(context),
                            ServerUrlMigration(),
                        ),
                ) {
                    context.filesDir.resolve("server_store.json")
                }

            val scope = CoroutineScope(Dispatchers.IO)
            appScope = scope
            val store = ServerStore(dataStore, scope)
            _serverStore = store

            val storage = secureStorageFactory(context)
            secureStorage = storage
            migrateLegacyDefaultIfNeeded(storage)
            _tokenFlow.value = getTokenInternal(storage)

            // Initialize the encrypted cookie store. Its initial scope loads on IO.
            val initialProfileId =
                store.getLatestState().selectedProfileId?.takeIf { it.isNotBlank() } ?: DEFAULT_PROFILE_ID
            _selectedProfileIdFlow.value = initialProfileId
            CookieManager.initialize(context, initialServerId = initialProfileId)

            scope.launch {
                store.stateFlow.collect { state ->
                    synchronized(AuthManager) {
                        val latestProfileId = store.getLatestState().selectedProfileId
                        _selectedProfileIdFlow.value = normalizedProfileId(latestProfileId)
                        syncCookieStoreForProfile(latestProfileId)
                    }
                    _themePreferenceFlow.value = state.themePreference
                    _useDynamicColorsFlow.value = state.useDynamicColors
                    _themePresetFlow.value = state.themePreset
                }
            }
        }
    }

    /** Retrieves initialized secure storage or fails closed. */
    private fun requireSecureStorage(): SecretStore =
        secureStorage ?: throw IllegalStateException("AuthManager not initialized. Call init(context) first.")

    fun setWsAuthParam(param: String) {
        synchronized(this) {
            val selectedId = getSelectedProfileId()
            serverStore.update { state ->
                state.copy(
                    wsAuthParam = param,
                    connectionProfiles =
                        state.connectionProfiles.map { profile ->
                            if (profile.id == selectedId) {
                                profile.copy(wsAuthParam = param)
                            } else {
                                profile
                            }
                        },
                )
            }
        }
    }

    /**
     * WebSocket query parameter for the selected profile.
     *
     * New profiles persist this mode directly. Legacy profiles fall back to
     * the former global mode until they authenticate again.
     */
    fun getWsAuthParam(): String {
        val state = serverStore.getLatestState()
        val selectedId = state.selectedProfileId?.takeIf { it.isNotBlank() }
        val profileMode =
            state.connectionProfiles
                .firstOrNull { it.id == selectedId }
                ?.wsAuthParam
        return profileMode?.takeIf { it.isNotBlank() }
            ?: state.wsAuthParam.takeIf { it.isNotBlank() }
            ?: "token"
    }

    /** True when the selected profile uses dashboard cookie authentication. */
    fun isGatedMode(): Boolean = getWsAuthParam() == "ticket"

    // ── Session Cookie (for gated/dashboard REST API) ────────────────────

    /**
     * In gated mode (basic auth), the dashboard authenticates REST API
     * requests via a dashboard session cookie, not via
     * `Authorization: Bearer`. The cookie is now owned by the shared
     * [CookieManager]/[PersistentCookieJar] (issue #470) which attaches it
     * automatically on every REST call, follows redirects, and persists it
     * encrypted. This accessor is a thin read-through to that store.
     */
    fun getSessionCookie(): String? = CookieManager.getSessionCookie()

    fun setSessionCookie(cookie: String?) {
        CookieManager.setSessionCookie(cookie, endpoint())
    }

    /**
     * Evict expired (non-session) cookies for the active server scope to
     * bound cookie growth (issue #470 step 7).
     */
    fun pruneServerCache() {
        CookieManager.pruneServerCache()
    }

    // ── Database Master Password ─────────────────────────────────────────

    fun getDatabasePassword(): ByteArray =
        synchronized(databasePasswordLock) {
            val storage = requireSecureStorage()
            var dbPasswordBase64 = storage.getString(SecureStorage.authKey("db_password"))
            if (dbPasswordBase64 == null) {
                val random = java.security.SecureRandom()
                val newPassword = ByteArray(32)
                random.nextBytes(newPassword)
                dbPasswordBase64 = android.util.Base64.encodeToString(newPassword, android.util.Base64.NO_WRAP)
                storage.putString(SecureStorage.authKey("db_password"), dbPasswordBase64)
            }
            val decoded =
                try {
                    android.util.Base64.decode(dbPasswordBase64, android.util.Base64.NO_WRAP)
                } catch (error: IllegalArgumentException) {
                    throw com.m57.hermescontrol.data.security.SecureBlobException(error)
                }
            if (decoded.size != DATABASE_PASSWORD_BYTES) {
                throw com.m57.hermescontrol.data.security.SecureBlobException()
            }
            decoded
        }

    // ── Connection Profiles ──────────────────────────────────────────────

    fun getConnectionProfiles(): List<ConnectionProfile> = serverStore.getLatestState().connectionProfiles

    fun saveConnectionProfiles(profiles: List<ConnectionProfile>) {
        synchronized(this) { serverStore.update { it.copy(connectionProfiles = profiles) } }
    }

    fun saveConnectionProfilesAndToken(
        profiles: List<ConnectionProfile>,
        profileId: String,
        token: String?,
    ) {
        var publish = false
        synchronized(this) {
            serverStore.update { it.copy(connectionProfiles = profiles) }
            requireSecureStorage().putString(SecureStorage.authKey("token_$profileId"), token)
            if (getSelectedProfileId() == profileId) {
                cachedToken = token
                tokenInitialized = true
                publish = true
            }
        }
        if (publish) _tokenFlow.value = token
    }

    fun saveConnectionProfilesAndSelect(
        profiles: List<ConnectionProfile>,
        profileId: String,
        token: String?,
    ) {
        synchronized(this) {
            serverStore.update { it.copy(connectionProfiles = profiles, selectedProfileId = profileId) }
            requireSecureStorage().putString(SecureStorage.authKey("token_$profileId"), token)
            cachedToken = token
            tokenInitialized = true
            syncCookieStoreForProfile(profileId)
        }
        _tokenFlow.value = token
    }

    /**
     * Guarantees the selected profile is never null (issue #478).
     *
     * - If a profile is already selected (default or otherwise), nothing is changed.
     * - If nothing is selected but other profiles exist, the first one is selected.
     * - If there are no profiles at all (fresh install / legacy standalone), a [DEFAULT_PROFILE_ID]
     *   profile is created from the current top-level host/port and selected.
     *
     * This never injects a Default profile into an existing user's profile list, and never
     * clobbers a user's explicit selection.
     */
    fun ensureDefaultProfile() {
        val state = serverStore.getLatestState()
        val hasDefault = state.connectionProfiles.any { it.id == DEFAULT_PROFILE_ID }
        val needsSelection = state.selectedProfileId.isNullOrBlank()

        if (!needsSelection && (hasDefault || state.connectionProfiles.isNotEmpty())) return

        if (state.connectionProfiles.isEmpty()) {
            // Fresh install / legacy standalone: create the Default profile and select it.
            serverStore.update { s ->
                s.copy(
                    connectionProfiles =
                        listOf(
                            ConnectionProfile(
                                id = DEFAULT_PROFILE_ID,
                                name = DEFAULT_PROFILE_NAME,
                                baseUrl = s.resolvedBaseUrl,
                            ),
                        ),
                    selectedProfileId = DEFAULT_PROFILE_ID,
                )
            }
            return
        }

        // Profiles exist but nothing is selected: pick the first one so selection is non-null.
        if (needsSelection) {
            serverStore.update { s -> s.copy(selectedProfileId = s.connectionProfiles.first().id) }
        }
    }

    /** Ensure a profile is selected (the default one if nothing else), so callers never see null. */
    fun ensureDefaultSelected() {
        ensureDefaultProfile()
        if (getSelectedProfileId().isNullOrBlank()) {
            setSelectedProfileId(DEFAULT_PROFILE_ID)
        }
    }

    /**
     * One-time migration: fold the legacy standalone ([KEY_LEGACY_TOKEN]) credentials into the
     * new default [ConnectionProfile]. Runs once per install, guarded by
     * [KEY_LEGACY_DEFAULT_MIGRATED].
     */
    private fun migrateLegacyDefaultIfNeeded(storage: SecretStore) {
        if (storage.getString(SecureStorage.authKey(KEY_LEGACY_DEFAULT_MIGRATED)) == "true") return
        val legacyToken = storage.getString(SecureStorage.authKey(KEY_LEGACY_TOKEN))
        ensureDefaultProfile()
        if (!legacyToken.isNullOrBlank()) {
            storage.putString(
                SecureStorage.authKey("token_$DEFAULT_PROFILE_ID"),
                legacyToken,
            )
        }
        storage.putString(SecureStorage.authKey(KEY_LEGACY_DEFAULT_MIGRATED), "true")
    }

    // ── Pinned Models ────────────────────────────────────────────────────

    fun getPinnedModels(): List<PinnedModel> = serverStore.getLatestState().pinnedModels

    fun savePinnedModels(pinned: List<PinnedModel>) {
        serverStore.update { it.copy(pinnedModels = pinned) }
    }

    // ── Pinned Sessions ──────────────────────────────────────────────────

    fun getPinnedSessionIds(profileId: String? = getSelectedProfileId()): List<String> {
        val resolvedProfileId = profileId ?: DEFAULT_PROFILE_ID
        return serverStore.getLatestState().pinnedSessionIdsByProfile[resolvedProfileId].orEmpty()
    }

    fun savePinnedSessionIds(
        pinnedSessionIds: List<String>,
        profileId: String? = getSelectedProfileId(),
    ) {
        val resolvedProfileId = profileId ?: DEFAULT_PROFILE_ID
        serverStore.update { state ->
            state.copy(
                pinnedSessionIdsByProfile =
                    state.pinnedSessionIdsByProfile +
                        (resolvedProfileId to pinnedSessionIds.distinct()),
            )
        }
    }

    fun clearPinnedSessionIds(profileId: String) {
        serverStore.update { state ->
            state.copy(
                pinnedSessionIdsByProfile =
                    state.pinnedSessionIdsByProfile - profileId,
            )
        }
    }

    fun getProfileToken(profileId: String): String? =
        requireSecureStorage().getString(SecureStorage.authKey("token_$profileId"))

    fun setProfileToken(
        profileId: String,
        token: String?,
    ) {
        var publish = false
        synchronized(this) {
            requireSecureStorage().putString(SecureStorage.authKey("token_$profileId"), token)
            if (getSelectedProfileId() == profileId) {
                cachedToken = token
                tokenInitialized = true
                publish = true
            }
        }
        if (publish) _tokenFlow.value = token
    }

    fun getSelectedProfileId(): String? {
        val id = serverStore.getLatestState().selectedProfileId
        return if (id.isNullOrBlank()) null else id
    }

    fun setSelectedProfileId(id: String?) {
        val token =
            synchronized(this) {
                serverStore.update { it.copy(selectedProfileId = id) }
                cachedToken = null
                tokenInitialized = false
                syncCookieStoreForProfile(id)
                id?.takeIf(String::isNotBlank)?.let(::getProfileToken)
            }
        _tokenFlow.value = token
    }

    internal data class CredentialBoundary(
        val profileId: String,
        val endpoint: ServerEndpoint,
        val gated: Boolean,
        val token: String?,
    )

    internal fun credentialBoundary(): CredentialBoundary =
        synchronized(this) {
            val state = serverStore.getLatestState()
            val selectedId = state.selectedProfileId?.takeIf(String::isNotBlank)
            val profile = state.connectionProfiles.firstOrNull { it.id == selectedId }
            val mode =
                profile?.wsAuthParam?.takeIf(String::isNotBlank)
                    ?: state.wsAuthParam.takeIf(String::isNotBlank)
                    ?: "token"
            CredentialBoundary(
                profileId = selectedId ?: DEFAULT_PROFILE_ID,
                endpoint = ServerEndpoint.parseForBuild(state.resolvedBaseUrl),
                gated = mode == "ticket",
                token = selectedId?.let(::getProfileToken),
            )
        }

    /** Store a refresh result for its captured profile without redirecting it after a switch. */
    internal fun commitRefreshedToken(
        boundary: CredentialBoundary,
        token: String?,
    ): Boolean {
        var publish = false
        val stillActive =
            synchronized(this) {
                requireSecureStorage().putString(SecureStorage.authKey("token_${boundary.profileId}"), token)
                val active =
                    credentialBoundary().let {
                        it.profileId == boundary.profileId &&
                            it.endpoint == boundary.endpoint &&
                            it.gated == boundary.gated
                    }
                if (active) {
                    cachedToken = token
                    tokenInitialized = true
                    publish = true
                }
                active
            }
        if (publish) _tokenFlow.value = token
        return stillActive
    }

    private fun normalizedProfileId(profileId: String?): String =
        profileId?.takeIf { it.isNotBlank() } ?: DEFAULT_PROFILE_ID

    private fun syncCookieStoreForProfile(profileId: String?) {
        val normalizedId = normalizedProfileId(profileId)
        val currentId = CookieManager.currentServerOrNull() ?: return
        if (currentId != normalizedId) {
            CookieManager.useStore(normalizedId)
        }
    }

    // ── Token ────────────────────────────────────────────────────────────

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenInitialized: Boolean = false

    // For testing purposes
    fun resetTokenCacheForTest() {
        synchronized(this) {
            cachedToken = null
            tokenInitialized = false
        }
    }

    // For testing purposes
    fun resetAuthStateForTest() {
        synchronized(this) {
            cachedToken = null
            tokenInitialized = false
            _serverStore = null
            secureStorage = null
            appScope?.let {
                try {
                    it.cancel()
                } catch (_: Exception) {
                }
            }
            appScope = null
        }
    }

    fun getToken(): String? {
        if (tokenInitialized) return cachedToken
        synchronized(this) {
            if (tokenInitialized) return cachedToken
            val selectedId = getSelectedProfileId()
            val token = if (selectedId != null) getProfileToken(selectedId) else null
            cachedToken = token
            tokenInitialized = true
            return token
        }
    }

    private fun getTokenInternal(storage: SecretStore): String? {
        val selectedId = serverStore.getLatestState().selectedProfileId?.takeIf { it.isNotBlank() }
        return if (selectedId != null) {
            storage.getString(SecureStorage.authKey("token_$selectedId"))
        } else {
            null
        }
    }

    fun setToken(token: String?) {
        synchronized(this) {
            val selectedId =
                getSelectedProfileId() ?: run {
                    ensureDefaultSelected()
                    DEFAULT_PROFILE_ID
                }
            requireSecureStorage().putString(SecureStorage.authKey("token_$selectedId"), token)
            cachedToken = token
            tokenInitialized = true
        }
        _tokenFlow.value = token
    }

    // ── Server endpoint ──────────────────────────────────────────────────

    fun getBaseUrl(): String = serverStore.getLatestState().resolvedBaseUrl

    fun endpoint(): ServerEndpoint =
        ServerEndpoint.parse(
            getBaseUrl(),
            CleartextPolicy.ALLOW_WITH_WARNING,
        )

    fun endpointForBuild(): ServerEndpoint = ServerEndpoint.parseForBuild(getBaseUrl())

    fun setBaseUrl(baseUrl: String) {
        val normalized =
            ServerEndpoint.parse(
                baseUrl,
                CleartextPolicy.ALLOW_WITH_WARNING,
            ).baseUrl.toString()
        synchronized(this) {
            val selectedId =
                getSelectedProfileId() ?: run {
                    ensureDefaultSelected()
                    DEFAULT_PROFILE_ID
                }
            serverStore.update { state ->
                val profiles =
                    state.connectionProfiles.map { profile ->
                        if (profile.id == selectedId) {
                            profile.copy(
                                host = "",
                                port = 0,
                                baseUrl = normalized,
                            )
                        } else {
                            profile
                        }
                    }
                state.copy(
                    baseUrl = normalized,
                    connectionProfiles = profiles,
                )
            }
        }
    }

    /** Compatibility accessors for legacy pairing payloads. */
    fun getHost(): String = serverStore.getLatestState().resolvedHost

    fun getPort(): Int = serverStore.getLatestState().resolvedPort

    fun setHost(host: String) {
        val endpoint = endpoint()
        val normalizedHost = host.trim().removePrefix("[").removeSuffix("]")
        setBaseUrl(
            endpoint.baseUrl.newBuilder().host(normalizedHost).build().toString(),
        )
    }

    fun setPort(port: Int) {
        require(port in 1..65535) { "Port is out of range" }
        val endpoint = endpoint()
        setBaseUrl(endpoint.baseUrl.newBuilder().port(port).build().toString())
    }

    // ── Auto-reconnect ───────────────────────────────────────────────────

    fun isAutoReconnect(): Boolean = serverStore.getLatestState().autoReconnect

    fun setAutoReconnect(enabled: Boolean) {
        serverStore.update { it.copy(autoReconnect = enabled) }
    }

    // ── Theme preference ──────────────────────────────────────────────────

    fun getThemePreference(): ThemePreference = serverStore.getLatestState().themePreference

    fun setThemePreference(theme: ThemePreference) {
        serverStore.update { it.copy(themePreference = theme) }
    }

    fun isUseDynamicColors(): Boolean = serverStore.getLatestState().useDynamicColors

    fun setUseDynamicColors(value: Boolean) {
        serverStore.update { it.copy(useDynamicColors = value) }
    }

    fun getThemePreset(): ThemePreset = serverStore.getLatestState().themePreset

    fun setThemePreset(preset: ThemePreset) {
        serverStore.update { it.copy(themePreset = preset) }
    }

    /** Canonical build-allowed Retrofit base URL. */
    fun baseUrl(): String = endpointForBuild().baseUrl.toString()

    /** Canonical WebSocket URL with an encoded token or short-lived ticket. */
    fun wsUrl(): String {
        return wsUrlWithCredential(getToken().orEmpty(), getWsAuthParam())
    }

    /** Build a WebSocket URL with a handshake-local credential. */
    fun wsUrlWithCredential(
        credential: String,
        authParameter: String = "ticket",
    ): String {
        return endpointForBuild().webSocketUrl(
            authParameter = authParameter,
            credential = credential,
        )
    }

    // ── Typing Effect ───────────────────────────────────────────────────

    fun isTypingEffectEnabled(): Boolean = serverStore.getLatestState().typingEffectEnabled

    fun setTypingEffectEnabled(enabled: Boolean) {
        serverStore.update { it.copy(typingEffectEnabled = enabled) }
    }

    // ── App display language ───────────────────────────────────────────
    // "system" follows the device locale; any other value is a BCP-47 code.

    fun getAppLanguage(): String = serverStore.getLatestState().appLanguage

    fun setAppLanguage(code: String) {
        serverStore.update { it.copy(appLanguage = code) }
    }

    fun getTypingEffectDelayMs(): Int = serverStore.getLatestState().typingEffectDelayMs

    fun setTypingEffectDelayMs(delayMs: Int) {
        serverStore.update { it.copy(typingEffectDelayMs = delayMs) }
    }
}
