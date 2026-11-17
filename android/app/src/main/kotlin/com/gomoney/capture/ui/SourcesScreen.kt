package com.gomoney.capture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.gomoney.capture.storage.ParseErrorMessage
import com.gomoney.capture.storage.TransactionSource

/**
 * Sources screen (US4, T027, contracts/sources-and-settings.md §1):
 * Distinct source list, empty state explaining why sources matter,
 * populated rows with status and binding, plus unparsed messages section below.
 */
@Composable
fun SourcesScreen(
    sources: List<TransactionSource>,
    parseErrors: List<ParseErrorMessage>,
    staleBindingIds: Set<String>,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onDismissParseError: (String) -> Unit,
) {
    var pendingRemoval by remember { mutableStateOf<TransactionSource?>(null) }

    pendingRemoval?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.sources_remove_confirm_title)) },
            text = { Text(stringResource(R.string.sources_remove_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval = null
                    onRemove(source.id)
                }) { Text(stringResource(R.string.sources_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text(stringResource(R.string.source_cancel)) }
            },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.sources_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Button(
                    onClick = onAdd,
                    modifier = Modifier.height(48.dp),
                ) {
                    Text(stringResource(R.string.sources_add))
                }
            }
        }

        if (sources.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.sources_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onAdd,
                            modifier = Modifier.height(48.dp),
                        ) {
                            Text(stringResource(R.string.sources_add))
                        }
                    }
                }
            }
        } else {
            items(sources, key = { it.id }) { source ->
                SourceCard(
                    source = source,
                    bindingStale = source.id in staleBindingIds,
                    onEdit = { onEdit(source.id) },
                    onToggle = { enabled -> onToggle(source.id, enabled) },
                    onRemove = { pendingRemoval = source },
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            ParseErrorReviewList(errors = parseErrors, onDismiss = onDismissParseError)
        }
    }
}

@Composable
private fun SourceCard(
    source: TransactionSource,
    bindingStale: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (source.enabled) stringResource(R.string.sources_enabled) else stringResource(R.string.sources_disabled),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (source.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = source.enabled,
                        onCheckedChange = onToggle,
                    )
                }
            }

            Text(
                text = "${source.identifier} · ${source.channel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = source.boundAccountLabel?.let { "Account: $it" } ?: stringResource(R.string.sources_unbound),
                style = MaterialTheme.typography.bodyMedium,
            )

            if (bindingStale) {
                Text(
                    text = stringResource(R.string.sources_binding_stale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.height(40.dp).padding(end = 8.dp),
                ) {
                    Text(stringResource(R.string.sources_edit))
                }
                OutlinedButton(
                    onClick = onRemove,
                    modifier = Modifier.height(40.dp),
                ) {
                    Text(stringResource(R.string.sources_remove))
                }
            }
        }
    }
}
