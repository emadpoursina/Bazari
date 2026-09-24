package com.gomoney.capture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gomoney.capture.storage.DeliveryRecord
import com.gomoney.capture.storage.NormalizedTransaction
import com.gomoney.capture.storage.ServerConfiguration

/**
 * Event detail (US4, T038, FR-023): sanitized detail only — amount, bank,
 * time, delivery state, error category. Raw text is hidden unless debug mode
 * is on (delegated to the US7 DebugEventScreen).
 */
@Composable
fun EventDetailScreen(
    tx: NormalizedTransaction?,
    record: DeliveryRecord?,
    config: ServerConfiguration,
    onRetry: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "Event detail", style = MaterialTheme.typography.titleLarge)
        Text(text = "${tx?.amountMinor ?: 0} IRR", style = MaterialTheme.typography.headlineSmall)
        Text(text = "Bank: ${tx?.bank ?: "-"} · account ${tx?.accountHint ?: "-"}")
        Text(text = "Time: ${tx?.txAt ?: "-"}")
        Text(text = "Delivery state: ${record?.state ?: "-"}")
        record?.errorCategory?.let { Text(text = "Error category: $it") }
        record?.errorDetail?.let { Text(text = "Detail: $it") } // sanitized only (FR-023)
        Text(text = "Parser: ${tx?.parserName ?: "-"} (confidence ${tx?.confidence ?: "-"})")

        if (record?.state == "failed") {
            Button(onClick = { record?.id?.let(onRetry) }) {
                Text("Retry delivery")
            }
        }
    }
}
