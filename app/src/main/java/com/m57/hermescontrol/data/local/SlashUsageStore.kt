package com.m57.hermescontrol.data.local

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

@Serializable
data class SlashUsageState(
    val countsByProfile: Map<String, Map<String, Int>> = emptyMap(),
)

object SlashUsageSerializer : Serializer<SlashUsageState> {
    override val defaultValue = SlashUsageState()

    override suspend fun readFrom(input: InputStream): SlashUsageState =
        try {
            OkHttpProvider.json.decodeFromString(SlashUsageState.serializer(), input.readBytes().decodeToString())
        } catch (exception: kotlinx.serialization.SerializationException) {
            throw CorruptionException("Cannot read slash usage state", exception)
        }

    override suspend fun writeTo(
        t: SlashUsageState,
        output: OutputStream,
    ) {
        output.write(OkHttpProvider.json.encodeToString(SlashUsageState.serializer(), t).encodeToByteArray())
    }
}

private val Context.slashUsageDataStore: DataStore<SlashUsageState> by dataStore(
    fileName = "slash_usage.json",
    serializer = SlashUsageSerializer,
)

open class SlashUsageStore(
    context: Context,
    private val dataStore: DataStore<SlashUsageState> = context.slashUsageDataStore,
) {
    open fun counts(profileId: String): Flow<Map<String, Int>> =
        dataStore.data
            .catch { exception ->
                if (exception is CorruptionException) {
                    throw exception
                } else if (exception is IOException) {
                    emit(SlashUsageState())
                } else {
                    throw exception
                }
            }
            .map { it.countsByProfile[profileId].orEmpty() }

    open suspend fun recordUse(
        profileId: String,
        command: String,
    ) {
        withContext(Dispatchers.IO) {
            dataStore.updateData { state ->
                val profileCounts = state.countsByProfile[profileId].orEmpty()
                state.copy(
                    countsByProfile =
                        state.countsByProfile +
                            (
                                profileId to
                                    (profileCounts + (command to ((profileCounts[command] ?: 0) + 1)))
                            ),
                )
            }
        }
    }
}
