package com.m57.hermescontrol.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionMessagesReasoningTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sessionMessage_readsReasoningField() {
        val response =
            json.decodeFromString<SessionMessagesResponse>(
                """
                {
                  "messages": [
                    {
                      "role": "assistant",
                      "content": "Answer",
                      "reasoning": "First reason"
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals("First reason", response.messages.single().reasoningText)
    }

    @Test
    fun sessionMessage_readsLegacyReasoningTextField() {
        val response =
            json.decodeFromString<SessionMessagesResponse>(
                """
                {
                  "messages": [
                    {
                      "role": "assistant",
                      "content": "Answer",
                      "reasoning_text": "Legacy reason"
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals("Legacy reason", response.messages.single().reasoningText)
    }
}
