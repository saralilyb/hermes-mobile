package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger

class ContextUsageTest {
    @Test
    fun parseContextUsage_coercesJsonNumbersAndUsesWindowOccupancy() {
        val usage =
            parseContextUsage(
                mapOf(
                    "context_used" to 54_321.0,
                    "context_max" to "272000",
                    "input" to 1_900_000.0,
                    "output" to 88_000,
                    "reasoning" to 12_500.0,
                    "total" to 2_000_500.0,
                    "calls" to 42.0,
                    "compressions" to 3,
                    "model" to "gpt-5.6-sol",
                ),
            )

        assertNotNull(usage)
        assertEquals(54_321L, usage?.usedTokens)
        assertEquals(272_000L, usage?.maxTokens)
        assertEquals(1_900_000L, usage?.inputTokens)
        assertEquals(88_000L, usage?.outputTokens)
        assertEquals(12_500L, usage?.reasoningTokens)
        assertEquals(2_000_500L, usage?.totalTokens)
        assertEquals(42L, usage?.apiCalls)
        assertEquals(3L, usage?.compressions)
        assertEquals("gpt-5.6-sol", usage?.model)
    }

    @Test
    fun parseContextUsage_preservesZeroWindowOccupancyAndRejectsNegativeValues() {
        val emptyWindow =
            parseContextUsage(
                mapOf(
                    "context_used" to 0,
                    "context_max" to 272_000,
                ),
            )
        assertEquals(0L, emptyWindow?.usedTokens)
        listOf(-1, -0.5, 0.5).forEach { invalidValue ->
            val invalidWindow =
                parseContextUsage(
                    mapOf(
                        "context_used" to invalidValue,
                        "context_max" to 272_000,
                    ),
                )
            assertNull(invalidWindow?.usedTokens)
        }
    }

    @Test
    fun parseContextUsage_rejectsIntegralNumbersOutsideLongRange() {
        val tooLarge = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)
        val tooSmall = BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE)

        listOf(tooLarge, tooSmall).forEach { invalidValue ->
            val usage =
                parseContextUsage(
                    mapOf(
                        "context_used" to invalidValue,
                        "context_max" to 272_000,
                    ),
                )
            assertNull(usage?.usedTokens)
        }
    }

    @Test
    fun parseContextUsage_explicitUnknownClearsOnlyTheNumerator() {
        val previous =
            ContextUsage(
                usedTokens = 120_000L,
                maxTokens = 272_000L,
                inputTokens = 1_900_000L,
                outputTokens = 88_000L,
            )

        val usage =
            parseContextUsage(
                usage = mapOf("compressions" to 4.0),
                previous = previous,
            )

        assertNotNull(usage)
        assertNull(usage?.usedTokens)
        assertEquals(272_000L, usage?.maxTokens)
        assertEquals(1_900_000L, usage?.inputTokens)
        assertEquals(88_000L, usage?.outputTokens)
        assertEquals(4L, usage?.compressions)
    }

    @Test
    fun parseContextUsage_absentBlockKeepsOlderGatewayReading() {
        val previous = ContextUsage(usedTokens = 12_000L, maxTokens = 272_000L)

        assertEquals(previous, parseContextUsage(null, previous))
        assertEquals(previous, parseContextUsage(emptyMap<String, Any?>(), previous))
    }

    @Test
    fun matchingModelContextLength_acceptsExactAndBareQualifiedMatches() {
        assertEquals(
            272_000L,
            matchingModelContextLength(
                activeModel = "openai-codex/gpt-5.6-sol",
                fallbackModel = "openai-codex/gpt-5.6-sol",
                fallbackLength = 272_000L,
            ),
        )
        assertEquals(
            272_000L,
            matchingModelContextLength(
                activeModel = "gpt-5.6-sol",
                fallbackModel = "openai-codex/gpt-5.6-sol",
                fallbackLength = 272_000L,
            ),
        )
    }

    @Test
    fun matchingModelContextLength_rejectsDifferentOrUnknownModels() {
        assertNull(
            matchingModelContextLength(
                activeModel = "anthropic/claude-opus-5",
                fallbackModel = "openai-codex/gpt-5.6-sol",
                fallbackLength = 272_000L,
            ),
        )
        assertNull(
            matchingModelContextLength(
                activeModel = "fireworks/gpt-5.6-sol",
                fallbackModel = "openai-codex/gpt-5.6-sol",
                fallbackLength = 272_000L,
            ),
        )
        assertNull(
            matchingModelContextLength(
                activeModel = null,
                fallbackModel = "openai-codex/gpt-5.6-sol",
                fallbackLength = 272_000L,
            ),
        )
    }
}
