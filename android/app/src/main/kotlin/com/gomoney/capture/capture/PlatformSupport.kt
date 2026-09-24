package com.gomoney.capture.capture

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import androidx.core.content.ContextCompat
import com.gomoney.capture.sync.SyncEngine

/** Platform-backed permission checks (FR-024 status source). */
class PlatformCapturePermission(private val context: Context) : CaptureGate.CapturePermission {

    override fun isNotificationListenerAccessGranted(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val enabled = nm.enabledListenerPackages ?: emptySet()
        return context.packageName in enabled
    }

    override fun isSmsPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
}

/** WorkManager enqueue helpers (T031 wiring). */
object TransactionSyncHelper {

    private const val UNIQUE_PERIODIC = "transaction-sync-periodic"

    /** Expedited on-demand work: enqueued at pipeline completion (T020). */
    fun enqueueExpedited(context: Context) {
        SyncEngine.enqueueExpedited(context)
    }

    /** Unique periodic work: re-enqueued on app start so the queue survives restarts (FR-016). */
    fun ensurePeriodic(context: Context) {
        SyncEngine.enqueuePeriodic(context)
    }

    /** Current listener access state, for the settings/dashboard screens (FR-024). */
    fun isListenerConfigured(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val enabled = nm.enabledListenerPackages ?: emptySet()
        if (context.packageName in enabled) return true
        // Fallback: ask the service component status via NotificationManagerCompat-less check.
        return false
    }

    /** The listener service component (for system-settings deep links, FR-024). */
    fun listenerComponent(context: Context): ComponentName =
        ComponentName(context, NotificationListenerService::class.java)
}
