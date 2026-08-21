// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.data.local

import android.content.Context
import android.content.SharedPreferences
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.GatewayFileClient
import com.m57.hermescontrol.data.security.SecretStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TestContext(
    private val tempDir: java.io.File,
    baseContext: Context,
) : android.content.ContextWrapper(baseContext) {
    override fun getFilesDir(): java.io.File = tempDir

    override fun getApplicationContext(): Context = this
}

class AuthManagerTest {
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var mockContext: Context
    private lateinit var testContext: Context
    private lateinit var testSecretStore: SecretStore

    @Before
    fun setUp() {
        mockPrefs = mockk(relaxed = true)
        mockEditor = mockk(relaxed = true)
        mockContext = mockk(relaxed = true)

        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.putInt(any(), any()) } returns mockEditor
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor
        every { mockPrefs.getBoolean("migrated_to_datastore", any()) } returns true

        // Mock Log to prevent "Method d in android.util.Log not mocked"
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.isLoggable(any(), any()) } returns false
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.encodeToString(any(), android.util.Base64.NO_WRAP) } answers {
            java.util.Base64.getEncoder().withoutPadding().encodeToString(firstArg())
        }
        every { android.util.Base64.decode(any<String>(), android.util.Base64.NO_WRAP) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }

        // Mock filesDir to point to temporary directory for DataStore
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        testContext = TestContext(tempDir, mockContext)

        val tempFile = java.io.File(tempDir, "server_store.json")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        AuthManager.resetAuthStateForTest()
        testSecretStore =
            object : SecretStore {
                override fun getString(logicalKey: String): String? =
                    if (logicalKey.startsWith("auth/")) {
                        mockPrefs.getString(logicalKey.removePrefix("auth/"), null)
                    } else {
                        null
                    }

                override fun putString(
                    logicalKey: String,
                    value: String?,
                ) {
                    if (!logicalKey.startsWith("auth/")) return
                    val key = logicalKey.removePrefix("auth/")
                    mockPrefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
                }

                override fun deletePrefix(prefix: String) = Unit
            }
        AuthManager.secureStorageFactory = { testSecretStore }
        CookieManager.resetForTest()
        CookieManager.secureStorageFactory = { testSecretStore }

        // Initialise AuthManager
        AuthManager.init(testContext)

        // Wait for ServerStore initialization to complete
        AuthManager.serverStore.getLatestState()
    }

    @After
    fun tearDown() {
        CookieManager.resetForTest()
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val tempFile = java.io.File(tempDir, "server_store.json")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        unmockkAll()
    }

    @Test
    fun testGetAndSetToken() {
        AuthManager.ensureDefaultProfile()
        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
        AuthManager.setToken("dummy_token_123")
        assertEquals("dummy_token_123", AuthManager.getToken())
        verify { mockEditor.putString("token_${AuthManager.DEFAULT_PROFILE_ID}", "dummy_token_123") }
        verify { mockEditor.apply() }
    }

    @Test
    fun testGetAndSetToken_null() {
        AuthManager.ensureDefaultProfile()
        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
        AuthManager.setToken(null)
        assertNull(AuthManager.getToken())
        verify { mockEditor.remove("token_${AuthManager.DEFAULT_PROFILE_ID}") }
        verify { mockEditor.apply() }
    }

    @Test
    fun databasePasswordIsStableUnderConcurrentCreation() {
        val values = java.util.concurrent.ConcurrentHashMap<String, String?>()
        AuthManager.resetAuthStateForTest()
        AuthManager.secureStorageFactory = {
            object : SecretStore {
                override fun getString(logicalKey: String): String? = values[logicalKey]

                override fun putString(
                    logicalKey: String,
                    value: String?,
                ) {
                    if (value == null) values.remove(logicalKey) else values[logicalKey] = value
                }

                override fun deletePrefix(prefix: String) {
                    values.keys.removeIf { it.startsWith(prefix) }
                }
            }
        }
        CookieManager.resetForTest()
        CookieManager.secureStorageFactory = AuthManager.secureStorageFactory
        AuthManager.init(testContext)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)

        val passwords =
            (0 until 32).map { executor.submit<ByteArray> { AuthManager.getDatabasePassword() } }
                .map { it.get() }
        executor.shutdown()

        assertTrue(passwords.all { it.size == 32 && it.contentEquals(passwords.first()) })
    }

    @Test(expected = com.m57.hermescontrol.data.security.SecureBlobException::class)
    fun databasePasswordRejectsMalformedBase64() {
        every { mockPrefs.getString("db_password", null) } returns "%%%"

        AuthManager.getDatabasePassword()
    }

    @Test(expected = com.m57.hermescontrol.data.security.SecureBlobException::class)
    fun databasePasswordRejectsWrongDecodedLength() {
        every { mockPrefs.getString("db_password", null) } returns
            java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(16))

        AuthManager.getDatabasePassword()
    }

    @Test
    fun testGatedMode_isScopedToSelectedProfile() {
        val direct =
            ConnectionProfile(
                id = "direct",
                name = "Direct",
                host = "direct.example.com",
                wsAuthParam = "token",
            )
        val gated =
            ConnectionProfile(
                id = "gated",
                name = "Gated",
                host = "gated.example.com",
                wsAuthParam = "ticket",
            )
        AuthManager.saveConnectionProfiles(listOf(direct, gated))

        AuthManager.setSelectedProfileId("direct")
        assertEquals(false, AuthManager.isGatedMode())

        AuthManager.setSelectedProfileId("gated")
        assertEquals(true, AuthManager.isGatedMode())
    }

    @Test
    fun testSetWsAuthParam_updatesOnlySelectedProfile() {
        val selected = ConnectionProfile("selected", "Selected", "selected.example.com")
        val other = ConnectionProfile("other", "Other", "other.example.com")
        AuthManager.saveConnectionProfiles(listOf(selected, other))
        AuthManager.setSelectedProfileId("selected")

        AuthManager.setWsAuthParam("ticket")

        val profiles = AuthManager.getConnectionProfiles()
        assertEquals("ticket", profiles.first { it.id == "selected" }.wsAuthParam)
        assertNull(profiles.first { it.id == "other" }.wsAuthParam)
    }

    @Test
    fun testLegacyProfile_fallsBackToPersistedGlobalMode() {
        val legacy = ConnectionProfile("legacy", "Legacy", "legacy.example.com")
        AuthManager.saveConnectionProfiles(listOf(legacy))
        AuthManager.setSelectedProfileId("legacy")
        AuthManager.serverStore.update { state ->
            state.copy(
                wsAuthParam = "ticket",
                connectionProfiles = listOf(legacy),
            )
        }

        assertEquals(true, AuthManager.isGatedMode())
    }

    @Test
    fun testGetAndSetHost() {
        AuthManager.setHost("10.0.0.1")
        assertEquals("10.0.0.1", AuthManager.getHost())
    }

    @Test
    fun testGetAndSetPort() {
        AuthManager.setPort(9090)
        assertEquals(9090, AuthManager.getPort())
    }

    @Test
    fun testGetAndSetAutoReconnect() {
        AuthManager.setAutoReconnect(false)
        assertEquals(false, AuthManager.isAutoReconnect())
    }

    @Test
    fun testBaseUrl() {
        AuthManager.setHost("hermes.local")
        AuthManager.setPort(1234)
        assertEquals("https://hermes.local:1234/", AuthManager.baseUrl())
    }

    @Test
    fun testTokenCaching() {
        AuthManager.ensureDefaultProfile()
        every { mockPrefs.getString("token_${AuthManager.DEFAULT_PROFILE_ID}", null) } returns "first-token"
        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
        assertEquals("first-token", AuthManager.getToken())

        // Update the mockPrefs directly, but since getToken uses the cache, it should still return the first token
        every { mockPrefs.getString("token_${AuthManager.DEFAULT_PROFILE_ID}", null) } returns "second-token"
        assertEquals("first-token", AuthManager.getToken())

        // Clear token initialized manually since this happens upon selecting profile id
        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
        assertEquals("second-token", AuthManager.getToken())

        // Test setToken
        AuthManager.setToken("third-token")
        assertEquals("third-token", AuthManager.getToken())
    }

    @Test
    fun testWsUrl() {
        AuthManager.ensureDefaultProfile()
        every { mockPrefs.getString("token_${AuthManager.DEFAULT_PROFILE_ID}", null) } returns "token123"
        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
        AuthManager.setBaseUrl("https://hermes.local:1234/")
        assertEquals("wss://hermes.local:1234/api/ws?token=token123", AuthManager.wsUrl())
    }

    @Test
    fun testWsUrl_nullToken() {
        AuthManager.ensureDefaultProfile()
        every { mockPrefs.getString("token_${AuthManager.DEFAULT_PROFILE_ID}", null) } returns null
        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
        AuthManager.setBaseUrl("https://hermes.local:1234/")
        assertEquals("wss://hermes.local:1234/api/ws?token=", AuthManager.wsUrl())
    }

    @Test
    fun testWsUrlWithCredential_doesNotPersistHandshakeTicket() {
        AuthManager.ensureDefaultProfile()
        every { mockPrefs.getString("token_${AuthManager.DEFAULT_PROFILE_ID}", null) } returns "token123"
        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
        AuthManager.setBaseUrl("https://hermes.local:1234/")

        assertEquals(
            "wss://hermes.local:1234/api/ws?ticket=fresh-ticket",
            AuthManager.wsUrlWithCredential("fresh-ticket"),
        )
        assertEquals("token123", AuthManager.getToken())
    }

    // ── TEST-08: Token routing through selected profile ─────────────────

    @Test
    fun testGetToken_usesProfileTokenWhenSelected() {
        val profile1 = ConnectionProfile("prof-1", "Profile 1", "127.0.0.1", 9119)
        AuthManager.saveConnectionProfiles(listOf(profile1))
        every { mockPrefs.getString("token_prof-1", null) } returns "profile-specific-token"
        AuthManager.setSelectedProfileId("prof-1")
        assertEquals("profile-specific-token", AuthManager.getToken())
    }

    @Test
    fun testGetToken_returnsNullWhenProfileHasNoToken() {
        val profile1 = ConnectionProfile("prof-1", "Profile 1", "127.0.0.1", 9119)
        AuthManager.saveConnectionProfiles(listOf(profile1))
        every { mockPrefs.getString("token_prof-1", null) } returns null
        AuthManager.setSelectedProfileId("prof-1")
        assertNull(AuthManager.getToken())
    }

    @Test
    fun testSetToken_writesToProfileTokenWhenSelected() {
        val profile1 = ConnectionProfile("prof-1", "Profile 1", "127.0.0.1", 9119)
        AuthManager.saveConnectionProfiles(listOf(profile1))
        AuthManager.setSelectedProfileId("prof-1")

        AuthManager.setToken("new-profile-token")

        verify { mockEditor.putString("token_prof-1", "new-profile-token") }
        verify { mockEditor.apply() }
        // Should NOT write to a global token key
        verify(exactly = 0) { mockEditor.putString("auth_token", any()) }
    }

    @Test
    fun testSetToken_writesToDefaultProfileWhenNoSelection() {
        AuthManager.ensureDefaultProfile()

        AuthManager.setToken("default-token")

        verify { mockEditor.putString("token_${AuthManager.DEFAULT_PROFILE_ID}", "default-token") }
        verify { mockEditor.apply() }
        verify(exactly = 0) { mockEditor.putString("auth_token", any()) }
    }

    @Test
    fun testGetHost_usesProfileHostWhenSelected() {
        val profile = ConnectionProfile("prof-1", "Work", "10.0.0.1", 9220)
        AuthManager.saveConnectionProfiles(listOf(profile))
        AuthManager.setSelectedProfileId("prof-1")

        assertEquals("10.0.0.1", AuthManager.getHost())
    }

    @Test
    fun testGetPort_usesProfilePortWhenSelected() {
        val profile = ConnectionProfile("prof-1", "Work", "10.0.0.1", 9220)
        AuthManager.saveConnectionProfiles(listOf(profile))
        AuthManager.setSelectedProfileId("prof-1")

        assertEquals(9220, AuthManager.getPort())
    }

    @Test
    fun testGetHost_fallsBackToDefaultWhenSelectedProfileIdNotFound() {
        AuthManager.ensureDefaultProfile()
        AuthManager.setSelectedProfileId("nonexistent")
        AuthManager.saveConnectionProfiles(emptyList())
        AuthManager.setHost("192.168.1.1")

        assertEquals("192.168.1.1", AuthManager.getHost())
    }

    @Test
    fun testSetHost_updatesProfileWhenSelected() {
        val profile = ConnectionProfile("prof-1", "Home", "10.0.0.1", 9119)
        AuthManager.saveConnectionProfiles(listOf(profile))
        AuthManager.setSelectedProfileId("prof-1")

        AuthManager.setHost("10.0.0.99")

        val updated = AuthManager.getConnectionProfiles().first()
        assertEquals("http://10.0.0.99:9119/", updated.resolvedBaseUrl)
    }

    @Test
    fun testSetPort_updatesProfileWhenSelected() {
        val profile = ConnectionProfile("prof-1", "Home", "10.0.0.1", 9119)
        AuthManager.saveConnectionProfiles(listOf(profile))
        AuthManager.setSelectedProfileId("prof-1")

        AuthManager.setPort(9999)

        val updated = AuthManager.getConnectionProfiles().first()
        assertEquals("http://10.0.0.1:9999/", updated.resolvedBaseUrl)
    }

    @Test
    fun testProfileTokenRoundTrip() {
        AuthManager.ensureDefaultProfile()
        AuthManager.setProfileToken("prof-a", "token-a")

        verify { mockEditor.putString("token_prof-a", "token-a") }
        verify { mockEditor.apply() }

        // Mock read-back
        every { mockPrefs.getString("token_prof-a", null) } returns "token-a"
        assertEquals("token-a", AuthManager.getProfileToken("prof-a"))
    }

    @Test
    fun testConnectionProfiles_roundTrip() {
        val profiles =
            listOf(
                ConnectionProfile("a", "A", "10.0.0.1", 9119),
                ConnectionProfile("b", "B", "10.0.0.2", 9220),
            )
        AuthManager.saveConnectionProfiles(profiles)

        val loaded = AuthManager.getConnectionProfiles()
        assertEquals(2, loaded.size)
        assertEquals("A", loaded[0].name)
        assertEquals("10.0.0.2", loaded[1].host)
    }

    // ── TEST-11: Multi-profile token scenarios ─────────────────────────

    @Test
    fun testGetToken_switchesTokenWhenProfileChanges() {
        val profileA = ConnectionProfile("prof-a", "Profile A", "127.0.0.1", 9119)
        val profileB = ConnectionProfile("prof-b", "Profile B", "127.0.0.1", 9119)
        AuthManager.saveConnectionProfiles(listOf(profileA, profileB))

        // Profile A selected → returns token_a
        every { mockPrefs.getString("token_prof-a", null) } returns "token-for-a"
        AuthManager.setSelectedProfileId("prof-a")
        assertEquals("token-for-a", AuthManager.getToken())

        // Switch to Profile B → returns token_b
        every { mockPrefs.getString("token_prof-b", null) } returns "token-for-b"
        AuthManager.setSelectedProfileId("prof-b") // Required to clear token cache
        assertEquals("token-for-b", AuthManager.getToken())

        // Switch back to Profile A → still returns token_a
        every { mockPrefs.getString("token_prof-a", null) } returns "token-for-a"
        AuthManager.setSelectedProfileId("prof-a") // Required to clear token cache
        assertEquals("token-for-a", AuthManager.getToken())
    }

    @Test
    fun mediaCredentialSnapshotWaitsForCompleteProfileTransition() {
        val profileA = ConnectionProfile("prof-a", "A", baseUrl = "https://same.example.test/", wsAuthParam = "token")
        val profileB = ConnectionProfile("prof-b", "B", baseUrl = "https://same.example.test/", wsAuthParam = "token")
        AuthManager.saveConnectionProfiles(listOf(profileA, profileB))
        every { mockPrefs.getString("token_prof-a", null) } returns "token-a"
        val blockTokenB = AtomicBoolean(false)
        val tokenReadStarted = CountDownLatch(1)
        val releaseTokenRead = CountDownLatch(1)
        every { mockPrefs.getString("token_prof-b", null) } answers {
            if (blockTokenB.get()) {
                tokenReadStarted.countDown()
                check(releaseTokenRead.await(5, TimeUnit.SECONDS))
            }
            "token-b"
        }

        AuthManager.setSelectedProfileId("prof-b")
        val expectedNew = GatewayFileClient.currentContext().scope
        AuthManager.setSelectedProfileId("prof-a")
        val expectedOld = GatewayFileClient.currentContext().scope
        assertEquals("prof-a", CookieManager.currentServerOrNull())

        blockTokenB.set(true)
        val transition = Thread { AuthManager.setSelectedProfileId("prof-b") }.apply { start() }
        try {
            assertTrue(tokenReadStarted.await(5, TimeUnit.SECONDS))
            var captured: com.m57.hermescontrol.data.remote.MediaCacheScope? = null
            val snapshotFinished = CountDownLatch(1)
            Thread {
                captured = GatewayFileClient.currentContext().scope
                snapshotFinished.countDown()
            }.start()
            assertTrue(!snapshotFinished.await(100, TimeUnit.MILLISECONDS))
            releaseTokenRead.countDown()
            transition.join(5_000)
            assertTrue(snapshotFinished.await(5, TimeUnit.SECONDS))

            assertTrue(captured == expectedOld || captured == expectedNew)
            assertEquals(expectedNew, captured)
            assertEquals("prof-b", CookieManager.currentServerOrNull())
        } finally {
            releaseTokenRead.countDown()
            transition.join(5_000)
        }
    }

    @Test
    fun refreshedTokenStaysWithCapturedProfileAfterProfileSwitch() {
        val profileA = ConnectionProfile("prof-a", "A", baseUrl = "https://same.example.test/", wsAuthParam = "token")
        val profileB = ConnectionProfile("prof-b", "B", baseUrl = "https://same.example.test/", wsAuthParam = "token")
        AuthManager.saveConnectionProfiles(listOf(profileA, profileB))
        every { mockPrefs.getString("token_prof-a", null) } returns "token-a-old"
        every { mockPrefs.getString("token_prof-b", null) } returns "token-b"
        AuthManager.setSelectedProfileId("prof-a")
        val captured = AuthManager.credentialBoundary()

        AuthManager.setSelectedProfileId("prof-b")
        val accepted = AuthManager.commitRefreshedToken(captured, "token-a-refreshed")

        assertEquals(false, accepted)
        verify { mockEditor.putString("token_prof-a", "token-a-refreshed") }
        assertEquals("prof-b", AuthManager.credentialBoundary().profileId)
        assertEquals("token-b", AuthManager.getToken())
    }

    @Test
    fun refreshedTokenIsRejectedWhenCapturedProfileEndpointChanges() {
        val original = ConnectionProfile("prof-a", "A", baseUrl = "https://old.example.test/", wsAuthParam = "token")
        AuthManager.saveConnectionProfiles(listOf(original))
        every { mockPrefs.getString("token_prof-a", null) } returns "token-a-old"
        AuthManager.setSelectedProfileId("prof-a")
        val captured = AuthManager.credentialBoundary()

        AuthManager.saveConnectionProfiles(
            listOf(original.copy(baseUrl = "https://new.example.test/")),
        )
        val accepted = AuthManager.commitRefreshedToken(captured, "token-from-old-server")

        assertFalse(accepted)
        verify(exactly = 0) { mockEditor.putString("token_prof-a", "token-from-old-server") }
        assertEquals("token-a-old", AuthManager.getToken())
        assertEquals("token-a-old", AuthManager.tokenFlow.value)
    }

    @Test
    fun refreshedTokenIsRejectedWhenCapturedProfileAuthModeChanges() {
        val original = ConnectionProfile("prof-a", "A", baseUrl = "https://same.example.test/", wsAuthParam = "token")
        AuthManager.saveConnectionProfiles(listOf(original))
        AuthManager.setSelectedProfileId("prof-a")
        val captured = AuthManager.credentialBoundary()

        AuthManager.saveConnectionProfiles(listOf(original.copy(wsAuthParam = "ticket")))
        val accepted = AuthManager.commitRefreshedToken(captured, "direct-token")

        assertFalse(accepted)
        verify(exactly = 0) { mockEditor.putString("token_prof-a", "direct-token") }
    }

    @Test
    fun refreshedTokenIsRejectedWhenCapturedProfileIsDeleted() {
        val original = ConnectionProfile("prof-a", "A", baseUrl = "https://same.example.test/", wsAuthParam = "token")
        AuthManager.saveConnectionProfiles(listOf(original))
        AuthManager.setSelectedProfileId("prof-a")
        val captured = AuthManager.credentialBoundary()

        AuthManager.saveConnectionProfiles(emptyList())
        val accepted = AuthManager.commitRefreshedToken(captured, "orphaned-token")

        assertFalse(accepted)
        verify(exactly = 0) { mockEditor.putString("token_prof-a", "orphaned-token") }
    }

    @Test
    fun refreshedTokenPublicationIsAtomicWithProfileSwitch() {
        assertTokenFlowPublicationSerialized(refresh = true)
    }

    @Test
    fun setTokenPublicationIsAtomicWithProfileSwitch() {
        assertTokenFlowPublicationSerialized(refresh = false)
    }

    private fun assertTokenFlowPublicationSerialized(refresh: Boolean) {
        val profileA = ConnectionProfile("prof-a", "A", baseUrl = "https://same.example.test/", wsAuthParam = "token")
        val profileB = ConnectionProfile("prof-b", "B", baseUrl = "https://same.example.test/", wsAuthParam = "token")
        AuthManager.saveConnectionProfiles(listOf(profileA, profileB))
        every { mockPrefs.getString("token_prof-a", null) } returns "token-a-old"
        every { mockPrefs.getString("token_prof-b", null) } returns "token-b"
        AuthManager.setSelectedProfileId("prof-a")
        val captured = AuthManager.credentialBoundary()
        val publicationReached = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        AuthManager.beforeTokenFlowPublicationForTest = {
            publicationReached.countDown()
            check(releasePublication.await(5, TimeUnit.SECONDS))
        }

        var accepted: Boolean? = null
        val mutation =
            Thread {
                if (refresh) {
                    accepted = AuthManager.commitRefreshedToken(captured, "token-a-new")
                } else {
                    AuthManager.setToken("token-a-new")
                }
            }.apply { start() }
        val switched = CountDownLatch(1)
        val switch =
            Thread {
                AuthManager.setSelectedProfileId("prof-b")
                switched.countDown()
            }
        try {
            assertTrue("publication not reached; accepted=$accepted", publicationReached.await(5, TimeUnit.SECONDS))
            switch.start()
            assertTrue(!switched.await(100, TimeUnit.MILLISECONDS))
            releasePublication.countDown()
            mutation.join(5_000)
            switch.join(5_000)
            assertTrue(!mutation.isAlive && !switch.isAlive)

            if (refresh) assertEquals(true, accepted)
            verify { mockEditor.putString("token_prof-a", "token-a-new") }
            assertEquals("prof-b", AuthManager.credentialBoundary().profileId)
            assertEquals("token-b", AuthManager.credentialBoundary().token)
            assertEquals("token-b", AuthManager.tokenFlow.value)
        } finally {
            AuthManager.beforeTokenFlowPublicationForTest = null
            releasePublication.countDown()
            mutation.join(5_000)
            if (switch.state != Thread.State.NEW) switch.join(5_000)
        }
    }

    @Test
    fun setTokenAndProfileSwitchNeverExposeMixedMediaCredentials() {
        assertTokenMutationSerialized { AuthManager.setToken("token-a-new") }
    }

    @Test
    fun setProfileTokenAndProfileSwitchNeverExposeMixedMediaCredentials() {
        assertTokenMutationSerialized { AuthManager.setProfileToken("prof-a", "token-a-new") }
    }

    private fun assertTokenMutationSerialized(mutate: () -> Unit) {
        val profileA = ConnectionProfile("prof-a", "A", baseUrl = "https://same.example.test/", wsAuthParam = "token")
        val profileB = ConnectionProfile("prof-b", "B", baseUrl = "https://same.example.test/", wsAuthParam = "token")
        AuthManager.saveConnectionProfiles(listOf(profileA, profileB))
        val stored = java.util.concurrent.ConcurrentHashMap<String, String>()
        stored["token_prof-a"] = "token-a-old"
        stored["token_prof-b"] = "token-b"
        every { mockPrefs.getString(match { it.startsWith("token_prof-") }, null) } answers {
            stored[firstArg()]
        }
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        every { mockEditor.putString("token_prof-a", "token-a-new") } answers {
            writeStarted.countDown()
            check(releaseWrite.await(5, TimeUnit.SECONDS))
            stored["token_prof-a"] = "token-a-new"
            mockEditor
        }

        AuthManager.setSelectedProfileId("prof-b")
        val expectedB = GatewayFileClient.currentContext().scope
        AuthManager.setSelectedProfileId("prof-a")
        val oldA = GatewayFileClient.currentContext().scope

        val mutation = Thread(mutate).apply { start() }
        val switch = Thread { AuthManager.setSelectedProfileId("prof-b") }
        var captured: com.m57.hermescontrol.data.remote.MediaCacheScope? = null
        val snapshotFinished = CountDownLatch(1)
        try {
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
            switch.start()
            Thread {
                captured = GatewayFileClient.currentContext().scope
                snapshotFinished.countDown()
            }.start()
            assertTrue(!snapshotFinished.await(100, TimeUnit.MILLISECONDS))
            releaseWrite.countDown()
            mutation.join(5_000)
            switch.join(5_000)
            assertTrue(!mutation.isAlive && !switch.isAlive)
            assertTrue(snapshotFinished.await(5, TimeUnit.SECONDS))

            val snapshot = checkNotNull(captured)
            assertTrue(snapshot.profileId == "prof-b" || snapshot.profileId == "prof-a")
            if (snapshot.profileId == "prof-b") assertEquals(expectedB, snapshot) else assertTrue(snapshot != oldA)
            assertEquals("token-a-new", stored["token_prof-a"])
            assertEquals("token-b", stored["token_prof-b"])
            assertEquals("prof-b", AuthManager.credentialBoundary().profileId)
            assertEquals("token-b", AuthManager.getToken())
            assertEquals(expectedB, GatewayFileClient.currentContext().scope)
        } finally {
            releaseWrite.countDown()
            mutation.join(5_000)
            if (switch.state != Thread.State.NEW) switch.join(5_000)
        }
    }

    @Test
    fun testGetToken_profileWithNoTokenReturnsNull() {
        val profileA = ConnectionProfile("prof-a", "Profile A", "127.0.0.1", 9119)
        val profileB = ConnectionProfile("prof-b", "Profile B", "127.0.0.1", 9119)
        AuthManager.saveConnectionProfiles(listOf(profileA, profileB))

        // Profile A is selected but has no token → null (no global fallback)
        every { mockPrefs.getString("token_prof-a", null) } returns null
        AuthManager.setSelectedProfileId("prof-a")
        assertNull(AuthManager.getToken())

        // Switch to Profile B, which also has no token → still null
        every { mockPrefs.getString("token_prof-b", null) } returns null
        AuthManager.setSelectedProfileId("prof-b")
        assertNull(AuthManager.getToken())
    }

    @Test
    fun testGetToken_selectiveProfileTokens_someNull() {
        val profileA = ConnectionProfile("prof-a", "Profile A", "127.0.0.1", 9119)
        val profileB = ConnectionProfile("prof-b", "Profile B", "127.0.0.1", 9119)
        val profileC = ConnectionProfile("prof-c", "Profile C", "127.0.0.1", 9119)
        AuthManager.saveConnectionProfiles(listOf(profileA, profileB, profileC))

        // Profile A has a token
        every { mockPrefs.getString("token_prof-a", null) } returns "token-a"
        AuthManager.setSelectedProfileId("prof-a")
        assertEquals("token-a", AuthManager.getToken())

        // Profile B has no token → null
        every { mockPrefs.getString("token_prof-b", null) } returns null
        AuthManager.setSelectedProfileId("prof-b")
        assertNull(AuthManager.getToken())

        // Profile C has its own token too
        every { mockPrefs.getString("token_prof-c", null) } returns "token-c"
        AuthManager.setSelectedProfileId("prof-c")
        assertEquals("token-c", AuthManager.getToken())
    }

    @Test
    fun testSetToken_switchingProfiles_maintainsSeparateTokens() {
        val profileA = ConnectionProfile("prof-a", "Profile A", "127.0.0.1", 9119)
        val profileB = ConnectionProfile("prof-b", "Profile B", "127.0.0.1", 9119)
        AuthManager.saveConnectionProfiles(listOf(profileA, profileB))

        // Set a token while Profile A is selected
        AuthManager.setSelectedProfileId("prof-a")
        AuthManager.setToken("set-while-on-profile-a")
        verify { mockEditor.putString("token_prof-a", "set-while-on-profile-a") }
        verify { mockEditor.apply() }

        // Switch to Profile B
        AuthManager.setSelectedProfileId("prof-b")

        // Set a different token for Profile B
        AuthManager.setToken("set-while-on-profile-b")
        verify { mockEditor.putString("token_prof-b", "set-while-on-profile-b") }
        verify(exactly = 0) { mockEditor.putString("auth_token", any()) }
    }

    @Test
    fun testSetToken_toNull_clearsProfileToken() {
        val profileA = ConnectionProfile("prof-a", "Profile A", "127.0.0.1", 9119)
        AuthManager.saveConnectionProfiles(listOf(profileA))

        AuthManager.setSelectedProfileId("prof-a")

        AuthManager.setToken(null)

        verify { mockEditor.remove("token_prof-a") }
        verify { mockEditor.apply() }
    }

    @Test
    fun testGetSelectedProfileId_returnsNullForEmptyString() {
        AuthManager.setSelectedProfileId("")
        assertNull(AuthManager.getSelectedProfileId())

        AuthManager.setSelectedProfileId("  ")
        assertNull(AuthManager.getSelectedProfileId())
    }

    @Test
    fun testEnsureDefaultProfile_createsDefaultWhenMissing() {
        AuthManager.saveConnectionProfiles(emptyList())
        AuthManager.setSelectedProfileId(null)

        AuthManager.ensureDefaultProfile()

        val profiles = AuthManager.getConnectionProfiles()
        assertEquals(1, profiles.size)
        assertEquals(AuthManager.DEFAULT_PROFILE_ID, profiles[0].id)
        assertEquals(AuthManager.DEFAULT_PROFILE_NAME, profiles[0].name)
        assertEquals(AuthManager.DEFAULT_PROFILE_ID, AuthManager.getSelectedProfileId())
    }

    @Test
    fun testEnsureDefaultProfile_doesNotDuplicateExisting() {
        val existing = ConnectionProfile("prof-1", "Work", "10.0.0.1", 9119)
        AuthManager.saveConnectionProfiles(listOf(existing))
        AuthManager.setSelectedProfileId("prof-1")

        AuthManager.ensureDefaultProfile()

        val profiles = AuthManager.getConnectionProfiles()
        assertEquals(1, profiles.size)
        assertEquals("prof-1", AuthManager.getSelectedProfileId())
    }

    @Test
    fun testMigrateLegacyToken_foldsIntoDefaultProfile() {
        // Simulate a legacy install: no profiles, a global auth_token, and a selected id of null.
        every { mockPrefs.getString("auth_token", null) } returns "legacy-standalone-token"

        // Re-initialize so init() runs the one-time migration
        AuthManager.resetAuthStateForTest()
        AuthManager.init(testContext)

        // token should now be persisted under the default profile key, and the legacy key removed
        verify { mockEditor.putString("token_${AuthManager.DEFAULT_PROFILE_ID}", "legacy-standalone-token") }
        verify { mockEditor.putString("legacy_default_migrated", "true") }
    }
}
