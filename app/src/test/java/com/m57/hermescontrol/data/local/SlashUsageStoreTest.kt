package com.m57.hermescontrol.data.local

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files

class SlashUsageStoreTest {
    @Test
    fun serializer_reportsMalformedJsonAsCorruption() {
        assertThrows(CorruptionException::class.java) {
            runTest {
                SlashUsageSerializer.readFrom(ByteArrayInputStream("not-json".encodeToByteArray()))
            }
        }
    }

    @Test
    fun malformedStoreDataSurfacesCorruption() {
        val file = Files.createTempFile("slash-usage-malformed", ".json").toFile()
        file.writeText("not-json")
        val dataStore = DataStoreFactory.create(serializer = SlashUsageSerializer) { file }
        val store = SlashUsageStore(mockk<Context>(relaxed = true), dataStore)

        assertThrows(CorruptionException::class.java) {
            runTest { store.counts("profile-a").first() }
        }
    }

    @Test
    fun concurrentWritesAreSerializedWithoutLostUpdates() =
        runTest {
            val file = Files.createTempFile("slash-usage-concurrent", ".json").toFile().also { it.delete() }
            val dataStore = DataStoreFactory.create(serializer = SlashUsageSerializer) { file }
            val store = SlashUsageStore(mockk<Context>(relaxed = true), dataStore)

            (1..100).map { async { store.recordUse("profile-a", "/help") } }.awaitAll()

            assertEquals(100, store.counts("profile-a").first()["/help"])
        }

    @Test
    fun writeFailureIsNotSwallowed() {
        val directory = Files.createTempDirectory("slash-usage-write-failure").toFile()
        val dataStore = DataStoreFactory.create(serializer = SlashUsageSerializer) { directory }
        val store = SlashUsageStore(mockk<Context>(relaxed = true), dataStore)

        assertThrows(IOException::class.java) {
            runTest { store.recordUse("profile-a", "/help") }
        }
    }
}
