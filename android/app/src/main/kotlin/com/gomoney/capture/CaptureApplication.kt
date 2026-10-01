package com.gomoney.capture

import android.app.Application
import androidx.work.Configuration
import com.gomoney.capture.source.TransactionSourceRepository
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App start (T031 wiring): installs the unique periodic sync work so the
 * outbox survives app/phone restarts (FR-016), and idempotently seeds the
 * former built-in banks as ordinary sources (005 T022, FR-021).
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
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val db = AppDatabase.get(this@CaptureApplication)
                TransactionSourceRepository(db.transactionSourceDao(), db.parseErrorDao(), db)
                    .seedDefaults()
            }
        }
    }
}
