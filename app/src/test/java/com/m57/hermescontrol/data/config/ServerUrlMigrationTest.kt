package com.m57.hermescontrol.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlMigrationTest {
    @Test
    fun `migrates legacy top level host and port without changing transport`() {
        val legacy =
            ServerStoreState(
                host = "192.0.2.10",
                port = 9119,
                baseUrl = null,
            )

        val migrated = ServerUrlMigration.migrateState(legacy)

        assertEquals("http://192.0.2.10:9119/", migrated.baseUrl)
    }

    @Test
    fun `migrates legacy profiles and preserves profile selection`() {
        val legacy =
            ServerStoreState(
                connectionProfiles =
                    listOf(
                        ConnectionProfile(
                            id = "legacy",
                            name = "Legacy",
                            host = "2001:db8::1",
                            port = 9443,
                            baseUrl = null,
                        ),
                    ),
                selectedProfileId = "legacy",
            )

        val migrated = ServerUrlMigration.migrateState(legacy)
        val profile = migrated.connectionProfiles.single()

        assertEquals("legacy", migrated.selectedProfileId)
        assertEquals("http://[2001:db8::1]:9443/", profile.baseUrl)
    }

    @Test
    fun `does not overwrite an existing complete URL`() {
        val state =
            ServerStoreState(
                baseUrl = "https://hermes.example.com:9119/prefix/",
            )

        val migrated = ServerUrlMigration.migrateState(state)

        assertEquals(state.baseUrl, migrated.baseUrl)
    }

    @Test
    fun `fresh default uses HTTPS`() {
        val fresh = ServerStoreSerializer.defaultValue

        assertEquals(
            "https://127.0.0.1:9119/",
            fresh.baseUrl,
        )
        assertNotNull(fresh.baseUrl)
        assertTrue(fresh.connectionProfiles.isEmpty())
    }
}
