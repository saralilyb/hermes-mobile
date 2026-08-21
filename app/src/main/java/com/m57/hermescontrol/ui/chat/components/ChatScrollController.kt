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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val LAYOUT_WAIT_TIMEOUT_MS = 1_000L

internal data class ChatScrollPosition(
    val atBottom: Boolean,
    val lastScrolledBackward: Boolean,
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

    fun launchScroll(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }

    fun observeUserScrollPosition() {
        scope.launch {
            listState.positions(bottomPixelTolerance).collect { position ->
                if (position.atBottom) {
                    isFollowingBottom = true
                    pendingCount = 0
                } else if (programmaticScrolls == 0 && position.lastScrolledBackward) {
                    isFollowingBottom = false
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
            scope.launch {
                scrollToBottomAwaitingLayout(listItemCount)
            }
        } else {
            pendingCount += newMessages
        }
    }

    private suspend fun scrollToBottomAwaitingLayout(expectedItemsCount: Int) {
        val boundary = listState.currentLayoutBoundary()
        performProgrammaticScroll {
            listState.scrollToBottom(animated = false)
        }
        val laidOut =
            withTimeoutOrNull(LAYOUT_WAIT_TIMEOUT_MS) {
                listState.awaitLayoutAfter(boundary, expectedItemsCount)
            } ?: false
        if (laidOut) {
            performProgrammaticScroll {
                listState.scrollToBottom(animated = false)
            }
        }
    }

    fun jumpToBottom(
        animated: Boolean = false,
        listItemCount: Int = 0,
    ) {
        pendingCount = 0
        isFollowingBottom = true
        scope.launch {
            scrollToBottomAwaitingLayout(listItemCount, animated)
        }
    }

    private suspend fun scrollToBottomAwaitingLayout(
        expectedItemsCount: Int,
        animated: Boolean,
    ) {
        val boundary = listState.currentLayoutBoundary()
        performProgrammaticScroll {
            listState.scrollToBottom(animated)
        }
        val laidOut =
            withTimeoutOrNull(LAYOUT_WAIT_TIMEOUT_MS) {
                listState.awaitLayoutAfter(boundary, expectedItemsCount)
            } ?: false
        if (laidOut) {
            performProgrammaticScroll {
                listState.scrollToBottom(animated)
            }
        }
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
    subagentIndicators: List<*>,
    clarifyRequest: Any?,
): Any =
    listOf(
        messages.size,
        streamingMessage?.hashCode() ?: 0,
        isThinking,
        subagentIndicators.size,
        clarifyRequest?.hashCode() ?: 0,
    )

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
