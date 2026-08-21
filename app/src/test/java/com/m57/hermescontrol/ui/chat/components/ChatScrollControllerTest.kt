package com.m57.hermescontrol.ui.chat.components

import com.m57.hermescontrol.ui.chat.SubagentIndicator
import com.m57.hermescontrol.ui.chat.TodoItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatScrollControllerTest {
    @Test
    fun tailFollowWaitsUntilDelayedTailItemIsLaidOut() =
        runTest {
            val state = FakeChatScrollableState(totalItemsCount = 1)
            val controller = ChatScrollController(state, backgroundScope, bottomPixelTolerance = 8)

            controller.onTailChanged(tailKey = "initial", messageCount = 1, listItemCount = 1)
            runCurrent()
            state.publishLayout(totalItemsCount = 1)
            runCurrent()
            state.scrollCalls.clear()

            controller.onTailChanged(tailKey = "new-tail", messageCount = 2, listItemCount = 2)
            runCurrent()

            assertEquals(listOf(false), state.scrollCalls)

            state.publishLayout(totalItemsCount = 2)
            runCurrent()

            assertEquals(listOf(false, false), state.scrollCalls)
        }

    @Test
    fun programmaticScrollCannotMasqueradeAsUpwardUserIntent() =
        runTest {
            val state = FakeChatScrollableState(totalItemsCount = 1)
            val controller = ChatScrollController(state, backgroundScope, bottomPixelTolerance = 8)
            controller.observeUserScrollPosition()
            runCurrent()

            state.positionDuringNextScroll =
                ChatScrollPosition(
                    atBottom = false,
                    lastScrolledBackward = true,
                    isScrollInProgress = true,
                )
            controller.jumpToBottom(animated = true, listItemCount = 2)
            runCurrent()

            assertTrue(controller.isFollowingBottom)

            state.publishLayout(totalItemsCount = 2)
            runCurrent()
            assertEquals(listOf(true, true), state.scrollCalls)
        }

    @Test
    fun upwardUserScrollPausesFollowAndReturningToBottomClearsUnread() =
        runTest {
            val state = FakeChatScrollableState(totalItemsCount = 2)
            val controller = ChatScrollController(state, backgroundScope, bottomPixelTolerance = 8)
            controller.observeUserScrollPosition()
            runCurrent()

            state.publishPosition(atBottom = false, lastScrolledBackward = true, isScrollInProgress = true)
            runCurrent()
            assertFalse(controller.isFollowingBottom)

            controller.onTailChanged(tailKey = "new-tail", messageCount = 3, listItemCount = 3)
            runCurrent()
            assertEquals(3, controller.pendingCount)
            assertTrue(state.scrollCalls.isEmpty())

            state.publishPosition(atBottom = true, lastScrolledBackward = false)
            runCurrent()
            assertTrue(controller.isFollowingBottom)
            assertEquals(0, controller.pendingCount)
        }

    @Test
    fun tailFollowHasBoundedLayoutRetries() =
        runTest {
            val state = FakeChatScrollableState(totalItemsCount = 1)
            val controller = ChatScrollController(state, this, bottomPixelTolerance = 8)

            controller.onTailChanged(tailKey = "new-tail", messageCount = 2, listItemCount = 2)
            advanceUntilIdle()

            assertEquals(1, state.scrollCalls.size)
        }

    @Test
    fun upwardUserIntentDuringLayoutWaitInvalidatesDelayedTailScroll() =
        runTest {
            val state = FakeChatScrollableState(totalItemsCount = 1)
            val controller = ChatScrollController(state, backgroundScope, bottomPixelTolerance = 8)
            controller.observeUserScrollPosition()
            runCurrent()

            controller.onTailChanged(tailKey = "tail", messageCount = 2, listItemCount = 2)
            runCurrent()
            assertEquals(listOf(false), state.scrollCalls)

            state.publishPosition(atBottom = false, lastScrolledBackward = true, isScrollInProgress = true)
            runCurrent()
            state.publishLayout(totalItemsCount = 2)
            runCurrent()

            assertFalse(controller.isFollowingBottom)
            assertEquals(listOf(false), state.scrollCalls)
        }

    @Test
    fun staleBackwardDirectionDuringLayoutWaitKeepsDelayedTailScroll() =
        runTest {
            val state = FakeChatScrollableState(totalItemsCount = 1)
            val controller = ChatScrollController(state, backgroundScope, bottomPixelTolerance = 8)
            controller.observeUserScrollPosition()
            runCurrent()

            controller.onTailChanged(tailKey = "tail", messageCount = 2, listItemCount = 2)
            runCurrent()
            assertEquals(listOf(false), state.scrollCalls)

            state.publishPosition(
                atBottom = false,
                lastScrolledBackward = true,
                isScrollInProgress = false,
            )
            runCurrent()
            state.publishLayout(totalItemsCount = 2)
            runCurrent()

            assertTrue(controller.isFollowingBottom)
            assertEquals(listOf(false, false), state.scrollCalls)
        }

    @Test
    fun rapidTailChangesLeaveOnlyNewestLayoutRetryEffective() =
        runTest {
            val state = FakeChatScrollableState(totalItemsCount = 1)
            val controller = ChatScrollController(state, backgroundScope, bottomPixelTolerance = 8)

            controller.onTailChanged(tailKey = "tail-2", messageCount = 2, listItemCount = 2)
            runCurrent()
            controller.onTailChanged(tailKey = "tail-3", messageCount = 3, listItemCount = 3)
            runCurrent()
            assertEquals(listOf(false, false), state.scrollCalls)

            state.publishLayout(totalItemsCount = 2)
            runCurrent()
            assertEquals(listOf(false, false), state.scrollCalls)

            state.publishLayout(totalItemsCount = 3)
            runCurrent()
            assertEquals(listOf(false, false, false), state.scrollCalls)
        }

    @Test
    fun tailContentKeyTracksRenderedStickyBarInputs() {
        val messages = listOf("message")
        val base =
            tailContentKey(
                messages = messages,
                streamingMessage = null,
                isThinking = false,
                subagentIndicators = listOf(SubagentIndicator(type = "subagent.start", goal = "short")),
                todos = listOf(TodoItem(id = "todo", content = "first", status = "pending")),
                clarifyRequest = null,
            )

        val changedGoal =
            tailContentKey(
                messages,
                null,
                false,
                listOf(SubagentIndicator(type = "subagent.start", goal = "a resized goal")),
                listOf(TodoItem(id = "todo", content = "first", status = "pending")),
                null,
            )
        val changedTodoStatus =
            tailContentKey(
                messages,
                null,
                false,
                listOf(SubagentIndicator(type = "subagent.complete", goal = "short", status = "completed")),
                listOf(TodoItem(id = "todo", content = "first", status = "in_progress")),
                null,
            )
        val changedTodoContent =
            tailContentKey(
                messages,
                null,
                false,
                emptyList(),
                listOf(TodoItem(id = "todo", content = "resized task", status = "in_progress")),
                null,
            )
        val otherTodoContent =
            tailContentKey(
                messages,
                null,
                false,
                emptyList(),
                listOf(TodoItem(id = "todo", content = "different task", status = "in_progress")),
                null,
            )

        assertFalse(base == changedGoal)
        assertFalse(base == changedTodoStatus)
        assertFalse(changedTodoContent == otherTodoContent)
    }

    @Test
    fun chatListItemCountIncludesEveryConditionalLazyRow() {
        assertEquals(
            8,
            chatListItemCount(
                messageCount = 4,
                hasStreamingMessage = true,
                isThinking = true,
                hasClarifyRequest = true,
                isLoadingOlder = true,
            ),
        )
    }
}

