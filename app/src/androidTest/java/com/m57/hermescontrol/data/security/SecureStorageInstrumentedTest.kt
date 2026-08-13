package com.m57.hermescontrol.data.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class SecureStorageInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun cleanBefore() = clearTestStorage()

    @After
    fun cleanAfter() = clearTestStorage()

    @Test
    fun cryptoUsesFreshTwelveByteNonceAndSixteenByteTag() {
        val cipher = AndroidKeystoreBlobCipher()
        val first = cipher.encrypt("auth/token", "secret".toByteArray())
        val second = cipher.encrypt("auth/token", "secret".toByteArray())

        assertEquals(12, first.copyOfRange(5, 17).size)
        assertEquals(17 + 6 + 16, first.size)
        assertFalse(first.copyOfRange(5, 17).contentEquals(second.copyOfRange(5, 17)))
        assertArrayEquals("secret".toByteArray(), cipher.decrypt("auth/token", first))
    }

    @Test(expected = SecureBlobException::class)
    fun aadMismatchFailsClosed() {
        val cipher = AndroidKeystoreBlobCipher()
        cipher.decrypt("auth/other", cipher.encrypt("auth/token", "secret".toByteArray()))
    }

    @Test(expected = SecureBlobException::class)
    fun corruptionFailsClosed() {
        val cipher = AndroidKeystoreBlobCipher()
        val blob = cipher.encrypt("auth/token", "secret".toByteArray())
        blob[blob.lastIndex] = (blob.last().toInt() xor 1).toByte()
        cipher.decrypt("auth/token", blob)
    }

    @Test(expected = SecureBlobException::class)
    fun unknownVersionFailsClosed() {
        val cipher = AndroidKeystoreBlobCipher()
        val blob = cipher.encrypt("auth/token", "secret".toByteArray())
        blob[4] = 2
        cipher.decrypt("auth/token", blob)
    }

    @Test
    fun independentBlobsAndAuthenticatedDelete() {
        val storage = SecureStorage(context)
        storage.putString(SecureStorage.authKey("one"), "first")
        storage.putString(SecureStorage.authKey("two"), "second")
        storage.putString(SecureStorage.authKey("one"), null)

        assertNull(storage.getString(SecureStorage.authKey("one")))
        assertEquals("second", storage.getString(SecureStorage.authKey("two")))
        assertEquals(2, context.noBackupFilesDir.resolve("secure_blobs_v1").listFiles()?.size)
    }

    @Test
    fun migrationResumesAfterInterruptionAndVerifiesEveryByte() {
        legacyAuth().edit().putString("token_default", "token-value").putString("db_password", "cGFzcw==").commit()
        var interrupted = false
        try {
            SecureStorage(context) { copied -> if (copied == 1) error("simulated process interruption") }
        } catch (_: IllegalStateException) {
            interrupted = true
        }
        assertTrue(interrupted)
        assertEquals("COPYING", migrationPrefs().getString("state", null))

        val resumed = SecureStorage(context)
        assertEquals("token-value", resumed.getString(SecureStorage.authKey("token_default")))
        assertEquals("cGFzcw==", resumed.getString(SecureStorage.authKey("db_password")))
        assertEquals("DUAL_WRITE", migrationPrefs().getString("state", null))
    }

    @Test
    fun concurrentWriteWaitsForMigrationAndRemainsAuthoritative() {
        legacyAuth().edit().putString("token_default", "old-token").commit()
        val migrationPaused = java.util.concurrent.CountDownLatch(1)
        val releaseMigration = java.util.concurrent.CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val migrating =
            executor.submit {
                SecureStorage(context) { copied ->
                    if (copied == 1) {
                        migrationPaused.countDown()
                        releaseMigration.await()
                    }
                }
            }
        assertTrue(migrationPaused.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val writing =
            executor.submit {
                SecureStorage(context).putString(
                    SecureStorage.authKey("token_default"),
                    "new-token",
                )
            }

        releaseMigration.countDown()
        migrating.get()
        writing.get()
        executor.shutdown()

        val storage = SecureStorage(context)
        assertEquals("new-token", storage.getString(SecureStorage.authKey("token_default")))
        assertEquals("new-token", legacyAuth().getString("token_default", null))
    }

    @Test
    fun newFirstReadDoesNotFallbackWhenBlobIsCorrupt() {
        legacyAuth().edit().putString("token_default", "legacy-token").commit()
        val storage = SecureStorage(context)
        val key = SecureStorage.authKey("token_default")
        val file = blobFile(key)
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)

        var failedClosed = false
        try {
            storage.getString(key)
        } catch (_: SecureBlobException) {
            failedClosed = true
        }
        assertTrue(failedClosed)
    }

    @Test
    fun dualWriteUpdatesLegacyWithoutDeletingLegacyFiles() {
        val prefs = legacyAuth()
        prefs.edit().putString("token_default", "old").commit()
        val storage = SecureStorage(context)
        storage.putString(SecureStorage.authKey("token_default"), "new")

        assertEquals("new", prefs.getString("token_default", null))
        assertTrue(legacyFile(LegacySecurePreferences.AUTH_FILE).exists())
    }

    @Test
    fun migrationLeavesLegacyCiphertextReadable() {
        val prefs = legacyAuth()
        prefs.edit().putString("token_default", "legacy-token").commit()

        val storage = SecureStorage(context)

        assertEquals("legacy-token", storage.getString(SecureStorage.authKey("token_default")))
        assertEquals("legacy-token", prefs.getString("token_default", null))
    }

    @Test
    fun malformedLegacyTokenDoesNotCrashStartup() {
        legacyAuth()
            .edit()
            .putStringSet("token_default", setOf("invalid-legacy-type"))
            .commit()

        val storage = SecureStorage(context)

        assertNull(storage.getString(SecureStorage.authKey("token_default")))
    }

    @Test
    fun legacyBooleanTokenDoesNotBecomeCredentialText() {
        legacyAuth().edit().putBoolean("token_default", true).commit()

        val storage = SecureStorage(context)

        assertNull(storage.getString(SecureStorage.authKey("token_default")))
    }

    @Test
    fun legacyBooleanMigrationMarkerRemainsSupported() {
        legacyAuth().edit().putBoolean("legacy_default_migrated", true).commit()

        val storage = SecureStorage(context)

        assertEquals(
            "true",
            storage.getString(SecureStorage.authKey("legacy_default_migrated")),
        )
    }

    @Test
    fun authenticatedBlobWinsWhenLegacyTokenTypeIsMalformed() {
        val key = SecureStorage.authKey("token_default")
        SecureStorage(context).putString(key, "authenticated-token")
        legacyAuth()
            .edit()
            .putStringSet("token_default", setOf("invalid-legacy-type"))
            .commit()

        val reopened = SecureStorage(context)

        assertEquals("authenticated-token", reopened.getString(key))
        assertEquals("authenticated-token", legacyAuth().getString("token_default", null))
    }

    @Test(expected = SecureBlobException::class)
    fun malformedLegacyDatabasePasswordStillFailsClosed() {
        legacyAuth()
            .edit()
            .putStringSet("db_password", setOf("invalid-legacy-type"))
            .commit()

        SecureStorage(context).getString(SecureStorage.authKey("db_password"))
    }

    @Test
    fun authenticatedDatabasePasswordWinsWhenLegacyTypeIsMalformed() {
        val key = SecureStorage.authKey("db_password")
        val encoded = android.util.Base64.encodeToString(ByteArray(32) { it.toByte() }, android.util.Base64.NO_WRAP)
        SecureStorage(context).putString(key, encoded)
        legacyAuth()
            .edit()
            .putStringSet("db_password", setOf("invalid-legacy-type"))
            .commit()

        assertEquals(encoded, SecureStorage(context).getString(key))
    }

    @Test
    fun migrationRecoversLegacyPreferencesBackupFile() {
        val prefs = legacyAuth()
        prefs.edit().putString("token_default", "backup-token").commit()
        val file = legacyFile(LegacySecurePreferences.AUTH_FILE)
        val backup = file.resolveSibling(file.name + ".bak")
        assertTrue(file.renameTo(backup))

        val storage = SecureStorage(context)

        assertEquals("backup-token", storage.getString(SecureStorage.authKey("token_default")))
    }

    @Test
    fun tombstonePreventsLegacyFallbackAfterDelete() {
        val prefs = legacyAuth()
        prefs.edit().putString("token_default", "legacy-token").commit()
        val storage = SecureStorage(context)

        storage.putString(SecureStorage.authKey("token_default"), null)

        assertNull(storage.getString(SecureStorage.authKey("token_default")))
        assertFalse(prefs.contains("token_default"))
    }

    @Test(expected = SecureBlobException::class)
    fun databasePasswordCannotBeDeletedDuringRollbackWindow() {
        legacyAuth().edit().putString("db_password", "cGFzcw==").commit()
        SecureStorage(context).putString(SecureStorage.authKey("db_password"), null)
    }

    @Test
    fun scopedCookieBytesMigrateWithoutCrossScopeChanges() {
        val legacyCookies =
            EncryptedSharedPreferences.create(
                LegacySecurePreferences.COOKIE_FILE,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        legacyCookies.edit().putString("cookies::profile-a", "serialized-cookie-bytes").commit()

        val storage = SecureStorage(context)

        assertEquals(
            "serialized-cookie-bytes",
            storage.getString(SecureStorage.cookieKey("cookies::profile-a")),
        )
        assertNull(storage.getString(SecureStorage.cookieKey("cookies::profile-b")))
    }

    @Test
    fun existingScopedCookiePreventsLegacyCookieCrossProfileLeak() {
        val storage = SecureStorage(context)
        storage.putString(SecureStorage.authKey("session_cookie"), "legacy-cookie")
        storage.putString(SecureStorage.cookieKey("cookies::profile-a"), "[]")
        val store = com.m57.hermescontrol.data.remote.EncryptedCookieStore(context)

        kotlinx.coroutines.runBlocking {
            assertTrue(store.load("profile-a").isEmpty())
            assertTrue(store.load("profile-b").isEmpty())
        }
    }

    @Test
    fun clearingCookiesSuppressesLegacyCookieFallback() {
        val storage = SecureStorage(context)
        storage.putString(SecureStorage.authKey("session_cookie"), "legacy-cookie")
        val store = com.m57.hermescontrol.data.remote.EncryptedCookieStore(context)

        kotlinx.coroutines.runBlocking {
            store.clearAll()
            assertTrue(store.load("profile-a").isEmpty())
        }
        assertNull(storage.getString(SecureStorage.authKey("session_cookie")))
    }

    @Test(expected = SecureBlobException::class)
    fun malformedAuthenticatedCookiePayloadFailsClosed() {
        val storage = SecureStorage(context)
        storage.putString(SecureStorage.cookieKey("cookies::profile-a"), "not-json")

        kotlinx.coroutines.runBlocking {
            com.m57.hermescontrol.data.remote.EncryptedCookieStore(context).load("profile-a")
        }
    }

    @Test
    fun indexedCookieDeleteSurvivesStorageReopen() {
        val key = SecureStorage.cookieKey("cookies::profile-a")
        SecureStorage(context).putString(key, "[]")

        SecureStorage(context).deletePrefix(SecureStorage.cookiePrefix())

        assertNull(SecureStorage(context).getString(key))
    }

    @Test
    fun freshInstallDoesNotCreateLegacyFiles() {
        SecureStorage(context).putString(SecureStorage.authKey("token_default"), "new")

        assertFalse(legacyFile(LegacySecurePreferences.AUTH_FILE).exists())
        assertFalse(legacyFile(LegacySecurePreferences.COOKIE_FILE).exists())
    }

    @Test
    fun concurrentWritesRemainAtomicAndReadable() {
        val storage = SecureStorage(context)
        val executor = Executors.newFixedThreadPool(8)
        val values = (0 until 64).map { "value-$it-${"x".repeat(256)}" }
        val futures =
            values.map { value ->
                executor.submit(Callable { storage.putString(SecureStorage.authKey("race"), value) })
            }
        futures.forEach { it.get() }
        executor.shutdown()

        assertTrue(storage.getString(SecureStorage.authKey("race")) in values)
    }

    @Test
    fun databasePasswordBytesSurviveReopen() {
        val password = ByteArray(32) { it.toByte() }
        val encoded = android.util.Base64.encodeToString(password, android.util.Base64.NO_WRAP)
        SecureStorage(context).putString(SecureStorage.authKey("db_password"), encoded)
        val reopened = SecureStorage(context)

        assertArrayEquals(
            password,
            android.util.Base64.decode(
                reopened.getString(SecureStorage.authKey("db_password")),
                android.util.Base64.NO_WRAP,
            ),
        )
    }

    private fun legacyAuth() =
        EncryptedSharedPreferences.create(
            LegacySecurePreferences.AUTH_FILE,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private fun migrationPrefs() = context.getSharedPreferences("secure_storage_migration", Context.MODE_PRIVATE)

    private fun blobFile(logicalKey: String): java.io.File {
        val digest = MessageDigest.getInstance("SHA-256").digest(logicalKey.toByteArray())
        return context.noBackupFilesDir.resolve("secure_blobs_v1/${digest.joinToString("") { "%02x".format(it) }}.blob")
    }

    private fun legacyFile(name: String) = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")

    private fun clearTestStorage() {
        context.noBackupFilesDir.resolve("secure_blobs_v1").deleteRecursively()
        context.getSharedPreferences("secure_storage_migration", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(LegacySecurePreferences.AUTH_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(LegacySecurePreferences.COOKIE_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        listOf(
            LegacySecurePreferences.AUTH_FILE,
            LegacySecurePreferences.COOKIE_FILE,
        ).forEach { name ->
            val file = legacyFile(name)
            file.delete()
            file.resolveSibling(file.name + ".bak").delete()
        }
    }
}
