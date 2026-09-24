package com.gomoney.capture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Minimal recent-events list (US1, T021): amount, bank, time, delivery state
 * per row — sufficient to verify the US1 independent test.
 */
data class EventRow(
    val amountMinor: Long,
    val bank: String,
    val txAt: String,
    val state: String,
    val errorCategory: String?,
    val txId: String,
)

@Composable
fun EventsScreen(rows: List<EventRow>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = { it.txId }) { row ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${row.amountMinor} IRR",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(text = row.state.uppercase(), style = MaterialTheme.typography.labelMedium)
                    }
                    Text(text = row.bank, style = MaterialTheme.typography.bodySmall)
                    Text(text = formatTime(row.txAt), style = MaterialTheme.typography.bodySmall)
                    row.errorCategory?.let {
                        Text(text = "category: $it", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun formatTime(txAt: String): String = runCatching {
    OffsetDateTime.parse(txAt).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrDefault(txAt)
