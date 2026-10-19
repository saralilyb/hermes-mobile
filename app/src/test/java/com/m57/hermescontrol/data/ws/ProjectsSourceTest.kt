package com.m57.hermescontrol.data.ws

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
