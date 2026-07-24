package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatStreamingControllerTest {
    @Test
    fun messageToken_copiesReasoningIntoExistingStreamingMessage() =
        runTest {
            val uiState = MutableStateFlow(ChatUiState())
            val streamingState =
                MutableStateFlow(
                    StreamingState(
                        streamingMessage =
                            ChatMessage(
                                role = MessageRole.ASSISTANT,
                                content = "",
                                isStreaming = true,
                            ),
                        reasoningText = "Preserved reasoning",
                    ),
                )
            val controller = controller(this, uiState, streamingState)

            controller.handleMessageToken(WsEvent.MessageToken("Answer", "session"))

            assertEquals(
                "Preserved reasoning",
                streamingState.value.streamingMessage?.reasoningText,
            )
        }

    @Test
    fun messageToken_copiesReasoningIntoFallbackStreamingMessage() =
        runTest {
            val uiState = MutableStateFlow(ChatUiState())
            val streamingState =
                MutableStateFlow(
                    StreamingState(reasoningText = "Preserved reasoning"),
                )
            val controller = controller(this, uiState, streamingState)

            controller.handleMessageToken(WsEvent.MessageToken("Answer", "session"))

            assertEquals(
                "Preserved reasoning",
                streamingState.value.streamingMessage?.reasoningText,
            )
        }

    @Test
    fun flushPendingReasoning_updatesStreamingMessage() =
        runTest {
            val uiState = MutableStateFlow(ChatUiState())
            val streamingState =
                MutableStateFlow(
                    StreamingState(
                        streamingMessage =
                            ChatMessage(
                                role = MessageRole.ASSISTANT,
                                content = "",
                                isStreaming = true,
                            ),
                    ),
                )
            val controller = controller(this, uiState, streamingState)

            controller.handleReasoningDelta(
                WsEvent.ReasoningDelta("Pending reasoning", "session"),
            )
            controller.flushPendingReasoning()

            assertEquals(
                "Pending reasoning",
                streamingState.value.streamingMessage?.reasoningText,
            )
        }

    @Test
    fun resetStreaming_doesNotResurrectClearedReasoningState() =
        runTest {
            val uiState = MutableStateFlow(ChatUiState())
            val streamingState = MutableStateFlow(StreamingState())
            val controller = controller(this, uiState, streamingState)

            controller.handleReasoningDelta(
                WsEvent.ReasoningDelta("Stale reasoning", "session"),
            )
            streamingState.value = StreamingState()
            controller.resetStreaming()

            assertEquals("", streamingState.value.reasoningText)
        }

    private fun controller(
        scope: CoroutineScope,
        uiState: MutableStateFlow<ChatUiState>,
        streamingState: MutableStateFlow<StreamingState>,
    ) = ChatStreamingController(
        scope = scope,
        uiState = uiState,
        streamingState = streamingState,
        isCurrentSession = { true },
        isTestEnvironment = { true },
    )
}
