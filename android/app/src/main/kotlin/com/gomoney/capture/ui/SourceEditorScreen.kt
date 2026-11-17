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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gomoney.capture.R
import com.gomoney.capture.source.MatchResult
import com.gomoney.capture.source.ValidationError
import com.gomoney.capture.storage.ServerAccount
import com.gomoney.capture.storage.TransactionSource
import com.gomoney.capture.sync.CatalogRepository
import com.gomoney.capture.ui.theme.AmountFormat
import com.gomoney.capture.ui.theme.CaptureThemeTokens

/**
 * Source editor screen (US4, T028, contracts/sources-and-settings.md §2):
 * Titled Add / Edit source, validation next to fields, test success shows
 * formatted amount + direction, 48dp tap targets.
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

    val extendedColors = CaptureThemeTokens.extendedColors
    val errorsByField = remember(errors) { errors.groupBy { it.field.lowercase() } }

    BackHandler(onBack = onCancel)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Back navigation and title
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.height(48.dp),
            ) {
                Text(stringResource(R.string.source_editor_back))
            }
            Text(
                text = if (initial.name.isBlank()) stringResource(R.string.source_editor_add_title) else stringResource(R.string.source_editor_edit_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Name
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.source_field_name)) },
                isError = errorsByField.containsKey("name"),
                modifier = Modifier.fillMaxWidth(),
            )
            errorsByField["name"]?.forEach { error ->
                Text(text = error.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        // Identifier
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = identifier,
                onValueChange = { identifier = it },
                label = { Text(stringResource(R.string.source_field_identifier)) },
                isError = errorsByField.containsKey("identifier"),
                modifier = Modifier.fillMaxWidth(),
            )
            errorsByField["identifier"]?.forEach { error ->
                Text(text = error.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        // Channel
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.source_field_channel), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = channel.equals("notification", ignoreCase = true),
                    onClick = { channel = "notification" },
                    label = { Text(stringResource(R.string.source_channel_notification)) },
                    modifier = Modifier.height(48.dp),
                )
                FilterChip(
                    selected = channel.equals("sms", ignoreCase = true),
                    onClick = { channel = "sms" },
                    label = { Text(stringResource(R.string.source_channel_sms)) },
                    modifier = Modifier.height(48.dp),
                )
            }
        }

        // Template
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = template,
                onValueChange = { template = it },
                label = { Text(stringResource(R.string.source_field_template)) },
                supportingText = { Text(stringResource(R.string.source_template_hint)) },
                isError = errorsByField.containsKey("template"),
                modifier = Modifier.fillMaxWidth(),
            )
            errorsByField["template"]?.forEach { error ->
                Text(text = error.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        // Income keywords
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = incomeKeywords,
                onValueChange = { incomeKeywords = it },
                label = { Text(stringResource(R.string.source_field_income_keywords)) },
                supportingText = { Text(stringResource(R.string.source_keywords_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Expense keywords
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = expenseKeywords,
                onValueChange = { expenseKeywords = it },
                label = { Text(stringResource(R.string.source_field_expense_keywords)) },
                supportingText = { Text(stringResource(R.string.source_keywords_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Server account
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.source_field_account), style = MaterialTheme.typography.labelMedium)
            val selectedAccount = accounts.firstOrNull { it.id == boundAccountId }
            Box {
                OutlinedButton(
                    onClick = { accountMenuOpen = true; onRefreshAccounts() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text(selectedAccount?.let { "${it.label} (${it.currency})" } ?: stringResource(R.string.sources_unbound))
                }
                DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sources_unbound)) },
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
                                boundAccountId = account.id
                                boundAccountLabel = account.label
                                accountMenuOpen = false
                            },
                        )
                    }
                }
            }
            com.gomoney.capture.source.SourceValidator.validateBinding(boundAccountId, accounts)?.let {
                Text(text = it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (catalogUnavailable) {
                Text(stringResource(R.string.source_account_unavailable), style = MaterialTheme.typography.bodySmall, color = extendedColors.warning)
            } else if (accounts.isEmpty()) {
                Text(stringResource(R.string.source_account_none), style = MaterialTheme.typography.bodySmall)
            }
            if (CatalogRepository.isBindingStale(boundAccountId, accounts)) {
                Text(
                    text = stringResource(R.string.sources_binding_stale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Test section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.source_test), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = sample,
                    onValueChange = { sample = it },
                    label = { Text(stringResource(R.string.source_test_sample)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onTest(template, sample, incomeKeywords, expenseKeywords) },
                    modifier = Modifier.height(48.dp),
                ) {
                    Text(stringResource(R.string.source_test))
                }
                when (val result = testResult) {
                    is MatchResult.Success -> {
                        val selectedAccount = accounts.firstOrNull { it.id == boundAccountId }
                        val formattedAmount = AmountFormat.formatAmountMinor(result.amountMinor, selectedAccount?.currency)
                        val directionLabel = if (result.direction.wire.equals("income", ignoreCase = true)) "Income" else "Expense"
                        Text(
                            text = stringResource(R.string.source_test_success, directionLabel, formattedAmount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = extendedColors.success,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    is MatchResult.Failure -> {
                        Text(
                            text = stringResource(R.string.source_test_failure, result.reason),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    null -> Unit
                }
            }
        }

        // Bottom actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
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
                },
                modifier = Modifier.height(48.dp),
            ) {
                Text(stringResource(R.string.source_save))
            }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.height(48.dp),
            ) {
                Text(stringResource(R.string.source_cancel))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
