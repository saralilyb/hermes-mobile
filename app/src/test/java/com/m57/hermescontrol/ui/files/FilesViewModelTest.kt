package com.m57.hermescontrol.ui.files

import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ManagedFileEntry
import com.m57.hermescontrol.data.remote.GatewayFile
import com.m57.hermescontrol.data.remote.GatewayFileClient
import com.m57.hermescontrol.data.remote.GatewayFileResult
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(GatewayFileClient)
    }

    @After
    fun tearDown() {
        unmockkObject(GatewayFileClient)
        Dispatchers.resetMain()
    }

    @Test
    fun `createDir validation emits localized message resource`() {
        val viewModel = FilesViewModel(ioDispatcher = dispatcher)

        viewModel.openCreateDir()
        viewModel.createDir()

        assertEquals(
            R.string.files_error_folder_name_required,
            viewModel.uiState.value.toastMessage?.resourceId,
        )
    }

    @Test
    fun `downloadFile returns authenticated gateway bytes and clears opening state`() =
        runTest(dispatcher) {
            val bytes = byteArrayOf(1, 2, 3)
            val cacheDir =
                java.io.File(
                    System.getProperty("java.io.tmpdir"),
                    "files-${System.nanoTime()}",
                ).apply { mkdirs() }
            coEvery { GatewayFileClient.fetch("/tmp/image.png", any()) } returns
                GatewayFileResult.Success(
                    GatewayFile(
                        name = "image.png",
                        mimeType = "image/png",
                        cacheFile = java.io.File(cacheDir, "image.png").apply { writeBytes(bytes) },
                    ),
                )
            val viewModel = FilesViewModel(ioDispatcher = dispatcher)
            var result: NetworkResult<DownloadedFile>? = null

            viewModel.downloadFile(
                ManagedFileEntry(name = "image.png", path = "/tmp/image.png"),
                cacheDir,
            ) { result = it }
            advanceUntilIdle()

            val downloaded = (result as NetworkResult.Success).data
            assertEquals("image.png", downloaded.name)
            assertEquals("image/png", downloaded.mimeType)
            assertArrayEquals(bytes, downloaded.cacheFile.readBytes())
            assertNull(viewModel.uiState.value.openingPath)
        }

    @Test
    fun `downloadFile maps device size cap to HTTP 413`() =
        runTest(dispatcher) {
            val cacheDir =
                java.io.File(
                    System.getProperty("java.io.tmpdir"),
                    "files-${System.nanoTime()}",
                ).apply { mkdirs() }
            coEvery { GatewayFileClient.fetch("/tmp/large.bin", any()) } returns GatewayFileResult.TooLarge
            val viewModel = FilesViewModel(ioDispatcher = dispatcher)
            var result: NetworkResult<DownloadedFile>? = null

            viewModel.downloadFile(
                ManagedFileEntry(name = "large.bin", path = "/tmp/large.bin"),
                cacheDir,
            ) { result = it }
            advanceUntilIdle()

            val error = (result as NetworkResult.Failure).error as NetworkError.Http
            assertEquals(413, error.code)
            assertNull(viewModel.uiState.value.openingPath)
        }
}
