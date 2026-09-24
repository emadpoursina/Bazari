package com.gomoney.capture

import android.app.Application
import com.gomoney.capture.sync.SyncEngine

/**
 * App start (T031 wiring): installs the unique periodic sync work so the
 * outbox survives app/phone restarts (FR-016).
 */
class CaptureApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        SyncEngine.enqueuePeriodic(this)
    }
}
