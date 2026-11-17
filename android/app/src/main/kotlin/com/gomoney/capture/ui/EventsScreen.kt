package com.gomoney.capture.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gomoney.capture.R
import com.gomoney.capture.capture.ScanUiState
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.ui.theme.AmountFormat
import com.gomoney.capture.ui.theme.CaptureThemeTokens
import com.gomoney.capture.ui.theme.EventDayGrouping
import com.gomoney.capture.ui.theme.StatusChip
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
    val currency: String = "",
    val destinationAccountId: Int? = null,
    val categoryId: Int? = null,
    val assignmentSyncState: String = "synced",
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
                state = delivery?.state ?: "sent",
                errorCategory = delivery?.errorCategory,
                txId = tx.id,
                type = tx.type,
                description = tx.description,
                userMemo = tx.userMemo,
                memoSyncState = tx.memoSyncState,
                currency = tx.currency,
                destinationAccountId = tx.destinationAccountId,
                categoryId = tx.categoryId,
                assignmentSyncState = tx.assignmentSyncState,
            )
        }
        val txIds = txs.map { it.id }.toSet()
        val errorRows = deliveries.filter { it.id !in txIds }.map { delivery ->
            val raw = rawById[delivery.sourceEventId]
            EventRow(
                amountMinor = 0L,
                bank = raw?.sourcePackage ?: "Unknown",
                txAt = raw?.postedAt ?: java.time.Instant.now().toString(),
                state = delivery.state,
                errorCategory = delivery.errorCategory ?: "parse_error",
                txId = delivery.id,
                description = delivery.errorDetail.orEmpty(),
            )
        }
        EventsState(rows = (txRows + errorRows).sortedByDescending { it.txAt })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventsState())
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EventsScreen(
    state: EventsViewModel.EventsState,
    onRetry: (String) -> Unit = {},
    onClearProcessed: () -> Unit = {},
    onClearAll: () -> Unit = {},
    scanState: ScanUiState = ScanUiState.Idle,
    onScan: () -> Unit = {},
    categories: List<com.gomoney.capture.storage.ServerCategory> = emptyList(),
    onOpenDetail: (String) -> Unit = {},
    onSyncSelected: (Set<String>) -> Unit = {},
    onDeleteSelected: (Set<String>) -> Unit = {},
) {
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    val liveIds = state.rows.map { it.txId }.toSet()
    if (!liveIds.containsAll(selectedIds)) {
        selectedIds = selectedIds.intersect(liveIds)
    }

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

    if (showDeleteSelectedDialog) {
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text("Delete $count selected event(s)?") },
            text = {
                Text(
                    "This permanently deletes the $count selected event(s), including any " +
                        "queued or failed deliveries that have not reached Go Money. " +
                        "Unselected events are kept. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selectedIds
                    showDeleteSelectedDialog = false
                    selectedIds = emptySet()
                    isSelectionMode = false
                    onDeleteSelected(ids)
                }) { Text("Delete selected") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) { Text("Cancel") }
            },
        )
    }

    val context = LocalContext.current
    val dayGroups = remember(state.rows) { EventDayGrouping.groupByDay(state.rows) }

    Column(modifier = Modifier.fillMaxSize()) {
        // App bar / Selection Header
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSelectionMode) {
                    Text(
                        text = "${selectedIds.size} selected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {
                            selectedIds = if (selectedIds.size == liveIds.size) emptySet() else liveIds
                        }) {
                            Text(if (selectedIds.size == liveIds.size) "Deselect" else "All")
                        }
                        if (selectedIds.isNotEmpty()) {
                            TextButton(onClick = { onSyncSelected(selectedIds) }) {
                                Text("Sync")
                            }
                            TextButton(onClick = { showDeleteSelectedDialog = true }) {
                                Text("Delete")
                            }
                        }
                        TextButton(onClick = {
                            isSelectionMode = false
                            selectedIds = emptySet()
                        }) {
                            Text("Done")
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.nav_events),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = onScan,
                            enabled = scanState !is ScanUiState.Running,
                        ) {
                            Text(stringResource(R.string.events_scan_notifications))
                        }
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.events_select)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        isSelectionMode = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.events_clear_processed)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onClearProcessed()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.events_clear_all)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showClearAllDialog = true
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Contract §3: Scan banner is the first LazyColumn item
            if (scanState !is ScanUiState.Idle) {
                item(key = "scan-banner") {
                    ScanBanner(
                        state = scanState,
                        onOpenNotificationSettings = { openNotificationAccessSettings(context) },
                    )
                }
            }

            // Empty state
            if (state.rows.isEmpty()) {
                item(key = "empty-state") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.events_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.events_empty_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onScan,
                            enabled = scanState !is ScanUiState.Running,
                        ) {
                            Text(stringResource(R.string.events_scan_notifications))
                        }
                    }
                }
            } else {
                dayGroups.forEach { group ->
                    stickyHeader(key = "header-${group.date}") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            Text(
                                text = group.headerLabel,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }

                    items(group.rows, key = { it.txId }) { row ->
                        EventCard(
                            row = row,
                            onRetry = onRetry,
                            onOpenDetail = onOpenDetail,
                            selected = row.txId in selectedIds,
                            isSelectionMode = isSelectionMode,
                            onToggleSelected = { checked ->
                                selectedIds = if (checked) selectedIds + row.txId else selectedIds - row.txId
                            },
                            onLongClick = {
                                if (!isSelectionMode) {
                                    isSelectionMode = true
                                    selectedIds = setOf(row.txId)
                                }
                            },
                        )
                    }
                }
                item(key = "footer-spacing") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ScanBanner(
    state: ScanUiState,
    onOpenNotificationSettings: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                ScanUiState.Idle -> Unit
                ScanUiState.Running -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
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
                    Text(
                        stringResource(R.string.notification_access_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onOpenNotificationSettings) {
                        Text(stringResource(R.string.notification_access_open_settings))
                    }
                }
                ScanUiState.Unavailable -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Can't read active notifications. Verify notification access in system settings.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onOpenNotificationSettings) {
                        Text(stringResource(R.string.notification_access_open_settings))
                    }
                }
            }
        }
    }
}

