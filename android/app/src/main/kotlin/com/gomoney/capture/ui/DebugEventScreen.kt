package com.gomoney.capture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gomoney.capture.storage.NormalizedTransaction
import com.gomoney.capture.storage.RawEvent

/**
 * Debug event detail view (US7, T046): gated by debugModeEnabled. Renders the
 * RawEvent (source, package, title, text) alongside the parse result
 * (type, amount, currency, account hint, parserName, confidence).
 * Raw text is never rendered when debug mode is off (FR-025/028).
 */
@Composable
fun DebugEventScreen(
    debugModeEnabled: Boolean,
    rawEvent: RawEvent?,
    tx: NormalizedTransaction?,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "Debug event detail", style = MaterialTheme.typography.titleLarge)

        if (!debugModeEnabled) {
            Text(text = "Debug mode is OFF — raw event fields are hidden.", style = MaterialTheme.typography.bodyMedium)
            return
        }

        Text(text = "— Raw event —", style = MaterialTheme.typography.titleSmall)
        Text(text = "Source: ${rawEvent?.source ?: "-"}")
        Text(text = "Package/sender: ${rawEvent?.sourcePackage ?: "-"}")
        Text(text = "Title: ${rawEvent?.title ?: "-"}")
        Text(text = "Text: ${rawEvent?.text ?: "-"}") // debug mode only (FR-025)
        Text(text = "Posted at: ${rawEvent?.postedAt ?: "-"}")

        Text(text = "— Parse result —", style = MaterialTheme.typography.titleSmall)
        Text(text = "Type: ${tx?.type ?: "-"}")
        Text(text = "Amount: ${tx?.amountMinor ?: "-"} ${tx?.currency ?: ""}")
        Text(text = "Account hint: ${tx?.accountHint ?: "-"}")
        Text(text = "Parser: ${tx?.parserName ?: "-"}")
        Text(text = "Confidence: ${tx?.confidence ?: "-"}")
        Text(text = "Fingerprint: ${tx?.fingerprint ?: "-"}")
    }
}
