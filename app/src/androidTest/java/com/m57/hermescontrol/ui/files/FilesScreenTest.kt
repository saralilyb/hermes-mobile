package com.m57.hermescontrol.ui.files

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.data.model.ManagedFileEntry
import com.m57.hermescontrol.data.remote.NetworkResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FilesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun gatewayFileClick_reachesParentOpenAndLaunchSeam() {
        val entry =
            ManagedFileEntry(name = "gateway-image.png", path = "/tmp/gateway-image.png", mimeType = "image/png")
        val viewModel = mockk<FilesViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(FilesUiState(entries = listOf(entry))).asStateFlow()
        val downloaded = DownloadedFile(entry.name, "image/png", File("/tmp/cached-image.png"))
        every { viewModel.downloadFile(entry, any(), any()) } answers {
            thirdArg<(NetworkResult<DownloadedFile>) -> Unit>().invoke(NetworkResult.Success(downloaded))
        }
        val launched = mutableListOf<DownloadedFile>()

        composeRule.setContent {
            FilesScreen(viewModel = viewModel, launchDownloadedFile = launched::add)
        }

        composeRule.onNodeWithText("gateway-image.png").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            verify(exactly = 1) { viewModel.downloadFile(entry, any(), any()) }
            assertEquals(listOf(downloaded), launched)
        }
    }
}
