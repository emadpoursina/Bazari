package com.gomoney.capture.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gomoney.capture.R
import com.gomoney.capture.sync.SyncBanner
import com.gomoney.capture.ui.DashboardViewModel.DashboardState
import com.gomoney.capture.ui.theme.AmountFormat
import com.gomoney.capture.ui.theme.CaptureThemeTokens
import com.gomoney.capture.ui.theme.HomeNextStep
import com.gomoney.capture.ui.theme.StatusChip
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Home / Capture Health screen (US1, T016, contracts/home-health.md):
 * Everyday connection state + counts + recent captures + single primary next step.
 * Calm healthy surfaces; no spending totals, charts, or budgets (FR-020).
 */
@Composable
fun DashboardScreen(
    state: DashboardState,
    onRefreshConnection: () -> Unit,
    onSyncNow: () -> Unit = {},
    onOpenDetail: (String) -> Unit = {},
    onGrantNotificationAccess: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenEvents: () -> Unit = {},
) {
    val extendedColors = CaptureThemeTokens.extendedColors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // App header
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )

        // Health / Connection Card (always visible per FR-003, T043)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.home_healthy_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    val connectionText = when (state.connectionStatus) {
                        HomeConnectionStatus.Reachable -> {
                            val ep = state.activeEndpoint?.let { " ($it)" }.orEmpty()
                            "${stringResource(R.string.home_connection_reachable)}$ep"
                        }
                        HomeConnectionStatus.NotReachable -> stringResource(R.string.home_connection_not_reachable)
                        HomeConnectionStatus.NotSetUpYet -> stringResource(R.string.home_connection_not_setup)
                    }
                    Text(
                        text = connectionText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onRefreshConnection) {
                    Text("Test")
                }
            }
        }

        // Single recovery action when blocked or queue needs attention
        when (state.nextStep) {
            HomeNextStep.GrantNotificationAccess -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = extendedColors.warning.copy(alpha = 0.1f),
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.home_blocked_permission_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.notification_access_explanation),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = onGrantNotificationAccess,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) {
                            Text(stringResource(R.string.home_blocked_permission_cta))
                        }
                    }
                }
            }
            HomeNextStep.ConfigureDestination -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = extendedColors.warning.copy(alpha = 0.1f),
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.home_blocked_endpoint_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.home_connection_not_setup),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = onOpenSettings,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) {
                            Text(stringResource(R.string.home_blocked_endpoint_cta))
                        }
                    }
                }
            }
            HomeNextStep.ViewEvents -> {
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
                            text = stringResource(R.string.home_blocked_queue_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "${state.failed} failed · ${state.waiting} waiting",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = onOpenEvents,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) {
                            Text(stringResource(R.string.home_blocked_queue_cta))
                        }
                    }
                }
            }
            HomeNextStep.None -> {
                // Calm healthy surface; no recovery action needed
            }
        }

        // Secondary sync action if sync banner is not ALL_CLEAR
        if (state.banner.kind != SyncBanner.Kind.ALL_CLEAR) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.bannerMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onSyncNow) {
                        Text(stringResource(R.string.home_sync_now))
                    }
                }
            }
        }

        // Three key counts
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CountCard(
                label = stringResource(R.string.home_captured_today),
                count = state.capturedToday,
                modifier = Modifier.weight(1f),
            )
            CountCard(
                label = stringResource(R.string.home_waiting_to_send),
                count = state.waiting,
                modifier = Modifier.weight(1f),
            )
            CountCard(
                label = stringResource(R.string.home_couldnt_send),
                count = state.failed,
                modifier = Modifier.weight(1f),
            )
        }

        // Recent activity
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_recent_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (state.recentRows.isNotEmpty()) {
                    Text(
                        text = "View all",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onOpenEvents() },
                    )
                }
            }

            if (state.recentRows.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_recent_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                state.recentRows.forEach { row ->
                    RecentEventRow(
                        row = row,
                        onClick = { onOpenDetail(row.txId) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun CountCard(label: String, count: Long, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = count.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(text = label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RecentEventRow(
    row: EventRow,
    onClick: () -> Unit,
) {
    val extendedColors = CaptureThemeTokens.extendedColors
    val isExpense = row.type.equals("expense", ignoreCase = true)
    val sign = if (isExpense) "-" else "+"
    val amountColor = if (isExpense) extendedColors.expense else extendedColors.income
    val formattedAmount = AmountFormat.formatAmountMinor(row.amountMinor, row.currency)

    val timeLabel = runCatching {
        OffsetDateTime.parse(row.txAt).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }.getOrElse { row.txAt.takeLast(8) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "$sign$formattedAmount",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = amountColor,
                    )
                }
                Text(
                    text = "${row.bank} · $timeLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip(
                state = row.state,
                errorCategory = row.errorCategory,
            )
        }
    }
}
