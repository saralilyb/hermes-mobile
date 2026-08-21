package com.m57.hermescontrol.ui.chat

import android.content.pm.PackageManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.ws.ConnectionStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for ChatScreen composable (TEST-09, issue #292).
 *
 * Validates that the chat screen renders correctly, its send button is
 * displayed, and the input field accepts text.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ChatScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        // Suppress the POST_NOTIFICATIONS runtime permission dialog that
        // the ChatScreen's LaunchedEffect(Unit) would otherwise trigger.
        // On the emulator this dialog pauses the activity and disposes the
        // compose hierarchy, making subsequent node lookups fail.
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), any())
        } returns PackageManager.PERMISSION_GRANTED
    }

    @Test
    fun chatScreen_renders_and_acceptsInput() {
        // Default ChatUiState has connectionStatus=DISCONNECTED which
        // disables the input field and hides the send button.  Override
        // so the interactive controls are active.
        val uiState =
            ChatUiState(
                connectionStatus = ConnectionStatus.CONNECTED,
                isSessionReady = true,
            )
        val mockViewModel = mockk<ChatViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns MutableStateFlow(uiState).asStateFlow()
        every { mockViewModel.streamingState } returns MutableStateFlow(StreamingState()).asStateFlow()

        composeTestRule.setContent {
            ChatScreen(
                onOpenDrawer = {},
                sessionId = null,
                viewModel = mockViewModel,
            )
        }

        // Verify the mic button is displayed initially and send button is not
        composeTestRule.onNodeWithTag("mic_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("send_button").assertDoesNotExist()

        // Verify entering text shows send button in input row
        // Mic stays visible in toolbar row (new two-row layout)
        composeTestRule.onNodeWithTag("chat_input").performTextInput("Hello Hermes")
        composeTestRule.onNodeWithTag("send_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mic_button").assertIsDisplayed()
    }

    @Test
    fun newSession_keepsDraftUntilSessionCreateCompletes() {
        val uiState =
            ChatUiState(
                connectionStatus = ConnectionStatus.CONNECTED,
                isSessionReady = false,
            )
        val mockViewModel = mockk<ChatViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns MutableStateFlow(uiState).asStateFlow()
        every { mockViewModel.streamingState } returns MutableStateFlow(StreamingState()).asStateFlow()

        composeTestRule.setContent {
            ChatScreen(
                onOpenDrawer = {},
                sessionId = null,
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithTag("chat_input").performTextInput("first prompt")
        composeTestRule.onNodeWithTag("send_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("chat_input").assertTextEquals("first prompt")
        verify(exactly = 0) { mockViewModel.sendMessage(any()) }
    }

    @Test
    fun taggedAndSyntheticRowsRenderAsTimelineEventsNeverUserBubbles() {
        val uiState =
            ChatUiState(
                connectionStatus = ConnectionStatus.CONNECTED,
                isSessionReady = true,
                messages =
                    listOf(
                        ChatMessage(
                            role = MessageRole.USER,
                            content = "backend marker",
                            displayKind = "future_kind",
                        ),
                        ChatMessage(
                            role = MessageRole.USER,
                            content =
                                "You've reached the maximum number of tool-calling iterations allowed. " +
                                    "Please provide a final response.",
                        ),
                    ),
            )
        val mockViewModel = mockk<ChatViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns MutableStateFlow(uiState).asStateFlow()
        every { mockViewModel.streamingState } returns MutableStateFlow(StreamingState()).asStateFlow()

        composeTestRule.setContent {
            ChatScreen(onOpenDrawer = {}, sessionId = "session", viewModel = mockViewModel)
        }

        composeTestRule.onAllNodesWithTag("system_timeline_event").assertCountEquals(2)
        composeTestRule.onNodeWithTag("chat_bubble_user").assertDoesNotExist()
    }

    @Test
    fun genuineUserRowStillRendersAsUserBubble() {
        val uiState =
            ChatUiState(
                connectionStatus = ConnectionStatus.CONNECTED,
                isSessionReady = true,
                messages = listOf(ChatMessage(role = MessageRole.USER, content = "hello")),
            )
        val mockViewModel = mockk<ChatViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns MutableStateFlow(uiState).asStateFlow()
        every { mockViewModel.streamingState } returns MutableStateFlow(StreamingState()).asStateFlow()

        composeTestRule.setContent {
            ChatScreen(onOpenDrawer = {}, sessionId = "session", viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithTag("chat_bubble_user").assertIsDisplayed()
        composeTestRule.onNodeWithTag("system_timeline_event").assertDoesNotExist()
    }

    @Test
    fun openingAttachmentPath_marksOnlyMatchingAttachment_andClearsWithParentState() {
        val path = "/gateway/report.pdf"
        val state =
            MutableStateFlow(
                ChatUiState(
                    connectionStatus = ConnectionStatus.CONNECTED,
                    isSessionReady = true,
                    messages =
                        listOf(
                            ChatMessage(
                                role = MessageRole.ASSISTANT,
                                content = "files",
                                attachments =
                                    listOf(
                                        Attachment(path, "report.pdf", "application/pdf", gatewayPath = path),
                                        Attachment(
                                            "/gateway/other.txt",
                                            "other.txt",
                                            "text/plain",
                                            gatewayPath = "/gateway/other.txt",
                                        ),
                                    ),
                            ),
                        ),
                    openingAttachmentPath = path,
                ),
            )
        val mockViewModel = mockk<ChatViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns state.asStateFlow()
        every { mockViewModel.streamingState } returns MutableStateFlow(StreamingState()).asStateFlow()

        composeTestRule.setContent {
            ChatScreen(onOpenDrawer = {}, sessionId = "session", viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithTag("attachment_opening_$path").assertIsDisplayed()
        composeTestRule.onNodeWithTag("attachment_opening_/gateway/other.txt").assertDoesNotExist()
        state.value = state.value.copy(openingAttachmentPath = null)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("attachment_opening_$path").assertDoesNotExist()
    }

    @Test
    fun fallbackOnlyContextMeter_opensOccupancyExplanation() {
        val uiState =
            ChatUiState(
                connectionStatus = ConnectionStatus.CONNECTED,
                currentSessionModel = "openai-codex/gpt-5.6-sol",
                modelContextLength = 272_000L,
                modelContextLengthModel = "openai-codex/gpt-5.6-sol",
            )
        val mockViewModel = mockk<ChatViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns MutableStateFlow(uiState).asStateFlow()
        every { mockViewModel.streamingState } returns MutableStateFlow(StreamingState()).asStateFlow()

        composeTestRule.setContent {
            ChatScreen(
                onOpenDrawer = {},
                sessionId = null,
                viewModel = mockViewModel,
            )
        }

        composeTestRule
            .onNodeWithText("— / 272k context")
            .assertHasClickAction()
            .performClick()
        verify { mockViewModel.openContextDetail() }
    }
}
