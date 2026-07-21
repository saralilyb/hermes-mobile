package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.ModelProvider

/**
 * Returns a gateway credential warning only when the model inventory does not
 * already prove that the active provider is usable.
 *
 * Hermes Agent 0.19.x can emit a missing-API-key warning for a configured
 * keyless custom provider even though the provider inventory correctly marks
 * it authenticated. Trusting that structured readiness signal avoids hiding
 * real setup errors for providers whose inventory row is unauthenticated.
 */
internal fun actionableCredentialWarning(
    warning: String?,
    currentSessionModel: String?,
    providers: List<ModelProvider>,
    inventoryResolved: Boolean,
): String? {
    val message = warning?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val reportedProvider =
        MISSING_API_KEY_WARNING
            .matchEntire(message)
            ?.groupValues
            ?.get(1)
            ?: return message
    if (!inventoryResolved) return null

    val sessionProvider =
        currentSessionModel
            ?.substringBefore('/')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val activeProvider =
        if (sessionProvider == null) {
            providers.singleOrNull { it.is_current == true }
        } else {
            providers.firstOrNull { it.slug.equals(sessionProvider, ignoreCase = true) }
        }
    if (activeProvider?.slug?.equals(reportedProvider, ignoreCase = true) != true) {
        return message
    }

    return message.takeUnless { activeProvider.authenticated == true }
}

private val MISSING_API_KEY_WARNING =
    Regex("^No API key configured for provider '([^']+)'\\. First message will fail\\.$")
