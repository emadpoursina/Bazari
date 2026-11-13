package com.gomoney.capture

import android.app.Application
import androidx.work.Configuration
import com.gomoney.capture.sync.NightlyLeftoverWorker
import com.gomoney.capture.sync.SyncEngine

/**
 * App start (T031 wiring): installs the unique periodic sync work so the
 * outbox survives app/phone restarts (FR-016).
 *
 * 009 US4: enqueues unique one-time nightly leftover worker for next 21:00 local.
 *
 * No sources are seeded here: a fresh install starts with an empty source
 * list and only user-created sources exist (005 amendment — default
 * former-bank seeds removed).
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
        NightlyLeftoverWorker.scheduleNext(this)
    }
}
