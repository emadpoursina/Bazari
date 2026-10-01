package com.gomoney.capture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gomoney.capture.storage.ParseErrorMessage

/**
 * Parse-error review list (US1, FR-028, SC-010): newest-first, each entry shows
 * the sanitized failure reason (never raw text — FR-023) and can be dismissed.
 */
@Composable
fun ParseErrorReviewList(
    errors: List<ParseErrorMessage>,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "Unparsed messages (${errors.size})", style = MaterialTheme.typography.titleSmall)
        if (errors.isEmpty()) {
            Text(text = "No parse errors.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        errors.forEach { error ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = error.failureReason, style = MaterialTheme.typography.bodyMedium)
                        Text(text = error.occurredAt, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { onDismiss(error.id) }) { Text("Dismiss") }
                }
            }
        }
    }
}
