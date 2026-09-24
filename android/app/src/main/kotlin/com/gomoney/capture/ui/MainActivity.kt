package com.gomoney.capture.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.setValue
import com.gomoney.capture.capture.PlatformCapturePermission
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.SettingsRepository

/**
 * Single-activity host with three tabs: Dashboard (US4), Events (US1), and
 * Settings (US4). Detail/debug views open from the Events tab.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

    Scaffold(
        bottomBar = {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, label ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(label) })
                }
            }
        },
    ) { padding ->
        when (selectedTab) {
            0 -> {
                val state by DashboardViewModel(db, settings, permission)
                    .state.collectAsStateWithLifecycleCompat()
                DashboardScreen(state = state, onRefreshConnection = { })
            }
            1 -> EventsScreen(rows = emptyList())
            2 -> SettingsScreen(
                viewModel = SettingsViewModel(settings),
                permission = permission,
                onTestConnection = { },
                connectionStatus = "",
            )
        }
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithLifecycleCompat() =
    collectAsState()
