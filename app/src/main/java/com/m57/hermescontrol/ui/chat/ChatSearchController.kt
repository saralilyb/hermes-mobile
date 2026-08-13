package com.m57.hermescontrol.ui.chat

/**
 * Pure search logic for in-chat text search.
 *
 * Computes match indices and navigation without depending on ViewModel or Android.
 */
class ChatSearchController {
    /**
     * Find visible user/assistant prose occurrences. Tool payloads, system
     * events, and reasoning are intentionally outside the searchable surface.
     */
    fun findMatches(
        messages: List<ChatMessage>,
        query: String,
    ): List<Int> {
        if (query.isBlank()) return emptyList()
        val matches = mutableListOf<Int>()
        for ((index, message) in messages.withIndex()) {
            if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) continue
            var from = 0
            while (matches.size < MAX_SEARCH_MATCHES) {
                val hit = message.content.indexOf(query, from, ignoreCase = true)
                if (hit < 0) break
                matches.add(index)
                from = hit + query.length
            }
            if (matches.size == MAX_SEARCH_MATCHES) break
        }
        return matches
    }

    /**
     * Compute the next/previous match index given the current position.
     */
    fun navigate(
        currentIndex: Int,
        matchCount: Int,
        direction: Int,
    ): Int {
        if (matchCount == 0) return -1
        return when (direction) {
            1 -> if (currentIndex >= matchCount - 1) 0 else currentIndex + 1
            -1 -> if (currentIndex <= 0) matchCount - 1 else currentIndex - 1
            else -> currentIndex
        }
    }

    companion object {
        const val MAX_SEARCH_MATCHES = 500
    }
}
