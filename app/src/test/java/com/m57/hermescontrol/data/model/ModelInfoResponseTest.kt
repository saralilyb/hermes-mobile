package com.m57.hermescontrol.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelInfoResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun deserialize_readsEffectiveContextLengthAndIgnoresCapabilities() {
        val response =
            json.decodeFromString<ModelInfoResponse>(
                """
                {
                  "model": "gpt-5.6-sol",
                  "provider": "openai-codex",
                  "auto_context_length": 272000,
                  "config_context_length": 0,
                  "effective_context_length": 272000,
                  "capabilities": {"reasoning": true}
                }
                """.trimIndent(),
            )

        assertEquals("gpt-5.6-sol", response.model)
        assertEquals("openai-codex", response.provider)
        assertEquals(272_000L, response.autoContextLength)
        assertEquals(0L, response.configContextLength)
        assertEquals(272_000L, response.effectiveContextLength)
    }
}
