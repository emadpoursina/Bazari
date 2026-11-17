package com.gomoney.capture.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gomoney.capture.R
import com.gomoney.capture.storage.DeliveryRecord
import com.gomoney.capture.storage.NormalizedTransaction
import com.gomoney.capture.storage.ServerAccount
import com.gomoney.capture.storage.ServerCategory
import com.gomoney.capture.storage.ServerConfiguration
import com.gomoney.capture.ui.theme.AmountFormat
import com.gomoney.capture.ui.theme.CaptureThemeTokens
import com.gomoney.capture.ui.theme.StatusChip
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** 005 T033/FR-022: currency is editable only before delivery begins. */
fun currencyEditable(deliveryState: String?): Boolean {
    val state = deliveryState?.lowercase()
    return state == "held" || state == "queued" || state == "failed"
}

/** Distinct non-blank currencies from the cached server accounts (005 T032). */
fun List<ServerAccount>.distinctCurrencies(): List<String> =
    map { it.currency }.filter { it.isNotBlank() }.distinct()

/**
 * Event detail screen (US3, T024, T025, contracts/event-detail.md):
 * Focused transaction story: Back → Identity → Send assignment (one confirm) → Note → Send/Retry.
 * Parser internals hidden unless debug mode is active.
 */
@Composable
fun EventDetailScreen(
    tx: NormalizedTransaction?,
    record: DeliveryRecord?,
    config: ServerConfiguration,
    onRetry: (String) -> Unit,
    accounts: List<ServerAccount> = emptyList(),
    categories: List<ServerCategory> = emptyList(),
    onSaveAssignment: (String, Int?, Int?) -> Unit = { _, _, _ -> },
    onSaveCurrency: (String, String) -> Unit = { _, _ -> },
    onSaveMemo: (txId: String, memo: String) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
    catalogUnavailable: Boolean = false,
    onRefreshCatalogs: () -> Unit = {},
    onOpenDebug: (() -> Unit)? = null,
) {
    BackHandler(onBack = onBack)

    var destinationAccountId by remember(tx?.id) { mutableStateOf(tx?.destinationAccountId) }
    var categoryId by remember(tx?.id) { mutableStateOf(tx?.categoryId) }
    var accountMenuOpen by remember { mutableStateOf(false) }
    var categoryMenuOpen by remember { mutableStateOf(false) }
    var currencyMenuOpen by remember { mutableStateOf(false) }
    var memoDraft by remember(tx?.id, tx?.userMemo) { mutableStateOf(tx?.userMemo.orEmpty()) }

    val extendedColors = CaptureThemeTokens.extendedColors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Back navigation
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.height(48.dp),
            ) {
                Text(stringResource(R.string.event_detail_back))
            }
            Text(
                text = stringResource(R.string.event_detail_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Identity section (amount, type, bank, time, status chip)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val isExpense = tx?.type?.equals("expense", ignoreCase = true) ?: true
                val sign = if (isExpense) "-" else "+"
                val typeLabel = if (isExpense) stringResource(R.string.event_detail_type_expense) else stringResource(R.string.event_detail_type_income)
                val amountColor = if (isExpense) extendedColors.expense else extendedColors.income
                val formattedAmount = AmountFormat.formatAmountMinor(tx?.amountMinor ?: 0L, tx?.currency)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$sign$formattedAmount",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = amountColor,
                    )
                    StatusChip(
                        state = record?.state ?: "sent",
                        errorCategory = record?.errorCategory,
                        hasNormalizedTx = tx != null,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = typeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = amountColor,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = tx?.bank ?: "Unknown source",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    tx?.accountHint?.ifBlank { null }?.let { hint ->
                        Text(
                            text = "($hint)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val humanTime = tx?.txAt?.let { rawTime ->
                    runCatching {
                        OffsetDateTime.parse(rawTime).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
                    }.getOrDefault(rawTime)
                } ?: "-"
                Text(
                    text = humanTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Send assignment section
        tx?.let { transaction ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.event_detail_assignment_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    // Destination account
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = "Destination account", style = MaterialTheme.typography.labelMedium)
                        Box {
                            OutlinedButton(
                                onClick = { accountMenuOpen = true },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) {
                                Text(accounts.firstOrNull { it.id == destinationAccountId }?.label ?: "Default")
                            }
                            DropdownMenu(
                                expanded = accountMenuOpen,
                                onDismissRequest = { accountMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Default") },
                                    onClick = { destinationAccountId = null; accountMenuOpen = false },
                                )
                                accounts.forEach { account ->
                                    DropdownMenuItem(
                                        text = { Text(account.label) },
                                        onClick = { destinationAccountId = account.id; accountMenuOpen = false },
                                    )
                                }
                            }
                        }
                    }

                    // Category
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = "Category", style = MaterialTheme.typography.labelMedium)
                        if (catalogUnavailable) {
                            Text(
                                "Category list unavailable — last known list shown",
                                style = MaterialTheme.typography.bodySmall,
                                color = extendedColors.warning,
                            )
                            OutlinedButton(onClick = onRefreshCatalogs) {
                                Text("Refresh categories")
                            }
                        }
                        Box {
                            OutlinedButton(
                                onClick = { categoryMenuOpen = true },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) {
                                Text(categories.firstOrNull { it.id == categoryId }?.label ?: "Default")
                            }
                            DropdownMenu(
                                expanded = categoryMenuOpen,
                                onDismissRequest = { categoryMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Default") },
                                    onClick = { categoryId = null; categoryMenuOpen = false },
                                )
                                categories.forEach { category ->
                                    DropdownMenuItem(
                                        text = { Text(category.label) },
                                        onClick = { categoryId = category.id; categoryMenuOpen = false },
                                    )
                                }
                            }
                        }
                    }

                    // Currency (gated by delivery state)
                    val editable = currencyEditable(record?.state)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = "Currency", style = MaterialTheme.typography.labelMedium)
                        if (editable) {
                            Box {
                                OutlinedButton(
                                    onClick = { currencyMenuOpen = true },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                ) {
                                    Text(transaction.currency.ifBlank { "Select currency" })
                                }
                                DropdownMenu(
                                    expanded = currencyMenuOpen,
                                    onDismissRequest = { currencyMenuOpen = false },
                                ) {
                                    accounts.distinctCurrencies().forEach { currency ->
                                        DropdownMenuItem(
                                            text = { Text(currency) },
                                            onClick = {
                                                onSaveCurrency(transaction.id, currency)
                                                currencyMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = transaction.currency.ifBlank { "unknown" },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    // Single confirm control for assignment
                    Button(
                        onClick = { onSaveAssignment(transaction.id, destinationAccountId, categoryId) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text(stringResource(R.string.event_detail_save_assignment))
                    }

                    if (transaction.assignmentSyncState == "pending") {
                        Text(
                            "Assignment saved on this phone · waiting to sync",
                            style = MaterialTheme.typography.labelSmall,
                            color = extendedColors.warning,
                        )
                    }

                    val chosenGone = transaction.categoryId != null &&
                        categories.none { it.id == transaction.categoryId }
                    if (chosenGone) {
                        Text(
                            "The saved category is no longer on the server — pick a new one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = extendedColors.warning,
                        )
                    }
                }
            }
        }

        // Note section (always visible, 009 contract)
        tx?.let { transaction ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.event_detail_note_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = memoDraft,
                        onValueChange = { if (it.length <= EventActions.MAX_MEMO_LENGTH) memoDraft = it },
                        label = { Text("What was this for?") },
                        placeholder = { Text("e.g. groceries, lunch, taxi") },
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text(
                                "Saved on this phone and synced to Go Money · " +
                                    "${memoDraft.length}/${EventActions.MAX_MEMO_LENGTH}",
                            )
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onSaveMemo(transaction.id, memoDraft) },
                            modifier = Modifier.height(48.dp),
                        ) {
                            Text(stringResource(R.string.event_detail_note_save))
                        }
                        OutlinedButton(
                            onClick = { memoDraft = transaction.userMemo.orEmpty() },
                            modifier = Modifier.height(48.dp),
                        ) {
                            Text(stringResource(R.string.event_detail_note_cancel))
                        }
                    }
                    if (transaction.memoSyncState == "pending") {
                        val pendingLabel = if (transaction.userMemo.isNullOrBlank()) {
                            "Note removal saved on this phone · waiting to sync"
                        } else {
                            "Saved on this phone · waiting to sync"
                        }
                        Text(
                            pendingLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = extendedColors.warning,
                        )
                    }
                }
            }
        }

        // Send / Retry section (failed sends)
        if (record?.state == "failed") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = extendedColors.error.copy(alpha = 0.08f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chip_couldnt_send),
                        style = MaterialTheme.typography.titleMedium,
                        color = extendedColors.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                    record.errorDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Button(
                        onClick = { record.id.let(onRetry) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text(stringResource(R.string.events_retry))
                    }
                }
            }
        }

        // Secondary Debug control (gated by config.debugModeEnabled)
        if (config.debugModeEnabled && onOpenDebug != null) {
            OutlinedButton(
                onClick = onOpenDebug,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(stringResource(R.string.event_detail_debug))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
