package com.gomoney.capture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gomoney.capture.R
import com.gomoney.capture.source.MatchResult
import com.gomoney.capture.source.ValidationError
import com.gomoney.capture.storage.ServerAccount
import com.gomoney.capture.storage.TransactionSource
import com.gomoney.capture.sync.CatalogRepository

/**
 * Source editor (US1/US2, FR-002/007/012/013/027; 005 FR-001/004): name,
 * identifier, channel, a fill-in-the-blank template, income/expense keywords,
 * the server-account selector, and the optional offline Test preview.
 *
 * 005: the selected account's currency is shown automatically (no separate
 * currency control — FR-001); accounts with a blank currency cannot be bound
 * (FR-006) and selecting one never replaces the previous binding; the Test
 * preview labels the amount with the bound account's currency, or shows it
 * without a rial label while unbound (FR-004/018).
 */
@Composable
fun SourceEditorScreen(
    initial: TransactionSource,
    accounts: List<ServerAccount>,
    catalogUnavailable: Boolean,
    errors: List<ValidationError>,
    testResult: MatchResult?,
    onSave: (TransactionSource) -> Unit,
    onCancel: () -> Unit,
    onTest: (String, String, String, String) -> Unit,
    onRefreshAccounts: () -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var identifier by remember(initial.id) { mutableStateOf(initial.identifier) }
    var channel by remember(initial.id) { mutableStateOf(initial.channel) }
    var template by remember(initial.id) { mutableStateOf(initial.template) }
    var incomeKeywords by remember(initial.id) { mutableStateOf(initial.incomeKeywords) }
    var expenseKeywords by remember(initial.id) { mutableStateOf(initial.expenseKeywords) }
    var boundAccountId by remember(initial.id) { mutableStateOf(initial.boundAccountId) }
    var boundAccountLabel by remember(initial.id) { mutableStateOf(initial.boundAccountLabel) }
    var sample by remember(initial.id) { mutableStateOf("") }
    var accountMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (initial.name.isBlank()) "Add source" else "Edit source",
            style = MaterialTheme.typography.titleLarge,
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = identifier,
            onValueChange = { identifier = it },
            label = { Text("Identifier (package id or SMS sender)") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Channel", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = channel.equals("notification", ignoreCase = true),
                onClick = { channel = "notification" },
                label = { Text("Notification") },
            )
            FilterChip(
                selected = channel.equals("sms", ignoreCase = true),
                onClick = { channel = "sms" },
                label = { Text("SMS") },
            )
        }

        OutlinedTextField(
            value = template,
            onValueChange = { template = it },
            label = { Text("Template") },
            supportingText = { Text("Paste a real message, then mark {amount} and {direction}.") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = incomeKeywords,
            onValueChange = { incomeKeywords = it },
            label = { Text("Income keywords") },
            supportingText = { Text("Separate words with commas or new lines") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = expenseKeywords,
            onValueChange = { expenseKeywords = it },
            label = { Text("Expense keywords") },
            supportingText = { Text("Separate words with commas or new lines") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Server account (optional)", style = MaterialTheme.typography.labelLarge)
        val selectedAccount = accounts.firstOrNull { it.id == boundAccountId }
        Box {
            OutlinedButton(onClick = { accountMenuOpen = true; onRefreshAccounts() }) {
                Text(selectedAccount?.let { "${it.label} (${it.currency})" } ?: "No account bound")
            }
            DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("No account bound") },
                    onClick = { boundAccountId = null; boundAccountLabel = null; accountMenuOpen = false },
                )
                accounts.forEach { account ->
                    val bindable = account.currency.isNotBlank()
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (bindable) "${account.label} (${account.currency})"
                                else "${account.label} — no currency (cannot bind)",
                            )
                        },
                        enabled = bindable,
                        onClick = {
                            // FR-006: only selectable accounts change the binding.
                            boundAccountId = account.id
                            boundAccountLabel = account.label
                            accountMenuOpen = false
                        },
                    )
                }
            }
        }
        // FR-006: reject saving a binding to a blank-currency account.
        com.gomoney.capture.source.SourceValidator.validateBinding(boundAccountId, accounts)?.let {
            Text(text = it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (catalogUnavailable) {
            Text("Account list unavailable — last known list shown", style = MaterialTheme.typography.bodySmall)
        } else if (accounts.isEmpty()) {
            Text("No accounts available", style = MaterialTheme.typography.bodySmall)
        }
        if (CatalogRepository.isBindingStale(boundAccountId, accounts)) {
            Text(
                text = stringResource(R.string.sources_binding_stale),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedTextField(
            value = sample,
            onValueChange = { sample = it },
            label = { Text("Sample message to test") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { onTest(template, sample, incomeKeywords, expenseKeywords) }) {
            Text("Test")
        }
        when (val result = testResult) {
            is MatchResult.Success -> {
                // 005 FR-004/018: label the amount with the bound account's
                // currency; unknown (no rial label) while unbound.
                val currencyLabel = selectedAccount?.currency?.takeIf { it.isNotBlank() } ?: "unknown currency"
                Text("Detected ${result.direction.wire} of ${result.amountMinor} $currencyLabel")
            }
            is MatchResult.Failure ->
                Text("Could not parse: ${result.reason}")
            null -> Unit
        }

        if (errors.isNotEmpty()) {
            errors.forEach { error ->
                Text("${error.field}: ${error.message}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onSave(
                    initial.copy(
                        name = name,
                        identifier = identifier,
                        channel = channel,
                        template = template,
                        incomeKeywords = incomeKeywords,
                        expenseKeywords = expenseKeywords,
                        boundAccountId = boundAccountId,
                        boundAccountLabel = boundAccountLabel,
                    ),
                )
            }) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
