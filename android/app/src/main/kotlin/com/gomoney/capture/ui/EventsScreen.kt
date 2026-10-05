package com.gomoney.capture.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gomoney.capture.capture.ScanUiState
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
    // 005 FR-004: the transaction's own currency; blank = unknown (no label).
    val currency: String = "",
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
                currency = tx.currency,
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
    // 006 FR-001/005: scan control + inline banner (contracts/scan-coordination.md §4).
    scanState: ScanUiState = ScanUiState.Idle,
    onScan: () -> Unit = {},
) {
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
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Contract §4: the banner is the FIRST item, directly above the row.
        if (scanState !is ScanUiState.Idle) {
            item(key = "scan-banner") {
                ScanBanner(
                    state = scanState,
                    onOpenNotificationSettings = { openNotificationAccessSettings(context) },
                )
            }
        }
        item(key = "event-actions") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Three controls must fit without clipping (FR-001).
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onClearProcessed) { Text("Clear processed") }
                OutlinedButton(onClick = { showClearAllDialog = true }) { Text("Clear all") }
                OutlinedButton(
                    onClick = onScan,
                    // FR-008: the control shows progress and disables while running.
                    enabled = scanState !is ScanUiState.Running,
                ) { Text("Scan notifications") }
            }
        }
        // Contract §4: the action row (and scan control) render even when the
        // list is empty — P1 must be testable with zero events.
        if (state.rows.isEmpty()) {
            item(key = "empty-state") {
                Text("No captured transactions yet.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            items(state.rows, key = { it.txId }) { row ->
                EventCard(row, onRetry, onSaveMemo)
            }
        }
    }
}

/**
 * Inline scan banner (006 FR-005/006/008, contracts/scan-coordination.md §2):
 * progress while running, examined/added/skipped wording on completion, and
 * actionable settings guidance when the scan cannot run at all.
 */
@Composable
private fun ScanBanner(
    state: ScanUiState,
    onOpenNotificationSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                ScanUiState.Idle -> Unit
                ScanUiState.Running -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        "Scanning active notifications…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is ScanUiState.Result -> Text(
                    scanResultText(state),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ScanUiState.Blocked -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // FR-006, Q5: clear message + a button that opens the
                    // system notification-access screen. No scan started.
                    Text(
                        "Notification-read access is missing, so scanning can't run. " +
                            "Grant this app access to read notifications in system settings, " +
                            "then press Scan notifications again.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SettingsButton(onOpenNotificationSettings)
                }
                ScanUiState.Unavailable -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Research R9: same guidance button + a "try again" hint
                    // (re-enabling access re-binds the listener).
                    Text(
                        "Can't read the active notifications right now. Check that notification " +
                            "access for this app is enabled in system settings, then try again.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SettingsButton(onOpenNotificationSettings)
                    Text(
                        "Tip: toggle notification access off and on, then try again.",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text("Open notification access settings") }
}

/**
 * Result wording (contracts/scan-coordination.md §2): empty shade, nothing
 * new, or the full examined/added/skipped counts.
 */
private fun scanResultText(result: ScanUiState.Result): String = when {
    result.examined == 0 -> "No active notifications."
    result.added == 0 -> "No new events found · Examined ${result.examined} · Skipped ${result.skipped}"
    else -> "Examined ${result.examined} · Added ${result.added} · Skipped ${result.skipped}"
}

/** FR-006: deep link to the system notification-access screen with a fallback. */
private fun openNotificationAccessSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
                    // 005 FR-003/004: never a hardcoded rial label. Blank
                    // (unknown) currency shows the bare amount.
                    text = if (row.amountMinor == 0L) "—"
                    else row.currency.takeIf { it.isNotBlank() }?.let { "${row.amountMinor} $it" }
                        ?: "${row.amountMinor}",
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
