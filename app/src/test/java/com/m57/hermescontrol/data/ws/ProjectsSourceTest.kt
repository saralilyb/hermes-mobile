package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.ProjectFolder
import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsSourceTest {
    private val owner = SessionLiveConnection("local-uuid", 7)

    @Test
    fun `empty result is distinct from offline unsupported and malformed`() =
        runTest {
            var calls = 0
            var current = true
            var raw: Any? = mapOf("projects" to emptyList<Any>())
            val source =
                HermesProjectsSource(
                    isConnectionCurrent = { current },
                    boundRpcRequest = { connection, method, params ->
                        assertEquals(owner, connection)
                        assertEquals("projects.list", method)
                        assertTrue(params.isEmpty())
                        calls++
                        raw
                    },
                )
            assertEquals(ProjectsResult.Ready(emptyList()), source.fetchProjects(owner))
            raw = null
            assertEquals(ProjectsResult.Unsupported, source.fetchProjects(owner))
            raw = mapOf("projects" to "bad")
            assertEquals(ProjectsResult.Failed, source.fetchProjects(owner))
            current = false
            assertEquals(ProjectsResult.Offline, source.fetchProjects(owner))
            assertEquals(3, calls)
        }

    @Test
    fun `method missing is unsupported and cancellation propagates`() =
        runTest {
            val missing =
                HermesProjectsSource({ true }) { _, _, _ ->
                    throw HermesWsClient.HermesRpcException("missing", -32601)
                }
            assertEquals(ProjectsResult.Unsupported, missing.fetchProjects(owner))
            val cancelled = HermesProjectsSource({ true }) { _, _, _ -> throw CancellationException() }
            assertTrue(runCatching { cancelled.fetchProjects(owner) }.exceptionOrNull() is CancellationException)
        }

    private suspend fun fetchFrame(project: String): ProjectsResult {
        val frame = """{"jsonrpc":"2.0","id":"projects-1","result":{"projects":[$project]}}"""
        val response = OkHttpProvider.json.decodeFromString<JsonRpcResponse>(frame)
        val event = EventParser.parse(response, frame) as WsEvent.RpcResult
        return HermesProjectsSource({ true }) { _, _, _ -> event.result }.fetchProjects(owner)
    }

    @Test
    fun `gateway RPC frames preserve timestamps and native or legacy booleans`() =
        runTest {
            for ((archived, primary, expected) in listOf(
                Triple("false", "true", false),
                Triple("true", "false", true),
                Triple("0", "1", false),
                Triple("1", "0", true),
            )) {
                val project =
                    """
                    {"id":"p","name":"App","created_at":1787590315,"archived":$archived,
                    "folders":[{"path":"/work/app","added_at":1787590316,"is_primary":$primary}]}
                    """.trimIndent()
                assertEquals(
                    ProjectsResult.Ready(
                        listOf(
                            ProjectInfo(
                                "p",
                                name = "App",
                                createdAt = 1787590315,
                                archived = expected,
                                folders =
                                    listOf(
                                        ProjectFolder("/work/app", isPrimary = !expected, addedAt = 1787590316),
                                    ),
                            ),
                        ),
                    ),
                    fetchFrame(project),
                )
            }
        }

    @Test
    fun `integer fields accept exact doubles but reject fractions nonfinite and overflow`() =
        runTest {
            for (value in listOf("0", "-1", "1787590315.0", "-9223372036854775808", "9223372036854774784")) {
                val expected = value.toDouble().toLong()
                val result =
                    fetchFrame("""{"id":"p","created_at":$value,"folders":[{"path":"/app","added_at":$value}]}""")
                assertTrue("Expected exact integer $value, got $result", result is ProjectsResult.Ready)
                val project = (result as ProjectsResult.Ready).projects.single()
                assertEquals(expected, project.createdAt)
                assertEquals(expected, project.folders.single().addedAt)
            }
            for (value in listOf("0.5", "-0.5", "9223372036854775808", "-9223372036854777856", "1e309", "-1e309")) {
                assertEquals(ProjectsResult.Failed, fetchFrame("""{"id":"p","created_at":$value}"""))
                assertEquals(
                    ProjectsResult.Failed,
                    fetchFrame("""{"id":"p","folders":[{"path":"/app","added_at":$value}]}"""),
                )
            }
            for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                val source =
                    HermesProjectsSource({ true }) { _, _, _ ->
                        mapOf("projects" to listOf(mapOf("id" to "p", "created_at" to value)))
                    }
                assertEquals(ProjectsResult.Failed, source.fetchProjects(owner))
            }
            for (value in listOf("0.5", "-0.5", "2", "1e309")) {
                assertEquals(ProjectsResult.Failed, fetchFrame("""{"id":"p","archived":$value}"""))
                assertEquals(
                    ProjectsResult.Failed,
                    fetchFrame("""{"id":"p","folders":[{"path":"/app","is_primary":$value}]}"""),
                )
            }
            assertEquals(ProjectsResult.Ready(listOf(ProjectInfo("p"))), fetchFrame("""{"id":"p"}"""))
        }

    @Test
    fun `owner fence rejects stale RPC errors after ABA`() =
        runTest {
            for (code in listOf(-32601, -32603)) {
                var currentOwner = owner
                val pending = CompletableDeferred<Any?>()
                val source = HermesProjectsSource({ it == currentOwner }) { _, _, _ -> pending.await() }
                var result: ProjectsResult? = null
                val job = launch { result = source.fetchProjects(owner) }
                testScheduler.runCurrent()
                currentOwner = owner.copy(generation = 9)
                pending.completeExceptionally(HermesWsClient.HermesRpcException("old error", code))
                job.join()
                assertEquals(ProjectsResult.Offline, result)
            }
        }

    @Test
    fun `owner fence rejects late success and errors after ABA`() =
        runTest {
            var currentOwner = owner
            val pending = CompletableDeferred<Any?>()
            val source = HermesProjectsSource({ it == currentOwner }) { _, _, _ -> pending.await() }
            var result: ProjectsResult? = null
            val job = launch { result = source.fetchProjects(owner) }
            testScheduler.runCurrent()
            currentOwner = owner.copy(generation = 9)
            pending.complete(mapOf("projects" to listOf(mapOf("id" to "old"))))
            job.join()
            assertEquals(ProjectsResult.Offline, result)
        }
}
