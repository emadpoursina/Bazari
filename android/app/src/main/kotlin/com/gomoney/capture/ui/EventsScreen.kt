package com.gomoney.capture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gomoney.capture.storage.AppDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Recent-events list (US1, T021): amount, bank, type, time, description and
 * delivery state per row — backed by real Room Flows so captured/delivery
 * state can be verified on-device. Rows are normalized transactions joined
 * with their delivery record; parse errors (no normalized transaction) are
 * shown with the sanitized error detail, never raw message text (FR-023).
 */
data class EventRow(
    val amountMinor: Long,
    val bank: String,
    val txAt: String,
    val state: String,
    val errorCategory: String?,
    val txId: String,
    val type: String = "expense",
    val description: String = "",
    val userMemo: String? = null,
    val memoSyncState: String = "synced",
)

class EventsViewModel(db: AppDatabase) : ViewModel() {

    data class EventsState(val rows: List<EventRow> = emptyList())

    val state: StateFlow<EventsState> = combine(
        db.normalizedTransactionDao().observeRecent(200),
        db.deliveryRecordDao().observeRecent(200),
        db.rawEventDao().observeAll(),
    ) { txs, deliveries, raws ->
        val deliveryById = deliveries.associateBy { it.id }
        val rawById = raws.associateBy { it.id }

        val txRows = txs.map { tx ->
            val delivery = deliveryById[tx.id]
            EventRow(
                amountMinor = tx.amountMinor,
                bank = tx.bank,
                txAt = tx.txAt,
                state = delivery?.state ?: "parsed",
                errorCategory = delivery?.errorCategory,
                txId = tx.id,
                type = tx.type,
                description = tx.description,
                userMemo = tx.userMemo,
                memoSyncState = tx.memoSyncState,
            )
        }
        // Parse-error rows: delivery record without a normalized transaction.
        val txIds = txs.map { it.id }.toSet()
        val errorRows = deliveries.filter { it.id !in txIds }.map { delivery ->
            val raw = rawById[delivery.sourceEventId]
            EventRow(
                amountMinor = 0,
                bank = raw?.sourcePackage ?: "unknown",
                txAt = raw?.postedAt ?: delivery.lastAttemptAt.orEmpty(),
                state = delivery.state,
                errorCategory = delivery.errorCategory ?: "parse_error",
                txId = delivery.id,
                description = delivery.errorDetail.orEmpty(),
            )
        }
        EventsState(rows = (txRows + errorRows).sortedByDescending { it.txAt })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventsState())
}

@Composable
fun EventsScreen(
    state: EventsViewModel.EventsState,
    onRetry: (String) -> Unit = {},
    onClearProcessed: () -> Unit = {},
    onClearAll: () -> Unit = {},
    onSaveMemo: (String, String) -> Unit = { _, _ -> },
) {
    if (state.rows.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("No captured transactions yet.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    var showClearAllDialog by remember { mutableStateOf(false) }
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear all events?") },
            text = {
                Text(
                    "This permanently deletes every captured event, including queued and failed " +
                        "deliveries that have not reached Go Money. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllDialog = false
                    onClearAll()
                }) { Text("Clear all") }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) { Text("Cancel") }
            },
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClearProcessed) { Text("Clear processed") }
                OutlinedButton(onClick = { showClearAllDialog = true }) { Text("Clear all") }
            }
        }
        items(state.rows, key = { it.txId }) { row ->
            EventCard(row, onRetry, onSaveMemo)
        }
    }
}

@Composable
private fun EventCard(
    row: EventRow,
    onRetry: (String) -> Unit,
    onSaveMemo: (String, String) -> Unit,
) {
    var editingMemo by remember(row.txId) { mutableStateOf(false) }
    var memoDraft by remember(row.txId, row.userMemo) { mutableStateOf(row.userMemo.orEmpty()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (row.amountMinor == 0L) "—" else "${row.amountMinor} IRR",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(text = row.state.uppercase(), style = MaterialTheme.typography.labelMedium)
            }
            Text(text = "${row.bank} · ${row.type}", style = MaterialTheme.typography.bodySmall)
            if (row.description.isNotBlank()) {
                Text(text = row.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            Text(text = formatTime(row.txAt), style = MaterialTheme.typography.bodySmall)
            row.errorCategory?.let {
                Text(text = "category: $it", style = MaterialTheme.typography.labelSmall)
            }

            if (row.amountMinor > 0L) {
                if (!row.userMemo.isNullOrBlank()) {
                    Text("Note: ${row.userMemo}", style = MaterialTheme.typography.bodyMedium)
                }
                if (row.memoSyncState == "pending") {
                    val pendingLabel = if (row.userMemo.isNullOrBlank()) {
                        "Note removal saved on this phone · waiting to sync"
                    } else {
                        "Saved on this phone · waiting to sync"
                    }
                    Text(pendingLabel, style = MaterialTheme.typography.labelSmall)
                }
                if (editingMemo) {
                    OutlinedTextField(
                        value = memoDraft,
                        onValueChange = { if (it.length <= MAX_MEMO_LENGTH) memoDraft = it },
                        label = { Text("What was this for?") },
                        placeholder = { Text("e.g. groceries, lunch, taxi") },
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Saved on this phone and synced to Go Money · ${memoDraft.length}/$MAX_MEMO_LENGTH") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            onSaveMemo(row.txId, memoDraft)
                            editingMemo = false
                        }) {
                            Text("Save note")
                        }
                        OutlinedButton(onClick = { editingMemo = false }) {
                            Text("Cancel")
                        }
                    }
                } else {
                    OutlinedButton(onClick = { editingMemo = true }) {
                        Text(if (row.userMemo.isNullOrBlank()) "Add note" else "Edit note")
                    }
                }
            }

            if (row.state == "failed") {
                OutlinedButton(onClick = { onRetry(row.txId) }) {
                    Text("Retry delivery")
                }
            }
        }
    }
}

private const val MAX_MEMO_LENGTH = 200

private fun formatTime(txAt: String): String = runCatching {
    OffsetDateTime.parse(txAt).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrDefault(txAt)
