package com.m57.hermescontrol.ui.cron

import com.m57.hermescontrol.data.model.CronJob
import com.m57.hermescontrol.data.model.UpdateCronJobRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class CronJobsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<HermesApiService>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns api
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `monitor source is mutually exclusive and no-agent clears it`() {
        val viewModel = CronJobsViewModel(ioDispatcher = dispatcher)
        viewModel.openNewJobDialog()
        viewModel.setMonitorMode("script")
        viewModel.updateEditorField("monitor_script", "check.sh")
        viewModel.setMonitorMode("url")
        viewModel.updateEditorField("monitor_url", "https://example.com/status")

        assertEquals("", viewModel.uiState.value.editorState.monitor_script)
        assertEquals("https://example.com/status", viewModel.uiState.value.editorState.monitor_url)

        viewModel.toggleNoAgent()
        assertTrue(viewModel.uiState.value.editorState.no_agent)
        assertEquals("off", viewModel.uiState.value.editorState.monitorMode)
        assertEquals("", viewModel.uiState.value.editorState.monitor_url)
    }

    @Test
    fun `failed monitor follow-up keeps created job and reports partial failure`() =
        runTest(dispatcher) {
            val viewModel = CronJobsViewModel(ioDispatcher = dispatcher)
            viewModel.openNewJobDialog()
            viewModel.updateEditorField("name", "Watch")
            viewModel.updateEditorField("schedule", "every 10m")
            viewModel.setMonitorMode("script")
            viewModel.updateEditorField("monitor_script", "check.sh")
            coEvery { api.createCronJob(any()) } returns Response.success(CronJob(id = "job-1", name = "Watch"))
            coEvery { api.updateCronJob("job-1", any()) } returns
                Response.error(400, "monitor rejected".toResponseBody())

            viewModel.saveEditor()
            advanceUntilIdle()

            coVerify(exactly = 0) { api.deleteCronJob(any()) }
            assertTrue(viewModel.uiState.value.editorState.isOpen)
            assertFalse(viewModel.uiState.value.editorState.isNew)
            assertEquals("job-1", viewModel.uiState.value.editorState.jobId)
            assertTrue(viewModel.uiState.value.editorState.toastMessage.orEmpty().contains("created"))
            val update = slot<UpdateCronJobRequest>()
            coVerify { api.updateCronJob("job-1", capture(update)) }
            assertEquals(JsonPrimitive("check.sh"), update.captured.updates["monitor_script"])
        }

    @Test
    fun `delete requires explicit confirmation`() =
        runTest(dispatcher) {
            val viewModel = CronJobsViewModel(ioDispatcher = dispatcher)
            val job = CronJob(id = "job-delete", name = "Delete me")

            viewModel.requestDeleteJob(job)
            assertEquals(job, viewModel.uiState.value.deleteTarget)
            coVerify(exactly = 0) { api.deleteCronJob(any()) }

            viewModel.dismissDeleteDialog()
            assertNull(viewModel.uiState.value.deleteTarget)
            viewModel.requestDeleteJob(job)
            viewModel.confirmDeleteJob()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.deleteTarget)
            coVerify(exactly = 1) { api.deleteCronJob("job-delete") }
        }
}
