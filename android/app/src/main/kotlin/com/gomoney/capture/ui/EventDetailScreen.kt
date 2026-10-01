package com.gomoney.capture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gomoney.capture.storage.DeliveryRecord
import com.gomoney.capture.storage.NormalizedTransaction
import com.gomoney.capture.storage.ServerAccount
import com.gomoney.capture.storage.ServerCategory
import com.gomoney.capture.storage.ServerConfiguration

/**
 * Event detail (US3/US4, T050, FR-017/018/019; 005 FR-004/022): sanitized
 * detail only — amount, bank, time, delivery state, error category — plus
 * destination-account and category drop-downs populated from the cached server
 * catalog. Raw text is hidden unless debug mode is on (delegated to the US7
 * DebugEventScreen).
 *
 * 005: the amount is labeled with the transaction's own currency (never a
 * hardcoded rial label; unknown currency shows without a code), and currency
 * is editable — as a dropdown of the server accounts' distinct non-blank
 * currencies — only while the row is local and undelivered (`held`, `queued`,
 * `failed`); `sending`/`sent` is read-only (FR-022). Assignment saving is
 * independent of currency and must not touch it (FR-019).
 */

/** 005 T033/FR-022: currency is editable only before delivery begins. */
fun currencyEditable(deliveryState: String?): Boolean {
    val state = deliveryState?.lowercase()
    return state == "held" || state == "queued" || state == "failed"
}

/** Distinct non-blank currencies from the cached server accounts (005 T032). */
fun List<ServerAccount>.distinctCurrencies(): List<String> =
    map { it.currency }.filter { it.isNotBlank() }.distinct()

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
) {
    var destinationAccountId by remember(tx?.id) { mutableStateOf(tx?.destinationAccountId) }
    var categoryId by remember(tx?.id) { mutableStateOf(tx?.categoryId) }
    var accountMenuOpen by remember { mutableStateOf(false) }
    var categoryMenuOpen by remember { mutableStateOf(false) }
    var currencyMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "Event detail", style = MaterialTheme.typography.titleLarge)
        // 005 FR-004: the transaction's own currency, never a rial constant;
        // unknown (blank) currency shows the bare amount.
        val currencyLabel = tx?.currency?.takeIf { it.isNotBlank() } ?: "unknown currency"
        Text(text = "${tx?.amountMinor ?: 0} $currencyLabel", style = MaterialTheme.typography.headlineSmall)
        Text(text = "Bank: ${tx?.bank ?: "-"} · account ${tx?.accountHint ?: "-"}")
        Text(text = "Time: ${tx?.txAt ?: "-"}")
        Text(text = "Delivery state: ${record?.state ?: "-"}")
        record?.errorCategory?.let { Text(text = "Error category: $it") }
        record?.errorDetail?.let { Text(text = "Detail: $it") } // sanitized only (FR-023)
        Text(text = "Parser: ${tx?.parserName ?: "-"} (confidence ${tx?.confidence ?: "-"})")

        // Destination account + category drop-downs (FR-017/018/021).
        Text(text = "Destination account", style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { accountMenuOpen = true }) {
                Text(accounts.firstOrNull { it.id == destinationAccountId }?.label ?: "Default")
            }
            DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
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

        Text(text = "Category", style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { categoryMenuOpen = true }) {
                Text(categories.firstOrNull { it.id == categoryId }?.label ?: "Default")
            }
            DropdownMenu(expanded = categoryMenuOpen, onDismissRequest = { categoryMenuOpen = false }) {
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

        // 005 T032/FR-022: currency edit gated by delivery state. Held/queued/
        // failed → dropdown of distinct non-blank account currencies (local
        // update only; editing `held` does NOT move held → queued). Sending/sent
        // → read-only text.
        tx?.let { transaction ->
            val editable = currencyEditable(record?.state)
            Text(text = "Currency", style = MaterialTheme.typography.labelLarge)
            if (editable) {
                Box {
                    OutlinedButton(onClick = { currencyMenuOpen = true }) {
                        Text(transaction.currency.ifBlank { "Select currency" })
                    }
                    DropdownMenu(expanded = currencyMenuOpen, onDismissRequest = { currencyMenuOpen = false }) {
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
                Text(text = transaction.currency.ifBlank { "unknown" }, style = MaterialTheme.typography.bodyMedium)
            }
        }

        tx?.let { transaction ->
            Button(onClick = { onSaveAssignment(transaction.id, destinationAccountId, categoryId) }) {
                Text("Save assignment")
            }
        }

        if (record?.state == "failed") {
            Button(onClick = { record?.id?.let(onRetry) }) {
                Text("Retry delivery")
            }
        }
    }
}
