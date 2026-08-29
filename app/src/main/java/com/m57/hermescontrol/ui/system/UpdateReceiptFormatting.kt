package com.m57.hermescontrol.ui.system

import com.m57.hermescontrol.data.model.UpdateReceiptResponse
import com.m57.hermescontrol.data.model.UpdateReceiptVersion

enum class UpdateReceiptOutcome {
    SUCCESS,
    PARTIAL,
    RUNNING,
}

/** Localizable, privacy-safe subset of a backend update receipt. */
data class UpdateReceiptDisplay(
    val outcome: UpdateReceiptOutcome? = null,
    val fromVersion: String? = null,
    val toVersion: String? = null,
)

/**
 * Extracts only the backend update outcome and version transition.
 *
 * Fleet/profile data, command arguments, process identifiers, timestamps, and
 * backend-supplied prose are intentionally excluded from the mobile UI.
 */
internal fun summarizeUpdateReceipt(response: UpdateReceiptResponse?): UpdateReceiptDisplay? {
    if (response == null) return null

    val receipt = response.receipt
    val summary = response.summary
    val outcome =
        when ((summary?.outcome ?: receipt?.outcome)?.lowercase()) {
            "success" -> UpdateReceiptOutcome.SUCCESS
            "partial" -> UpdateReceiptOutcome.PARTIAL
            "running" -> UpdateReceiptOutcome.RUNNING
            else -> null
        }
    val fromVersion =
        receipt?.preUpdate.displayVersion()
            ?: summary?.preSha.shortSha()
    val toVersion =
        receipt?.postUpdate.displayVersion()
            ?: summary?.postVersion
            ?: summary?.postSha.shortSha()

    if (outcome == null && fromVersion == null && toVersion == null) return null
    return UpdateReceiptDisplay(
        outcome = outcome,
        fromVersion = fromVersion,
        toVersion = toVersion,
    )
}

private fun UpdateReceiptVersion?.displayVersion(): String? =
    this?.version?.takeIf(String::isNotBlank) ?: this?.sha.shortSha()

private fun String?.shortSha(): String? =
    this
        ?.takeIf(String::isNotBlank)
        ?.take(8)
