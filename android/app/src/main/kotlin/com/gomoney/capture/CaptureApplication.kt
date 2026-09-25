package com.gomoney.capture

import android.app.Application
import androidx.work.Configuration
import com.gomoney.capture.sync.SyncEngine

/**
 * App start (T031 wiring): installs the unique periodic sync work so the
 * outbox survives app/phone restarts (FR-016).
 *
 * WorkManager is initialized on-demand (Configuration.Provider) so both the
 * app process and Robolectric unit tests share the same initialization path.
 */
class CaptureApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        SyncEngine.enqueuePeriodic(this)
    }
}