private class FakeChatScrollableState(
    totalItemsCount: Int,
) : ChatScrollableState {
    private val positions = MutableSharedFlow<ChatScrollPosition>(replay = 1)
    private val layouts = Channel<ChatLayoutBoundary>(Channel.UNLIMITED)
    private var generation = Any()
    private var totalItemsCount = totalItemsCount

    val scrollCalls = mutableListOf<Boolean>()
    var positionDuringNextScroll: ChatScrollPosition? = null

    override val firstVisibleItemScrollOffset: Int = 0

    init {
        positions.tryEmit(
            ChatScrollPosition(
                atBottom = true,
                lastScrolledBackward = false,
                isScrollInProgress = false,
            ),
        )
    }

    override fun positions(bottomPixelTolerance: Int): Flow<ChatScrollPosition> = positions

    override fun currentLayoutBoundary(): ChatLayoutBoundary = ChatLayoutBoundary(generation, totalItemsCount)

    override suspend fun awaitLayoutAfter(
        boundary: ChatLayoutBoundary,
        minimumItemsCount: Int,
    ): Boolean {
        while (true) {
            val next = layouts.receive()
            if (next.generation !== boundary.generation && next.totalItemsCount >= minimumItemsCount) {
                return true
            }
        }
    }

    override suspend fun scrollToBottom(animated: Boolean) {
        scrollCalls += animated
        positionDuringNextScroll?.let {
            positions.emit(it)
            positionDuringNextScroll = null
            yield()
        }
    }

    override suspend fun scrollToItem(
        index: Int,
        offset: Int,
        animated: Boolean,
    ) = Unit

    fun publishLayout(totalItemsCount: Int) {
        generation = Any()
        this.totalItemsCount = totalItemsCount
        layouts.trySend(currentLayoutBoundary())
    }

    fun publishPosition(
        atBottom: Boolean,
        lastScrolledBackward: Boolean,
        isScrollInProgress: Boolean = false,
    ) {
        positions.tryEmit(ChatScrollPosition(atBottom, lastScrolledBackward, isScrollInProgress))
    }
}
