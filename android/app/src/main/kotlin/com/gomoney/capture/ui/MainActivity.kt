package com.gomoney.capture.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.gomoney.capture.capture.PlatformCapturePermission
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.SettingsRepository
import kotlinx.coroutines.launch

/**
 * Single-activity host with three tabs: Dashboard (US4), Events (US1), and
 * Settings (US4). Detail/debug views open from the Events tab.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = AppDatabase.get(applicationContext)
        val settings = SettingsRepository(applicationContext)
        val permission = PlatformCapturePermission(applicationContext)

        setContent {
            MaterialTheme {
                AppTabs(db, settings, permission)
            }
        }
    }
}

@Composable
private fun AppTabs(
    db: AppDatabase,
    settings: SettingsRepository,
    permission: PlatformCapturePermission,
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Dashboard", "Events", "Settings")

    // View models are hoisted above the tab switcher so state survives tab
    // changes (remembered once per composition, not per branch).
    val dashboardViewModel = remember { DashboardViewModel(db, settings, permission) }
    val eventsViewModel = remember { EventsViewModel(db) }
    val settingsViewModel = remember { SettingsViewModel(settings) }
    val dashboardState by dashboardViewModel.state.collectAsStateWithLifecycleCompat()
    val eventsState by eventsViewModel.state.collectAsStateWithLifecycleCompat()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val eventActions = remember { EventActions(context, db) }
    val settingsConnectionStatus by settingsViewModel.connectionStatus.collectAsStateWithLifecycleCompat()

    Scaffold(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        bottomBar = {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, label ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(label) })
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    state = dashboardState,
                    onRefreshConnection = { dashboardViewModel.refreshConnection() },
                )
                1 -> EventsScreen(
                    state = eventsState,
                    onRetry = { deliveryId ->
                        scope.launch { eventActions.retry(deliveryId) }
                    },
                    onClearProcessed = {
                        scope.launch { eventActions.clearProcessed() }
                    },
                )
                2 -> SettingsScreen(
                    viewModel = settingsViewModel,
                    permission = permission,
                    onTestConnection = { settingsViewModel.testConnection() },
                    connectionStatus = settingsConnectionStatus,
                )
            }
        }
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithLifecycleCompat() =
    collectAsState()
