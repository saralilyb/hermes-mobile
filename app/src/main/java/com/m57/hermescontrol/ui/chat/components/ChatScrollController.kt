package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import com.m57.hermescontrol.ui.chat.SubagentIndicator
import com.m57.hermescontrol.ui.chat.TodoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val LAYOUT_WAIT_TIMEOUT_MS = 1_000L

internal data class ChatScrollPosition(
    val atBottom: Boolean,
    val lastScrolledBackward: Boolean,
    val isScrollInProgress: Boolean,
)

internal data class ChatLayoutBoundary(
    val generation: Any,
    val totalItemsCount: Int,
)

internal interface ChatScrollableState {
    val firstVisibleItemScrollOffset: Int

    fun positions(bottomPixelTolerance: Int): Flow<ChatScrollPosition>

    fun currentLayoutBoundary(): ChatLayoutBoundary

    suspend fun awaitLayoutAfter(
        boundary: ChatLayoutBoundary,
        minimumItemsCount: Int,
    ): Boolean

    suspend fun scrollToBottom(animated: Boolean)

    suspend fun scrollToItem(
        index: Int,
        offset: Int = 0,
        animated: Boolean = false,
    )
}

private class LazyListChatScrollableState(
    private val state: LazyListState,
) : ChatScrollableState {
    override val firstVisibleItemScrollOffset: Int
        get() = state.firstVisibleItemScrollOffset

    override fun positions(bottomPixelTolerance: Int): Flow<ChatScrollPosition> =
        snapshotFlow {
            ChatScrollPosition(
                atBottom = state.isAtBottom(bottomPixelTolerance),
                lastScrolledBackward = state.lastScrolledBackward,
                isScrollInProgress = state.isScrollInProgress,
            )
        }.distinctUntilChanged()

    override fun currentLayoutBoundary(): ChatLayoutBoundary {
        val info = state.layoutInfo
        return ChatLayoutBoundary(info, info.totalItemsCount)
    }

    override suspend fun awaitLayoutAfter(
        boundary: ChatLayoutBoundary,
        minimumItemsCount: Int,
    ): Boolean {
        snapshotFlow {
            val info = state.layoutInfo
            ChatLayoutBoundary(info, info.totalItemsCount)
        }.first {
            it.generation !== boundary.generation &&
                it.totalItemsCount >= minimumItemsCount
        }
        return true
    }

    override suspend fun scrollToBottom(animated: Boolean) {
        state.scrollToBottom(animated)
    }

    override suspend fun scrollToItem(
        index: Int,
        offset: Int,
        animated: Boolean,
    ) {
        if (animated) {
            state.animateScrollToItem(index, offset)
        } else {
            state.scrollToItem(index, offset)
        }
    }
}

/** Owns bottom-follow intent, unread counts, and history-anchor scrolling. */
class ChatScrollController internal constructor(
    private val listState: ChatScrollableState,
    internal val scope: CoroutineScope,
    bottomPixelTolerance: Int,
) {
    constructor(
        listState: LazyListState,
        scope: CoroutineScope,
        bottomPixelTolerance: Int = 8,
    ) : this(LazyListChatScrollableState(listState), scope, bottomPixelTolerance)

    var isFollowingBottom by mutableStateOf(true)
        private set

    var pendingCount by mutableIntStateOf(0)
        private set

    private var lastTailKey: Any? = null
    private var lastMessageCount: Int = 0
    private var programmaticScrolls by mutableIntStateOf(0)
    private var bottomPixelTolerance by mutableIntStateOf(bottomPixelTolerance)
    private var tailFollowGeneration = 0L
    private var tailFollowJob: Job? = null

    fun launchScroll(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }

    fun observeUserScrollPosition() {
        scope.launch {
            listState.positions(bottomPixelTolerance).collect { position ->
                if (position.atBottom) {
                    isFollowingBottom = true
                    pendingCount = 0
                } else if (
                    programmaticScrolls == 0 &&
                    position.isScrollInProgress &&
                    position.lastScrolledBackward
                ) {
                    isFollowingBottom = false
                    invalidateTailFollow()
                }
            }
        }
    }

    fun onTailChanged(
        tailKey: Any?,
        messageCount: Int = 0,
        listItemCount: Int = messageCount,
    ) {
        if (tailKey == lastTailKey) {
            lastMessageCount = messageCount
            return
        }
        val newMessages = (messageCount - lastMessageCount).coerceAtLeast(0)
        lastMessageCount = messageCount
        lastTailKey = tailKey
        if (isFollowingBottom) {
            scheduleTailFollow(listItemCount, animated = false)
        } else {
            pendingCount += newMessages
        }
    }

    private fun scheduleTailFollow(
        expectedItemsCount: Int,
        animated: Boolean,
    ) {
        val generation = ++tailFollowGeneration
        tailFollowJob?.cancel()
        tailFollowJob =
            scope.launch {
                scrollToBottomAwaitingLayout(expectedItemsCount, animated, generation)
            }
    }

    fun jumpToBottom(
        animated: Boolean = false,
        listItemCount: Int = 0,
    ) {
        pendingCount = 0
        isFollowingBottom = true
        scheduleTailFollow(listItemCount, animated)
    }

    private suspend fun scrollToBottomAwaitingLayout(
        expectedItemsCount: Int,
        animated: Boolean,
        generation: Long,
    ) {
        if (!canFollow(generation)) return
        val boundary = listState.currentLayoutBoundary()
        performProgrammaticScroll {
            listState.scrollToBottom(animated)
        }
        val laidOut =
            withTimeoutOrNull(LAYOUT_WAIT_TIMEOUT_MS) {
                listState.awaitLayoutAfter(boundary, expectedItemsCount)
            } ?: false
        if (laidOut && canFollow(generation)) {
            performProgrammaticScroll {
                listState.scrollToBottom(animated)
            }
        }
    }

    private fun canFollow(generation: Long): Boolean = isFollowingBottom && generation == tailFollowGeneration

    private fun invalidateTailFollow() {
        tailFollowGeneration += 1
        tailFollowJob?.cancel()
        tailFollowJob = null
    }

    fun resumeFollowing() = jumpToBottom(animated = true)

    fun showFab(contentPresent: Boolean): Boolean = !isFollowingBottom && contentPresent

    fun captureAnchorOffset(): Int = listState.firstVisibleItemScrollOffset

    fun scrollToItem(
        index: Int,
        offset: Int,
    ) {
        scope.launch {
            performProgrammaticScroll { listState.scrollToItem(index, offset) }
        }
    }

    fun scrollToSearchMatch(index: Int) {
        scope.launch {
            performProgrammaticScroll { listState.scrollToItem(index, animated = true) }
        }
    }

    private suspend fun performProgrammaticScroll(block: suspend () -> Unit) {
        programmaticScrolls += 1
        try {
            block()
        } finally {
            programmaticScrolls -= 1
        }
    }
}

