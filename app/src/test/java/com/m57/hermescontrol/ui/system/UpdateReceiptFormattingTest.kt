package com.m57.hermescontrol.ui.system

import com.m57.hermescontrol.data.model.UpdateReceipt
import com.m57.hermescontrol.data.model.UpdateReceiptFleetEntry
import com.m57.hermescontrol.data.model.UpdateReceiptResponse
import com.m57.hermescontrol.data.model.UpdateReceiptSummary
import com.m57.hermescontrol.data.model.UpdateReceiptVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateReceiptFormattingTest {
    @Test
    fun `null and empty receipts yield no display`() {
        assertNull(summarizeUpdateReceipt(null))
        assertNull(summarizeUpdateReceipt(UpdateReceiptResponse()))
    }

    @Test
    fun `full receipt keeps outcome and version but omits fleet metadata`() {
        val receipt =
            UpdateReceiptResponse(
                receipt =
                    UpdateReceipt(
                        outcome = "success",
                        preUpdate =
                            UpdateReceiptVersion(
                                version = "0.20.4",
                                sha = "a".repeat(40),
                            ),
                        postUpdate =
                            UpdateReceiptVersion(
                                version = "0.20.5",
                                sha = "b".repeat(40),
                            ),
                        fleet =
                            listOf(
                                UpdateReceiptFleetEntry(
                                    profile = "private-profile",
                                    state = "current",
                                ),
                            ),
                    ),
            )

        assertEquals(
            UpdateReceiptDisplay(
                outcome = UpdateReceiptOutcome.SUCCESS,
                fromVersion = "0.20.4",
                toVersion = "0.20.5",
            ),
            summarizeUpdateReceipt(receipt),
        )
    }

    @Test
    fun `summary-only receipt keeps supported outcome and post version`() {
        val receipt =
            UpdateReceiptResponse(
                summary =
                    UpdateReceiptSummary(
                        outcome = "partial",
                        postVersion = "0.21.0",
                        fleetStates = listOf("current"),
                    ),
            )

        assertEquals(
            UpdateReceiptDisplay(
                outcome = UpdateReceiptOutcome.PARTIAL,
                toVersion = "0.21.0",
            ),
            summarizeUpdateReceipt(receipt),
        )
    }

    @Test
    fun `version falls back to short sha`() {
        val receipt =
            UpdateReceiptResponse(
                receipt =
                    UpdateReceipt(
                        outcome = "running",
                        preUpdate =
                            UpdateReceiptVersion(
                                sha = "abcdef1234567890deadbeef",
                            ),
                        postUpdate = UpdateReceiptVersion(sha = "11112222"),
                    ),
            )

        assertEquals(
            UpdateReceiptDisplay(
                outcome = UpdateReceiptOutcome.RUNNING,
                fromVersion = "abcdef12",
                toVersion = "11112222",
            ),
            summarizeUpdateReceipt(receipt),
        )
    }

    @Test
    fun `unknown backend outcome is not exposed as raw UI text`() {
        val receipt =
            UpdateReceiptResponse(
                summary =
                    UpdateReceiptSummary(
                        outcome = "future-state",
                        postVersion = "0.22.0",
                    ),
            )

        assertEquals(
            UpdateReceiptDisplay(toVersion = "0.22.0"),
            summarizeUpdateReceipt(receipt),
        )
    }
}
