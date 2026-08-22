package com.m57.hermescontrol.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelCapabilitiesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun modelOptionsDeserializeReasoningCapabilities() {
        val response =
            json.decodeFromString<ModelOptionsResponse>(
                """
                {
                  "providers": [
                    {
                      "slug": "openai-codex",
                      "name": "OpenAI Codex",
                      "models": ["gpt-5.6-sol"],
                      "capabilities": {
                        "gpt-5.6-sol": {
                          "fast": false,
                          "reasoning": true,
                          "can_disable_reasoning": false
                        }
                      }
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(
            ModelCapabilities(
                fast = false,
                reasoning = true,
                can_disable_reasoning = false,
            ),
            response.providers.single().capabilities?.get("gpt-5.6-sol"),
        )
    }

    @Test
    fun capabilitiesForQualifiedModelPreservesModelSubpaths() {
        val expected = ModelCapabilities(reasoning = true)
        val providers =
            listOf(
                ModelProvider(
                    slug = "openrouter",
                    name = "OpenRouter",
                    capabilities = mapOf("vendor/model" to expected),
                ),
            )

        assertEquals(
            expected,
            providers.capabilitiesFor("openrouter/vendor/model"),
        )
    }

    @Test
    fun capabilitiesForMissingOrUnqualifiedModelReturnsNull() {
        val providers =
            listOf(
                ModelProvider(
                    slug = "openai-codex",
                    name = "OpenAI Codex",
                ),
            )

        assertNull(providers.capabilitiesFor(null))
        assertNull(providers.capabilitiesFor("gpt-5.6-sol"))
        assertNull(providers.capabilitiesFor("openai-codex/gpt-5.6-sol"))
    }
}
