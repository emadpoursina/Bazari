package com.gomoney.capture.ui

import com.gomoney.capture.ui.theme.EventDayGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class EventDayGroupingTest {

    private val zoneId = ZoneId.of("UTC")

    private fun createRow(id: String, txAt: String): EventRow =
        EventRow(
            amountMinor = 1000L,
            bank = "TestBank",
            txAt = txAt,
            state = "sent",
            errorCategory = null,
            txId = id,
        )

    @Test
    fun groupsEventsByDeviceLocalCalendarDayNewestFirst() {
        val row1 = createRow("1", "2026-11-17T12:00:00Z")
        val row2 = createRow("2", "2026-11-17T08:00:00Z")
        val row3 = createRow("3", "2026-11-16T15:00:00Z")
        val row4 = createRow("4", "2026-11-15T20:00:00Z")

        val groups = EventDayGrouping.groupByDay(listOf(row3, row1, row4, row2), zoneId)

        assertEquals(3, groups.size)
        assertEquals(LocalDate.of(2026, 11, 17), groups[0].date)
        assertEquals(listOf("1", "2"), groups[0].rows.map { it.txId })

        assertEquals(LocalDate.of(2026, 11, 16), groups[1].date)
        assertEquals(listOf("3"), groups[1].rows.map { it.txId })

        assertEquals(LocalDate.of(2026, 11, 15), groups[2].date)
        assertEquals(listOf("4"), groups[2].rows.map { it.txId })
    }

    @Test
    fun handlesEmptyList() {
        val groups = EventDayGrouping.groupByDay(emptyList(), zoneId)
        assertTrue(groups.isEmpty())
    }

    @Test
    fun respectsZoneOffsetForDayBoundary() {
        // 23:30 UTC on Nov 16 is 03:00 on Nov 17 in Tehran (+03:30)
        val tehranZone = ZoneId.of("+03:30")
        val row = createRow("tehran", "2026-11-16T23:30:00Z")

        val groups = EventDayGrouping.groupByDay(listOf(row), tehranZone)
        assertEquals(1, groups.size)
        assertEquals(LocalDate.of(2026, 11, 17), groups[0].date)
    }
}
