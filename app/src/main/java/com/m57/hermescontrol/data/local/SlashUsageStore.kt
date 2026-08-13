package com.m57.hermescontrol.data.local

import android.content.Context
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
import java.io.InputStream
import java.io.OutputStream

@Serializable
private data class SlashUsageState(
    val countsByProfile: Map<String, Map<String, Int>> = emptyMap(),
)

private object SlashUsageSerializer : Serializer<SlashUsageState> {
    override val defaultValue = SlashUsageState()

    override suspend fun readFrom(input: InputStream): SlashUsageState =
        runCatching {
            OkHttpProvider.json.decodeFromString(SlashUsageState.serializer(), input.readBytes().decodeToString())
        }.getOrDefault(defaultValue)

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
    private val context: Context,
) {
    open fun counts(profileId: String): Flow<Map<String, Int>> =
        context.slashUsageDataStore.data
            .catch { emit(SlashUsageState()) }
            .map { it.countsByProfile[profileId].orEmpty() }

    open suspend fun recordUse(
        profileId: String,
        command: String,
    ) {
        withContext(Dispatchers.IO) {
            runCatching {
                context.slashUsageDataStore.updateData { state ->
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
}
