package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.ModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialWarningPolicyTest {
    @Test
    fun suppressesFalseMissingKeyWarningForAuthenticatedCurrentProvider() {
        val providers =
            listOf(
                provider(
                    slug = "meridian",
                    authenticated = true,
                    isCurrent = true,
                ),
            )

        assertNull(
            actionableCredentialWarning(
                warning = MISSING_KEY_WARNING,
                currentSessionModel = "meridian/claude-sonnet-5",
                providers = providers,
                inventoryResolved = true,
            ),
        )
    }

    @Test
    fun suppressesWarningUsingInventoryCurrentProviderBeforeSessionInfoArrives() {
        val providers =
            listOf(
                provider(
                    slug = "meridian",
                    authenticated = true,
                    isCurrent = true,
                ),
            )

        assertNull(
            actionableCredentialWarning(
                warning = MISSING_KEY_WARNING,
                currentSessionModel = null,
                providers = providers,
                inventoryResolved = true,
            ),
        )
    }

    @Test
    fun preservesWarningForProviderThatActuallyNeedsSetup() {
        val providers =
            listOf(
                provider(
                    slug = "anthropic",
                    authenticated = false,
                    isCurrent = true,
                ),
            )
        val warning =
            "No API key configured for provider 'anthropic'. First message will fail."

        assertEquals(
            warning,
            actionableCredentialWarning(
                warning = warning,
                currentSessionModel = "anthropic/claude-sonnet-5",
                providers = providers,
                inventoryResolved = true,
            ),
        )
    }

    @Test
    fun defersWarningUntilProviderInventoryResolves() {
        assertNull(
            actionableCredentialWarning(
                warning = MISSING_KEY_WARNING,
                currentSessionModel = "meridian/claude-sonnet-5",
                providers = emptyList(),
                inventoryResolved = false,
            ),
        )
    }

    @Test
    fun preservesWarningWhenResolvedInventoryCannotVerifyProvider() {
        assertEquals(
            MISSING_KEY_WARNING,
            actionableCredentialWarning(
                warning = MISSING_KEY_WARNING,
                currentSessionModel = "meridian/claude-sonnet-5",
                providers = emptyList(),
                inventoryResolved = true,
            ),
        )
    }

    @Test
    fun doesNotUseDifferentGlobalProviderForSessionWarning() {
        val providers =
            listOf(
                provider(
                    slug = "openai-codex",
                    authenticated = true,
                    isCurrent = true,
                ),
            )

        assertEquals(
            MISSING_KEY_WARNING,
            actionableCredentialWarning(
                warning = MISSING_KEY_WARNING,
                currentSessionModel = "meridian/claude-sonnet-5",
                providers = providers,
                inventoryResolved = true,
            ),
        )
    }

    @Test
    fun preservesOtherCredentialWarningsForAuthenticatedProvider() {
        val providers =
            listOf(
                provider(
                    slug = "openai-codex",
                    authenticated = true,
                    isCurrent = true,
                ),
            )
        val warning = "OAuth session expired"

        assertEquals(
            warning,
            actionableCredentialWarning(
                warning = warning,
                currentSessionModel = "openai-codex/gpt-5.6",
                providers = providers,
                inventoryResolved = true,
            ),
        )
    }

    private fun provider(
        slug: String,
        authenticated: Boolean,
        isCurrent: Boolean,
    ) = ModelProvider(
        slug = slug,
        name = slug,
        is_current = isCurrent,
        models = listOf("claude-sonnet-5"),
        authenticated = authenticated,
    )

    private companion object {
        const val MISSING_KEY_WARNING =
            "No API key configured for provider 'meridian'. First message will fail."
    }
}
