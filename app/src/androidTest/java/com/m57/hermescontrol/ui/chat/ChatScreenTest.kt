package com.m57.hermescontrol.ui.chat

import android.content.pm.PackageManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.PrivilegedRequestBinding
import com.m57.hermescontrol.data.ws.ServerRequestBinding
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
    fun approvalSession_parentRendersOfferedActionAndRoutesExactBinding() {
        val binding = PrivilegedRequestBinding("approval-1", "session", "profile-a", 7)
        val message =
            ChatMessage(
                id = "approval-card",
                role = MessageRole.SYSTEM,
                content = "Approval requested",
                approvalInfo = ApprovalInfo("command", null, null, binding, offersSession = true),
            )
        val state =
            MutableStateFlow(
                ChatUiState(
                    connectionStatus = ConnectionStatus.CONNECTED,
                    isSessionReady = true,
                    messages = listOf(message),
                ),
            )
        val viewModel = mockk<ChatViewModel>(relaxed = true)
        every { viewModel.uiState } returns state.asStateFlow()
        every { viewModel.streamingState } returns MutableStateFlow(StreamingState()).asStateFlow()
        composeTestRule.setContent { ChatScreen(sessionId = "session", viewModel = viewModel) }
        composeTestRule.onNodeWithText(
            "Allow for this session",
        ).assertIsDisplayed().assertHasClickAction().performClick()
        verify(exactly = 1) { viewModel.respondToApproval("approval-card", binding, "session") }
        verify(exactly = 0) { viewModel.respondToApproval(any(), any(), "always") }
        state.value =
            state.value.copy(
                messages =
                    listOf(
                        message.copy(
                            approvalInfo = message.approvalInfo!!.copy(offersSession = false),
                        ),
                    ),
            )
        composeTestRule.onNodeWithTag("allow_session_button").assertDoesNotExist()
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
    fun attachmentOnlyInput_showsSendButton_andSubmitsThroughParent() {
        val uiState =
            ChatUiState(
                connectionStatus = ConnectionStatus.CONNECTED,
                isSessionReady = true,
                pendingAttachments =
                    listOf(
                        Attachment(
                            uri = "content://test/photo.jpg",
                            name = "photo.jpg",
                            mimeType = "image/jpeg",
                        ),
                    ),
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

        composeTestRule.onNodeWithTag("send_button").assertIsDisplayed().performClick()
        verify { mockViewModel.sendMessage("") }
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

    @Test
    fun vaultCode_parentMasksValueAndRoutesConfirmAndCancel() {
        val state = promptState(vaultPrompt("vault.code", "code-1", prompt = "Enter the emailed code"))
        val viewModel = promptViewModel(state)
        composeTestRule.setContent { ChatScreen(sessionId = "session", viewModel = viewModel) }

        composeTestRule.onNodeWithText("Verification code required").assertIsDisplayed()
        composeTestRule.onNodeWithText("Enter the emailed code").assertIsDisplayed()
        val codeInput = composeTestRule.onNodeWithTag("vault_secret_input")
        codeInput.performTextInput("123456")
        codeInput.assert(rawInputEquals("123456")).assert(maskedEditableText())
        composeTestRule.onNodeWithTag("vault_send_button").performClick()
        verify { viewModel.respondToVault("123456") }

        composeTestRule.onNodeWithTag("vault_cancel_button").performClick()
        verify { viewModel.cancelVault() }
    }

    @Test
    fun vaultUnlock_parentDisablesControlsWhileSubmittingAndDismissIsIncidental() {
        val state = promptState(vaultPrompt("vault.unlock_prompt", "unlock-1", isSubmitting = true))
        val viewModel = promptViewModel(state)
        composeTestRule.setContent { ChatScreen(sessionId = "session", viewModel = viewModel) }

        composeTestRule.onNodeWithText("Unlock vault").assertIsDisplayed()
        composeTestRule.onNodeWithTag("vault_secret_input").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("vault_send_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("vault_cancel_button").assertIsNotEnabled()
        pressBack()
        verify(exactly = 0) { viewModel.cancelVault() }
        verify(exactly = 0) { viewModel.dismissVault() }
    }

    @Test
    fun vaultSaveLogin_parentRoutesBothFieldsAndMasksPassword() {
        val state =
            promptState(
                vaultPrompt(
                    "vault.save_login",
                    "save-1",
                    identifier = "alice@example.com",
                    requestedOrigin = "https://accounts.example.com:8443",
                    title = "Save this password for a different site",
                ),
            )
        val viewModel = promptViewModel(state)
        composeTestRule.setContent { ChatScreen(sessionId = "session", viewModel = viewModel) }

        composeTestRule.onNodeWithText("Save this password for a different site").assertIsDisplayed()
        composeTestRule.onNodeWithText("Requested site: https://accounts.example.com:8443").assertIsDisplayed()
        composeTestRule.onNodeWithTag("vault_identifier_input").assertTextContains("alice@example.com")
        val passwordInput = composeTestRule.onNodeWithTag("vault_secret_input")
        passwordInput.performTextInput("password")
        passwordInput.assert(rawInputEquals("password")).assert(maskedEditableText())
        composeTestRule.onNodeWithTag("vault_send_button").performClick()
        verify { viewModel.respondToVaultLogin("alice@example.com", "password") }
    }

    @Test
    fun vaultSaveLogin_parentRejectsMissingOriginWithoutCredentialEntry() {
        val state = promptState(vaultPrompt("vault.save_login", "save-missing"))
        val viewModel = promptViewModel(state)
        composeTestRule.setContent { ChatScreen(sessionId = "session", viewModel = viewModel) }

        composeTestRule.onNodeWithText("Requested site: Missing origin").assertIsDisplayed()
        composeTestRule.onNodeWithText("This request has no valid web origin. Cancel it without entering credentials.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("vault_identifier_input").assertDoesNotExist()
        composeTestRule.onNodeWithTag("vault_secret_input").assertDoesNotExist()
        composeTestRule.onNodeWithTag("vault_send_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("vault_cancel_button").assertIsEnabled()
    }

    @Test
    fun vaultParent_fullBindingReplacementClearsButDuplicateRetainsSecret() {
        val first = vaultPrompt("vault.code", "request-1")
        val state = promptState(first)
        val viewModel = promptViewModel(state)
        composeTestRule.setContent { ChatScreen(sessionId = "session", viewModel = viewModel) }
        val input = composeTestRule.onNodeWithTag("vault_secret_input")
        input.performTextInput("retained")

        state.value = state.value.copy(vaultPrompt = first.copy(prompt = "duplicate update"))
        composeTestRule.waitForIdle()
        input.assert(rawInputEquals("retained"))

        state.value = state.value.copy(vaultPrompt = vaultPrompt("vault.code", "request-2"))
        composeTestRule.waitForIdle()
        input.assert(rawInputEquals(""))
    }

    @Test
    fun sudoAndSecret_parentRouteMaskedConfirmAndCancel() {
        val binding = privilegedBinding("sudo-1")
        val state = promptState().also { it.value = it.value.copy(sudoPrompt = SudoPromptUi(binding)) }
        val viewModel = promptViewModel(state)
        composeTestRule.setContent { ChatScreen(sessionId = "session", viewModel = viewModel) }
        val sudoInput = composeTestRule.onNodeWithTag("sudo_password_input")
        sudoInput.performTextInput("sudo-password")
        sudoInput.assert(rawInputEquals("sudo-password")).assert(maskedEditableText())
        composeTestRule.onNodeWithTag("sudo_send_button").performClick()
        verify { viewModel.respondToSudo("sudo-password") }
        composeTestRule.onNodeWithTag("sudo_cancel_button").performClick()
        verify { viewModel.cancelSudo() }

        state.value = state.value.copy(sudoPrompt = null, secretPrompt = SecretPromptUi(privilegedBinding("secret-1")))
        composeTestRule.waitForIdle()
        val secretInput = composeTestRule.onNodeWithTag("secret_value_input")
        secretInput.performTextInput("api-token")
        secretInput.assert(rawInputEquals("api-token")).assert(maskedEditableText())
        composeTestRule.onNodeWithTag("secret_send_button").performClick()
        verify { viewModel.respondToSecret("api-token") }
        composeTestRule.onNodeWithTag("secret_cancel_button").performClick()
        verify { viewModel.cancelSecret() }
    }

    private fun promptState(prompt: VaultPromptUi? = null) =
        MutableStateFlow(
            ChatUiState(
                connectionStatus = ConnectionStatus.CONNECTED,
                isSessionReady = true,
                currentSessionId = "session",
                vaultPrompt = prompt,
            ),
        )

    private fun promptViewModel(state: MutableStateFlow<ChatUiState>) =
        mockk<ChatViewModel>(relaxed = true).also { viewModel ->
            every { viewModel.uiState } returns state.asStateFlow()
            every { viewModel.streamingState } returns MutableStateFlow(StreamingState()).asStateFlow()
        }

    private fun vaultPrompt(
        method: String,
        requestId: String,
        prompt: String? = null,
        identifier: String? = null,
        requestedOrigin: String? = null,
        title: String? = null,
        isSubmitting: Boolean = false,
    ) = VaultPromptUi(
        binding = ServerRequestBinding(requestId, "session", "profile", 1),
        method = method,
        prompt = prompt,
        identifier = identifier,
        requestedOrigin = requestedOrigin,
        title = title,
        isSubmitting = isSubmitting,
    )

    private fun privilegedBinding(requestId: String) = PrivilegedRequestBinding(requestId, "session", "profile", 1)

    private fun rawInputEquals(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString(value))

    private fun maskedEditableText() =
        SemanticsMatcher("editable text is masked") { node ->
            val editable =
                if (node.config.contains(SemanticsProperties.EditableText)) {
                    node.config[SemanticsProperties.EditableText].text
                } else {
                    ""
                }
            editable.isNotEmpty() && editable.all { it != 'p' && it != '1' && it != 'a' && it != 's' }
        }
}
