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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gomoney.capture.R
import com.gomoney.capture.storage.TransactionSource

/**
 * Sources screen (US1/US4, FR-001/003/028): the source list with enable/disable
 * and remove actions, plus the parse-error review list.
 */
@Composable
fun SourcesScreen(
    sources: List<TransactionSource>,
    parseErrors: List<com.gomoney.capture.storage.ParseErrorMessage>,
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
            title = { Text("Remove source?") },
            text = {
                Text(
                    "Messages from \"${source.name}\" will no longer be captured. " +
                        "Already-captured transactions are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval = null
                    onRemove(source.id)
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAdd) { Text("Add source") }
            }
        }
        if (sources.isEmpty()) {
            item { Text("No sources yet. Add one to capture a new sender.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(sources, key = { it.id }) { source ->
            SourceCard(
                source = source,
                bindingStale = source.id in staleBindingIds,
                onEdit = { onEdit(source.id) },
                onToggle = { enabled -> onToggle(source.id, enabled) },
                onRemove = { pendingRemoval = source },
            )
        }
        item {
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = source.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${source.identifier} · ${source.channel}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = source.boundAccountLabel?.let { "Account: $it" } ?: "No account bound",
                style = MaterialTheme.typography.bodySmall,
            )
            if (bindingStale) {
                Text(
                    text = stringResource(R.string.sources_binding_stale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = source.enabled, onCheckedChange = onToggle)
                Text(
                    text = if (source.enabled) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onEdit) { Text("Edit") }
                OutlinedButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}
