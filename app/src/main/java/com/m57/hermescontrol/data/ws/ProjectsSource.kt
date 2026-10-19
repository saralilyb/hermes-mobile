package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.data.model.ProjectsListResponse
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

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
    override suspend fun fetchProjects(connection: SessionLiveConnection): ProjectsResult {
        if (!isConnectionCurrent(connection)) return ProjectsResult.Offline
        val result =
            try {
                val raw = boundRpcRequest(connection, WsMethods.PROJECTS_LIST, emptyMap())
                val element = SessionLiveStatusDecoder.anyToJsonElement(raw)
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