private fun scanResultText(result: ScanUiState.Result): String = when {
    result.examined == 0 -> "No active notifications."
    result.added == 0 -> "No new events found · Examined ${result.examined} · Skipped ${result.skipped}"
    else -> "Examined ${result.examined} · Added ${result.added} · Skipped ${result.skipped}"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventCard(
    row: EventRow,
    onRetry: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    selected: Boolean,
    isSelectionMode: Boolean,
    onToggleSelected: (Boolean) -> Unit,
    onLongClick: () -> Unit,
) {
    val extendedColors = CaptureThemeTokens.extendedColors
    val isExpense = row.type.equals("expense", ignoreCase = true)
    val sign = if (isExpense) "-" else "+"
    val amountColor = if (isExpense) extendedColors.expense else extendedColors.income
    val formattedAmount = if (row.amountMinor == 0L && row.errorCategory != null) {
        "—"
    } else {
        AmountFormat.formatAmountMinor(row.amountMinor, row.currency)
    }

    val timeLabel = runCatching {
        OffsetDateTime.parse(row.txAt).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }.getOrElse { row.txAt.takeLast(8) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onToggleSelected(!selected)
                    } else {
                        onOpenDetail(row.txId)
                    }
                },
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = onToggleSelected,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (formattedAmount == "—") "—" else "$sign$formattedAmount",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (formattedAmount == "—") MaterialTheme.colorScheme.onSurface else amountColor,
                    )
                    StatusChip(
                        state = row.state,
                        errorCategory = row.errorCategory,
                        hasNormalizedTx = row.amountMinor > 0L || row.errorCategory == null,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${row.bank} · $timeLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val preview = row.userMemo?.ifBlank { null } ?: row.description
                if (preview.isNotBlank()) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (row.state == "failed") {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { onRetry(row.txId) },
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text(stringResource(R.string.events_retry))
                    }
                }
            }
        }
    }
}
