package com.m57.hermescontrol.ui.skills

import android.app.Application
import com.m57.hermescontrol.data.model.SkillHubPreviewResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class HubPreviewRaceTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: HermesApiService
    private lateinit var vm: SkillsViewModel
    private val pending = mutableListOf<Continuation<Response<SkillHubPreviewResponse>>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(ApiClient)
        api = mockk()
        every { ApiClient.hermesApi } returns api
        // Deliberately not cancellable: late callbacks must exercise the generation fence,
        // not merely disappear when the old request job is cancelled.
        coEvery { api.previewHubSkill(any()) } coAnswers {
            suspendCoroutine { pending.add(it) }
        }
        vm = SkillsViewModel(mockk<Application>(relaxed = true), dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun replacementRejectsLateSuccess() {
        start("first")
        start("second")
        complete(1, "second", "new")
        complete(0, "first", "old")
        assertEquals("second", vm.uiState.value.hubPreviewIdentifier)
        assertEquals("new", vm.uiState.value.hubPreviewContent)
    }

    @Test
    fun closeRejectsLateSuccessAndResetsAllHubFields() {
        start("first")
        vm.clearHubPreview()
        complete(0, "first", "old")
        assertCleared()
    }

    @Test
    fun sameIdentifierReopenRejectsOldCompletionWhileNewRequestIsLoading() {
        start("same")
        vm.clearHubPreview()
        start("same")
        complete(0, "same", "old")
        assertEquals("same", vm.uiState.value.hubPreviewIdentifier)
        assertNull(vm.uiState.value.hubPreviewContent)
        assertTrue(vm.uiState.value.isHubPreviewing)
        complete(1, "same", "new")
        assertEquals("new", vm.uiState.value.hubPreviewContent)
        assertFalse(vm.uiState.value.isHubPreviewing)
    }

    @Test
    fun obsoleteFailureCannotReplaceSuccessfulReopen() {
        start("same")
        start("same")
        complete(1, "same", "new")
        pending[0].resumeWithException(IllegalStateException("obsolete"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("new", vm.uiState.value.hubPreviewContent)
        assertNull(vm.uiState.value.hubPreviewError)
        assertFalse(vm.uiState.value.isHubPreviewing)
    }

    @Test
    fun blankIdentifierClearsAndInvalidatesWithoutRequest() {
        start("first")
        vm.previewHubSkill("  ")
        complete(0, "first", "old")
        assertCleared()
        coVerify(exactly = 1) { api.previewHubSkill(any()) }
    }

    @Test
    fun normalCancellationAlsoLeavesClosedPreviewCleared() {
        vm.previewHubSkill("first")
        dispatcher.scheduler.runCurrent()
        vm.clearHubPreview()
        complete(0, "first", "old")
        assertCleared()
    }

    @Test
    fun currentFailureIsVisibleAndClearRemovesIt() {
        start("first")
        pending[0].resumeWithException(IllegalStateException("current"))
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.isHubPreviewing)
        assertTrue(vm.uiState.value.hubPreviewError != null)
        vm.clearHubPreview()
        assertCleared()
    }

    private fun start(identifier: String) {
        vm.previewHubSkill(identifier)
        dispatcher.scheduler.runCurrent()
        // withContext also enforces prompt cancellation even for a resistant API stub.
        // Suppress only cancellation through the stored handle, leaving the real job
        // running, to isolate generation identity independently of that second fence.
        val handle = SkillsViewModel::class.java.getDeclaredField("hubPreviewJob")
        handle.isAccessible = true
        handle.set(vm, mockk<kotlinx.coroutines.Job>(relaxed = true))
    }

    private fun complete(
        index: Int,
        identifier: String,
        content: String,
    ) {
        pending[index].resume(Response.success(SkillHubPreviewResponse(identifier, content)))
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun assertCleared() {
        assertNull(vm.uiState.value.hubPreviewIdentifier)
        assertNull(vm.uiState.value.hubPreviewContent)
        assertNull(vm.uiState.value.hubPreviewError)
        assertFalse(vm.uiState.value.isHubPreviewing)
    }
}
