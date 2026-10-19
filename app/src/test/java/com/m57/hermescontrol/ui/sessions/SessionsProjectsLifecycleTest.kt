package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.model.LiveSessionSnapshot
import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.ProjectsResult
import com.m57.hermescontrol.data.ws.ProjectsSource
import com.m57.hermescontrol.data.ws.SessionLiveConnection
import com.m57.hermescontrol.data.ws.SessionLiveStatusSource
import com.m57.hermescontrol.data.ws.SourcedSessionLiveEvent
import com.m57.hermescontrol.data.ws.WsEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsProjectsLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    private val live = Live()
    private val calls = mutableListOf<SessionLiveConnection>()
    private var answer: suspend () -> ProjectsResult = { ProjectsResult.Ready(listOf(ProjectInfo("good"))) }
    private val source =
        object : ProjectsSource {
            override suspend fun fetchProjects(connection: SessionLiveConnection): ProjectsResult {
                calls.add(connection)
                return answer()
            }
        }
    private lateinit var vm: SessionsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        vm =
            SessionsViewModel(
                object : SessionPinStore {
                    override fun load() = emptyList<String>()

                    override fun save(pinIds: List<String>) = Unit
                },
                dispatcher,
                live,
                source,
            )
        vm.startLiveStatusTracking()
        dispatcher.scheduler.runCurrent()
    }

    @After
    fun cleanup() {
        vm.stopLiveStatusTracking()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun `visibility restart restores project controls only for same socket owner`() {
        vm.selectProjectFilter("project:good")
        vm.toggleProjectGroup("project:good")
        vm.stopLiveStatusTracking()
        dispatcher.scheduler.runCurrent()
        vm.startLiveStatusTracking()
        dispatcher.scheduler.runCurrent()
        assertEquals("project:good", vm.uiState.value.projectFilter)
        assertEquals(setOf("project:good"), vm.uiState.value.collapsedProjectGroups)
        vm.stopLiveStatusTracking()
        dispatcher.scheduler.runCurrent()
        live.generation++
        vm.startLiveStatusTracking()
        dispatcher.scheduler.runCurrent()
        assertEquals(null, vm.uiState.value.projectFilter)
        assertTrue(vm.uiState.value.collapsedProjectGroups.isEmpty())
    }

    @Test
    fun `failed same owner refresh keeps last good metadata`() {
        answer = { ProjectsResult.Failed }
        live.events.tryEmit(SourcedSessionLiveEvent(WsEvent.ProjectsChanged, "a", 1))
        dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(250)
        dispatcher.scheduler.runCurrent()
        assertEquals("good", vm.uiState.value.projects.single().id)
        assertEquals(ProjectsResult.Failed, vm.uiState.value.projectsStatus)
    }

    @Test
    fun `burst invalidations coalesce and stale owner never dispatches`() {
        assertEquals(1, calls.size)
        repeat(10) { live.events.tryEmit(SourcedSessionLiveEvent(WsEvent.ProjectsChanged, "a", 1)) }
        dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(249)
        assertEquals(1, calls.size)
        dispatcher.scheduler.advanceTimeBy(1)
        dispatcher.scheduler.runCurrent()
        assertEquals(2, calls.size)
        live.events.tryEmit(SourcedSessionLiveEvent(WsEvent.ProjectsChanged, "b", 1))
        dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(300)
        dispatcher.scheduler.runCurrent()
        assertEquals(2, calls.size)
    }

    @Test
    fun `offline preserves same owner cache then reconnect discards it`() {
        live.status.value = ConnectionStatus.DISCONNECTED
        dispatcher.scheduler.runCurrent()
        assertEquals(ProjectsResult.Offline, vm.uiState.value.projectsStatus)
        assertEquals("good", vm.uiState.value.projects.single().id)
        live.generation = 2
        answer = { ProjectsResult.Unsupported }
        live.status.value = ConnectionStatus.CONNECTED
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.uiState.value.projects.isEmpty())
        assertEquals(ProjectsResult.Unsupported, vm.uiState.value.projectsStatus)
    }

    @Test
    fun `late success and stale errors cannot replace current owner after ABA`() {
        for (stale in listOf(ProjectsResult.Ready(listOf(ProjectInfo("stale"))), ProjectsResult.Failed)) {
            lateinit var continuation: Continuation<ProjectsResult>
            answer = { suspendCoroutine { continuation = it } }
            live.events.tryEmit(SourcedSessionLiveEvent(WsEvent.ProjectsChanged, "a", live.generation))
            dispatcher.scheduler.runCurrent()
            dispatcher.scheduler.advanceTimeBy(250)
            dispatcher.scheduler.runCurrent()
            // Isolate the generation fence from cancellation to make its mutation load-bearing.
            val field = SessionsViewModel::class.java.getDeclaredField("projectsJob").apply { isAccessible = true }
            field.set(vm, Job())
            live.profiles.value = "b"
            live.generation++
            answer = { ProjectsResult.Ready(listOf(ProjectInfo("current"))) }
            dispatcher.scheduler.runCurrent()
            live.profiles.value = "a"
            live.generation++
            dispatcher.scheduler.runCurrent()
            continuation.resume(stale)
            dispatcher.scheduler.runCurrent()
            assertEquals("current", vm.uiState.value.projects.single().id)
            assertTrue(vm.uiState.value.projectsStatus is ProjectsResult.Ready)
        }
    }

    @Test
    fun `stop tracking cancels project request`() {
        val pending = CompletableDeferred<ProjectsResult>()
        var cancelled = false
        answer = {
            try {
                pending.await()
            } finally {
                cancelled = true
            }
        }
        live.events.tryEmit(SourcedSessionLiveEvent(WsEvent.ProjectsChanged, "a", 1))
        dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(250)
        dispatcher.scheduler.runCurrent()
        vm.stopLiveStatusTracking()
        dispatcher.scheduler.runCurrent()
        assertTrue(cancelled)
    }

    private class Live : SessionLiveStatusSource {
        val profiles = MutableStateFlow("a")
        val status = MutableStateFlow(ConnectionStatus.CONNECTED)
        val events = MutableSharedFlow<SourcedSessionLiveEvent>(extraBufferCapacity = 32)
        var generation = 1
        override val selectedProfileIds = profiles
        override val connectionStatus = status
        override val sourcedEvents = events

        override fun currentConnection(profileId: String) = SessionLiveConnection(profileId, generation)

        override suspend fun fetchActiveSessionsSnapshot(connection: SessionLiveConnection): LiveSessionSnapshot? = null
    }
}
