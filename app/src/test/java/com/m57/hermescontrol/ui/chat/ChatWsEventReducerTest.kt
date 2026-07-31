package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWsEventReducerTest {
    @Test
    fun testToolProgress_updatesProgressPreviewForMatchingRunningTool() {
        val initialMessage =
            ChatMessage(
                role = MessageRole.TOOL,
                content = "{}",
                toolName = "web_search",
                toolStatus = ToolStatus.RUNNING,
            )
        val state =
            ChatUiState(
                messages = listOf(initialMessage),
                currentSessionId = "session-1",
            )
        val event =
            WsEvent.ToolProgress(
                name = "web_search",
                preview = "fetching google...",
                sessionId = "session-1",
            )

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = event,
                currentSessionId = "session-1",
            )

        assertEquals(1, result.state.messages.size)
        val updatedMessage = result.state.messages.first()
        assertEquals(ToolStatus.RUNNING, updatedMessage.toolStatus)
        assertEquals("fetching google...", updatedMessage.progressPreview)
    }

    @Test
    fun testToolGenerating_clearsProgressPreviewForMatchingRunningTool() {
        val initialMessage =
            ChatMessage(
                role = MessageRole.TOOL,
                content = "{}",
                toolName = "code_writer",
                toolStatus = ToolStatus.RUNNING,
                progressPreview = "writing...",
            )
        val state =
            ChatUiState(
                messages = listOf(initialMessage),
                currentSessionId = "session-1",
            )
        val event =
            WsEvent.ToolGenerating(
                name = "code_writer",
                sessionId = "session-1",
            )

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = event,
                currentSessionId = "session-1",
            )

        assertEquals(1, result.state.messages.size)
        val updatedMessage = result.state.messages.first()
        assertEquals(ToolStatus.RUNNING, updatedMessage.toolStatus)
        assertEquals("", updatedMessage.progressPreview)
    }

    @Test
    fun testSubagentEvent_appendsToSubagentIndicators() {
        val state =
            ChatUiState(
                currentSessionId = "session-1",
                subagentIndicators = emptyList(),
            )
        val event =
            WsEvent.SubagentEvent(
                type = "subagent.start",
                sessionId = "session-1",
                payload =
                    mapOf(
                        "goal" to "analyze repository",
                        "task_index" to 2,
                        "task_count" to 4,
                        "subagent_id" to "sub-1",
                        "text" to "analyzing files",
                    ),
            )

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = event,
                currentSessionId = "session-1",
            )

        assertEquals(1, result.state.subagentIndicators.size)
        val indicator = result.state.subagentIndicators.first()
        assertEquals("subagent.start", indicator.type)
        assertEquals("analyze repository", indicator.goal)
        assertEquals(2, indicator.taskIndex)
        assertEquals(4, indicator.taskCount)
        assertEquals("sub-1", indicator.subagentId)
        assertEquals("analyzing files", indicator.text)
    }

    @Test
    fun testSubagentEvent_updatesExistingIndicatorBySubagentId() {
        val initialIndicator =
            SubagentIndicator(
                type = "subagent.start",
                goal = "analyze repository",
                taskIndex = 1,
                taskCount = 4,
                subagentId = "sub-1",
                text = "starting",
            )
        val state =
            ChatUiState(
                currentSessionId = "session-1",
                subagentIndicators = listOf(initialIndicator),
            )
        val event =
            WsEvent.SubagentEvent(
                type = "subagent.progress",
                sessionId = "session-1",
                payload =
                    mapOf(
                        "task_index" to 2,
                        "subagent_id" to "sub-1",
                        "text" to "in progress",
                    ),
            )

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = event,
                currentSessionId = "session-1",
            )

        assertEquals(1, result.state.subagentIndicators.size)
        val indicator = result.state.subagentIndicators.first()
        assertEquals("subagent.progress", indicator.type)
        assertEquals("analyze repository", indicator.goal)
        assertEquals(2, indicator.taskIndex)
        assertEquals(4, indicator.taskCount)
        assertEquals("sub-1", indicator.subagentId)
        assertEquals("in progress", indicator.text)
    }

    @Test
    fun testSubagentComplete_updatesIndicatorToCompleted() {
        val initialIndicator =
            SubagentIndicator(
                type = "subagent.start",
                goal = "analyze repository",
                taskIndex = 1,
                taskCount = 4,
                subagentId = "sub-1",
                text = "starting",
            )
        val state =
            ChatUiState(
                currentSessionId = "session-1",
                subagentIndicators = listOf(initialIndicator),
            )
        val event =
            WsEvent.SubagentEvent(
                type = "subagent.complete",
                sessionId = "session-1",
                payload =
                    mapOf(
                        "subagent_id" to "sub-1",
                        "status" to "completed",
                        "summary" to "done",
                    ),
            )

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = event,
                currentSessionId = "session-1",
            )

        assertEquals(1, result.state.subagentIndicators.size)
        val indicator = result.state.subagentIndicators.first()
        assertEquals("subagent.complete", indicator.type)
        assertEquals("completed", indicator.status)
        assertEquals("done", indicator.summary)
        assertTrue(indicator.isComplete)
    }

    @Test
    fun testMessageStart_preservesReasoningFromPrecedingDelta() {
        val result =
            ChatWsEventReducer.reduce(
                state = ChatUiState(currentSessionId = "session-1"),
                streamingState =
                    StreamingState(
                        isReasoning = true,
                        reasoningText = "Preserved reasoning",
                    ),
                event = WsEvent.MessageStart("session-1"),
                currentSessionId = "session-1",
            )

        assertEquals("Preserved reasoning", result.streamingState.reasoningText)
        assertEquals(
            "Preserved reasoning",
            result.streamingState.streamingMessage?.reasoningText,
        )
    }

    @Test
    fun testMessageComplete_fallsBackToMessageReasoning() {
        val result =
            ChatWsEventReducer.reduce(
                state = ChatUiState(currentSessionId = "session-1"),
                streamingState =
                    StreamingState(
                        streamingMessage =
                            ChatMessage(
                                role = MessageRole.ASSISTANT,
                                content = "",
                                reasoningText = "Preserved reasoning",
                                isStreaming = true,
                            ),
                    ),
                event = WsEvent.MessageComplete("Answer", "session-1"),
                currentSessionId = "session-1",
            )

        assertEquals("Preserved reasoning", result.state.messages.single().reasoningText)
    }

    @Test
    fun testMessageComplete_withMediaDefersPersistenceUntilAttachmentExtraction() {
        val result =
            ChatWsEventReducer.reduce(
                state = ChatUiState(currentSessionId = "session-1"),
                streamingState = StreamingState(),
                event = WsEvent.MessageComplete("Image\nMEDIA:/tmp/image.png", "session-1"),
                currentSessionId = "session-1",
            )

        assertTrue(result.effects.any { it is ReducerEffect.AttachHostMedia })
        assertFalse(result.effects.any { it is ReducerEffect.PersistMessage })
    }

    @Test
    fun testMessageDoneWithMedia_defersPersistenceUntilAttachmentsAreMapped() {
        val streamingMessage =
            ChatMessage(
                id = "media-done",
                role = MessageRole.ASSISTANT,
                content = "Image\nMEDIA:/tmp/image.png",
                isStreaming = true,
            )

        val result =
            ChatWsEventReducer.reduce(
                state = ChatUiState(currentSessionId = "session-1"),
                streamingState = StreamingState(streamingMessage = streamingMessage),
                event = WsEvent.MessageDone("session-1"),
                currentSessionId = "session-1",
            )

        assertTrue(result.effects.any { it is ReducerEffect.AttachHostMedia })
        assertFalse(result.effects.any { it is ReducerEffect.PersistMessage })
    }

    @Test
    fun testSessionMismatch_isIgnored() {
        val initialMessage =
            ChatMessage(
                role = MessageRole.TOOL,
                content = "{}",
                toolName = "web_search",
                toolStatus = ToolStatus.RUNNING,
            )
        val state =
            ChatUiState(
                messages = listOf(initialMessage),
                currentSessionId = "session-1",
            )
        val event =
            WsEvent.ToolProgress(
                name = "web_search",
                preview = "fetching google...",
                sessionId = "session-different",
            )

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = event,
                currentSessionId = "session-1",
            )

        assertEquals(1, result.state.messages.size)
        val updatedMessage = result.state.messages.first()
        assertEquals(ToolStatus.RUNNING, updatedMessage.toolStatus)
        assertEquals(null, updatedMessage.progressPreview)
    }

    @Test
    fun testSubagentEvent_accumulatesLiveTranscriptLogs() {
        val state =
            ChatUiState(
                currentSessionId = "session-1",
                subagentIndicators = emptyList(),
            )
        val startEvent =
            WsEvent.SubagentEvent(
                type = "subagent.start",
                sessionId = "session-1",
                payload =
                    mapOf(
                        "subagent_id" to "sub-1",
                        "goal" to "research api",
                        "text" to "Initializing subagent",
                    ),
            )

        val res1 =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = startEvent,
                currentSessionId = "session-1",
            )

        val progressEvent =
            WsEvent.SubagentEvent(
                type = "subagent.progress",
                sessionId = "session-1",
                payload =
                    mapOf(
                        "subagent_id" to "sub-1",
                        "text" to "Fetching documentation",
                    ),
            )

        val res2 =
            ChatWsEventReducer.reduce(
                state = res1.state,
                streamingState = StreamingState(),
                event = progressEvent,
                currentSessionId = "session-1",
            )

        val indicator = res2.state.subagentIndicators.first()
        assertEquals(2, indicator.logs.size)
        assertEquals("Initializing subagent", indicator.logs[0].text)
        assertEquals("Fetching documentation", indicator.logs[1].text)
        assertTrue(indicator.isRunning)
    }

    @Test
    fun testMessageStart_prunesCompletedSubagents() {
        val completedSubagent =
            SubagentIndicator(
                type = "subagent.complete",
                goal = "finished task",
                subagentId = "sub-1",
                status = "completed",
            )
        val state =
            ChatUiState(
                currentSessionId = "session-1",
                subagentIndicators = listOf(completedSubagent),
            )
        val startEvent = WsEvent.MessageStart(sessionId = "session-1")

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = startEvent,
                currentSessionId = "session-1",
            )

        assertTrue(result.state.subagentIndicators.isEmpty())
    }

    @Test
    fun testToolComplete_matchesConcurrentSameNameToolByCallId() {
        val initialState = ChatUiState(currentSessionId = "session-1")
        val firstStart =
            WsEvent.ToolStart(
                name = "terminal",
                data = mapOf("tool_id" to "call-a", "command" to "first"),
                sessionId = "session-1",
            )
        val secondStart =
            WsEvent.ToolStart(
                name = "terminal",
                data = mapOf("tool_id" to "call-b", "command" to "second"),
                sessionId = "session-1",
            )
        val afterFirst =
            ChatWsEventReducer.reduce(
                state = initialState,
                streamingState = StreamingState(),
                event = firstStart,
                currentSessionId = "session-1",
            )
        val afterSecond =
            ChatWsEventReducer.reduce(
                state = afterFirst.state,
                streamingState = StreamingState(),
                event = secondStart,
                currentSessionId = "session-1",
            )

        val completed =
            ChatWsEventReducer.reduce(
                state = afterSecond.state,
                streamingState = StreamingState(),
                event =
                    WsEvent.ToolComplete(
                        name = "terminal",
                        data = mapOf("tool_id" to "call-a", "output" to "done"),
                        sessionId = "session-1",
                    ),
                currentSessionId = "session-1",
            )

        assertEquals(
            listOf("call-a", "call-b"),
            completed.state.messages.map { it.toolCallId },
        )
        assertEquals(
            listOf(ToolStatus.COMPLETED, ToolStatus.RUNNING),
            completed.state.messages.map { it.toolStatus },
        )
        assertTrue(completed.state.messages.first().content.contains("done"))
    }

    @Test
    fun testToolStart_extractsAgentTodos() {
        val state = ChatUiState(currentSessionId = "session-1")
        val todoEvent =
            WsEvent.ToolStart(
                name = "todo",
                sessionId = "session-1",
                data =
                    mapOf(
                        "todos" to
                            listOf(
                                mapOf("id" to "1", "content" to "Inspect repo", "status" to "completed"),
                                mapOf("id" to "2", "content" to "Implement feature", "status" to "in_progress"),
                            ),
                    ),
            )

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = todoEvent,
                currentSessionId = "session-1",
            )

        assertEquals(2, result.state.todos.size)
        assertEquals("Inspect repo", result.state.todos[0].content)
        assertTrue(result.state.todos[0].isCompleted)
        assertEquals("Implement feature", result.state.todos[1].content)
        assertTrue(result.state.todos[1].isInProgress)
    }

    @Test
    fun testHydrateTodosFromMessages_parsesStoredToolMessage() {
        val todoMessage =
            ChatMessage(
                role = MessageRole.TOOL,
                toolName = "todo",
                content = """{"todos":[{"id":"a","content":"Write tests","status":"completed"}]}""",
            )
        val todos = hydrateTodosFromMessages(listOf(todoMessage))

        assertEquals(1, todos.size)
        assertEquals("Write tests", todos[0].content)
        assertTrue(todos[0].isCompleted)
    }

    @Test
    fun testReviewSummary_addsSystemMessage() {
        val state = ChatUiState(currentSessionId = "session-1")
        val event =
            WsEvent.ReviewSummary(
                text = "💾 Self-improvement review: Skill 'android-ci' patched",
                sessionId = "session-1",
            )

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = event,
                currentSessionId = "session-1",
            )

        assertEquals(1, result.state.messages.size)
        val msg = result.state.messages.first()
        assertEquals(MessageRole.SYSTEM, msg.role)
        assertEquals("💾 Self-improvement review: Skill 'android-ci' patched", msg.content)
    }
}
