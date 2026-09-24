package com.gomoney.capture.capture

import com.gomoney.capture.model.EventSource
import com.gomoney.capture.storage.ServerConfiguration

/**
 * Capture gating + permission checks (T022, FR-008/R8):
 * capture stops when notification-listener access is revoked and queued data
 * is preserved (edge case).
 */
class CaptureGate(
    private val permission: CapturePermission,
) {
    /** Abstraction over NotificationManagerCompat / ContextCompat checks. */
    interface CapturePermission {
        fun isNotificationListenerAccessGranted(): Boolean
        fun isSmsPermissionGranted(): Boolean
    }

    /** Notification events pass only when listener access is granted. */
    fun allowsNotificationCapture(): Boolean = permission.isNotificationListenerAccessGranted()

    /** SMS events pass only when RECEIVE_SMS is granted. */
    fun allowsSmsCapture(): Boolean = permission.isSmsPermissionGranted()

    /**
     * Combined gate used before processing (T022 wiring): a revoked permission
     * stops capture without touching queued data.
     */
    fun allows(source: EventSource, config: ServerConfiguration): Boolean = when (source) {
        EventSource.NOTIFICATION -> config.notificationCaptureEnabled && allowsNotificationCapture()
        EventSource.SMS -> config.smsCaptureEnabled && allowsSmsCapture()
    }
}
