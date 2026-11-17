package com.gomoney.capture.ui.theme

import com.gomoney.capture.ui.EventRow
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class DayGroup(
    val date: LocalDate,
    val headerLabel: String,
    val rows: List<EventRow>,
)

object EventDayGrouping {

    private fun parseLocalDate(txAt: String, zoneId: ZoneId): LocalDate {
        return runCatching {
            OffsetDateTime.parse(txAt).atZoneSameInstant(zoneId).toLocalDate()
        }.recoverCatching {
            Instant.parse(txAt).atZone(zoneId).toLocalDate()
        }.recoverCatching {
            LocalDate.parse(txAt.take(10))
        }.getOrElse {
            LocalDate.now(zoneId)
        }
    }

    fun formatHeaderLabel(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val today = LocalDate.now(zoneId)
        return when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }
    }

    fun groupByDay(
        events: List<EventRow>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<DayGroup> {
        if (events.isEmpty()) return emptyList()

        // Group rows preserving intra-day order (rows are already sorted by txAt desc)
        val map = linkedMapOf<LocalDate, MutableList<EventRow>>()
        for (event in events) {
            val date = parseLocalDate(event.txAt, zoneId)
            map.getOrPut(date) { mutableListOf() }.add(event)
        }

        // Sort days newest first
        return map.entries
            .sortedByDescending { it.key }
            .map { (date, rows) ->
                DayGroup(
                    date = date,
                    headerLabel = formatHeaderLabel(date, zoneId),
                    rows = rows,
                )
            }
    }
}
