package com.gomoney.capture.ui

import com.gomoney.capture.ui.theme.StatusPresentation
import com.gomoney.capture.ui.theme.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StatusPresentationTest {

    @Test
    fun mapsSentToSentChipWithSuccessTone() {
        val presentation = StatusPresentation.from("sent")
        assertEquals("Sent", presentation.label)
        assertEquals(StatusTone.Success, presentation.tone)
    }

    @Test
    fun mapsPendingStatesToWaitingChipWithWarningTone() {
        val states = listOf("queued", "sending", "held", "captured", "parsed")
        for (state in states) {
            val presentation = StatusPresentation.from(state)
            assertEquals("Waiting", presentation.label)
            assertEquals(StatusTone.Warning, presentation.tone)
        }
    }

    @Test
    fun mapsFailedToCouldntSendChipWithErrorTone() {
        val presentation = StatusPresentation.from("failed")
        assertEquals("Couldn't send", presentation.label)
        assertEquals(StatusTone.Error, presentation.tone)
    }

    @Test
    fun mapsParseProblemsWithErrorTone() {
        // Delivery without normalized tx
        val noNormalizedTx = StatusPresentation.from("parsed", hasNormalizedTx = false)
        assertEquals("Parse problem", noNormalizedTx.label)
        assertEquals(StatusTone.Error, noNormalizedTx.tone)

        // Error category parse
        val parseCategory = StatusPresentation.from("failed", errorCategory = "parse")
        assertEquals("Parse problem", parseCategory.label)
        assertEquals(StatusTone.Error, parseCategory.tone)

        // Wire state parse_error
        val parseErrorState = StatusPresentation.from("parse_error")
        assertEquals("Parse problem", parseErrorState.label)
        assertEquals(StatusTone.Error, parseErrorState.tone)
    }

    @Test
    fun neverOutputsRawWireEnumNames() {
        val wireNames = listOf("QUEUED", "FAILED", "SENDING", "HELD", "CAPTURED", "PARSED")
        for (wire in wireNames) {
            val presentation = StatusPresentation.from(wire)
            assertFalse("Should not contain raw $wire", presentation.label.equals(wire, ignoreCase = true))
        }
    }
}
