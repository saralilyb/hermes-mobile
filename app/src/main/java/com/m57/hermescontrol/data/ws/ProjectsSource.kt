package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.data.model.ProjectsListResponse
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

sealed interface ProjectsResult {
    data class Ready(val projects: List<ProjectInfo>) : ProjectsResult

    data object Initial : ProjectsResult

    data object Offline : ProjectsResult

    data object Unsupported : ProjectsResult

    data object Failed : ProjectsResult
}

interface ProjectsSource {
    suspend fun fetchProjects(connection: SessionLiveConnection): ProjectsResult
}

/** No queue, reconnect side effect, implicit management profile, or disk access. */
class HermesProjectsSource(
    private val isConnectionCurrent: (SessionLiveConnection) -> Boolean = { connection ->
        AuthManager.getSelectedProfileId() == connection.profileId &&
            HermesWsClient.connectionBinding(connection.profileId)?.generation == connection.generation
    },
    private val boundRpcRequest: suspend (SessionLiveConnection, String, Map<String, Any>) -> Any? =
        { connection, method, params ->
            val binding =
                HermesWsClient.connectionBinding(connection.profileId)
                    ?.takeIf { it.generation == connection.generation }
                    ?: throw HermesWsClient.HermesRpcException("Connection changed")
            HermesWsClient.requestForProfileConnectionAwaited(binding, method, params)
        },
) : ProjectsSource {
    // Projects P1: the shared RPC converter returns Doubles, including integer wire values.
    // Normalize only exact finite integers here; never truncate or saturate invalid DTO fields.
    private fun normalizeProjectNumbers(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.mapValues { normalizeProjectNumbers(it.value) })
            is JsonArray -> JsonArray(element.map { normalizeProjectNumbers(it) })
            is JsonPrimitive -> {
                val value = if (element.isString || element.longOrNull != null) null else element.doubleOrNull
                // Long.MAX_VALUE.toDouble() rounds UP to 2^63, so the upper bound is exclusive.
                if (value != null && value.isFinite() &&
                    value >= Long.MIN_VALUE.toDouble() && value < Long.MAX_VALUE.toDouble() &&
                    value == kotlin.math.floor(value)
                ) {
                    JsonPrimitive(value.toLong())
                } else {
                    element
                }
            }
        }

    override suspend fun fetchProjects(connection: SessionLiveConnection): ProjectsResult {
        if (!isConnectionCurrent(connection)) return ProjectsResult.Offline
        val result =
            try {
                val raw = boundRpcRequest(connection, WsMethods.PROJECTS_LIST, emptyMap())
                val element = normalizeProjectNumbers(SessionLiveStatusDecoder.anyToJsonElement(raw))
                if (raw == null || element is JsonObject && !element.containsKey("projects")) {
                    ProjectsResult.Unsupported
                } else {
                    ProjectsResult.Ready(
                        OkHttpProvider.json.decodeFromJsonElement(ProjectsListResponse.serializer(), element).projects,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HermesWsClient.HermesRpcException) {
                if (e.code == -32601) ProjectsResult.Unsupported else ProjectsResult.Failed
            } catch (_: Exception) {
                ProjectsResult.Failed
            }
        // Fence errors as well as successful snapshots, independent of coroutine cancellation.
        return if (isConnectionCurrent(connection)) result else ProjectsResult.Offline
    }
}