fun tailContentKey(
    messages: List<*>,
    streamingMessage: Any?,
    isThinking: Boolean,
    subagentIndicators: List<SubagentIndicator>,
    todos: List<TodoItem>,
    clarifyRequest: Any?,
): Any =
    listOf(
        messages.size,
        streamingMessage?.hashCode() ?: 0,
        isThinking,
        stickySubagentBarLayoutKey(subagentIndicators, todos),
        clarifyRequest?.hashCode() ?: 0,
    )

internal data class StickySubagentBarLayoutKey(
    val activeSubagentCount: Int,
    val firstActiveSubagentGoal: String?,
    val activeTodoCount: Int,
    val firstActiveTodoContent: String?,
)

internal fun stickySubagentBarLayoutKey(
    indicators: List<SubagentIndicator>,
    todos: List<TodoItem>,
): StickySubagentBarLayoutKey {
    val activeSubagents = indicators.filter { it.isRunning }
    val activeTodos = todos.filter { it.isInProgress }
    return StickySubagentBarLayoutKey(
        activeSubagentCount = activeSubagents.size,
        firstActiveSubagentGoal = activeSubagents.firstOrNull()?.goal?.takeIf { it.isNotBlank() },
        activeTodoCount = activeTodos.size,
        firstActiveTodoContent = activeTodos.firstOrNull()?.content?.takeIf { it.isNotBlank() },
    )
}

fun LazyListState.isAtBottom(tolerance: Int = 8): Boolean {
    val layoutInfo = this.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) return true
    val lastItem = visibleItems.last()
    if (lastItem.index < layoutInfo.totalItemsCount - 1) return false
    val lastBottom = lastItem.offset + lastItem.size
    val viewportBottom = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
    return lastBottom <= viewportBottom + tolerance
}

suspend fun LazyListState.scrollToBottom(animated: Boolean) {
    val layoutInfo = this.layoutInfo
    if (layoutInfo.totalItemsCount == 0) return
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (animated) {
        animateScrollToItem(lastIndex)
    } else {
        scrollToItem(lastIndex)
    }
    val info = this.layoutInfo
    val lastItem = info.visibleItemsInfo.lastOrNull { it.index == lastIndex } ?: return
    val remaining =
        (lastItem.offset + lastItem.size + info.afterContentPadding) - info.viewportEndOffset
    if (remaining > 0) {
        if (animated) {
            animateScrollBy(remaining.toFloat())
        } else {
            scroll { scrollBy(remaining.toFloat()) }
        }
    }
}

@Composable
fun rememberChatScrollController(
    listState: LazyListState,
    scope: CoroutineScope,
): ChatScrollController {
    val bottomTolerance = with(LocalDensity.current) { CHAT_LIST_VERTICAL_CONTENT_PADDING.roundToPx() }
    return remember(listState, scope, bottomTolerance) {
        ChatScrollController(listState, scope, bottomTolerance)
    }
}
