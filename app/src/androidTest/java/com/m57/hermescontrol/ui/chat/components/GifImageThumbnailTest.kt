package com.m57.hermescontrol.ui.chat.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.m57.hermescontrol.data.remote.GatewayFileResult
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

class GifImageThumbnailTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fileBackedGifRendersImageAndPlayControl() {
        val file =
            kotlin.io.path.createTempFile(suffix = ".gif").toFile().apply {
                writeBytes(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61))
            }
        composeRule.setContent {
            MaterialTheme {
                GifImageThumbnail(file, contentDescription = "file gif", isGif = true, onClick = {})
            }
        }

        composeRule.onNodeWithContentDescription("file gif").assertExists()
        composeRule.onNodeWithContentDescription("Pause GIF").assertExists()
    }

    @Test
    fun gatewayImageShowsLoadingUntilFetchCompletesThenKeepsFallbackNode() {
        val gate = CompletableDeferred<GatewayFileResult>()
        composeRule.setContent {
            MaterialTheme {
                GifImageThumbnail(
                    model = "fallback",
                    gatewayPath = "/remote.gif",
                    contentDescription = "gateway gif",
                    isGif = false,
                    onClick = {},
                    gatewayFetcher = { _, _ -> gate.await() },
                )
            }
        }

        composeRule.onNodeWithTag("gateway-media-loading").assertExists()
        gate.complete(GatewayFileResult.NotFound)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("gateway gif").assertExists()
    }
}
