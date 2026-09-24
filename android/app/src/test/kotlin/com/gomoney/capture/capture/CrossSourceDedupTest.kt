package com.gomoney.capture.capture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.parser.Fingerprint
import com.gomoney.capture.model.TxType
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.DedupRepository
import com.gomoney.capture.storage.DeliveryRepository
import com.gomoney.capture.storage.RawEvent
import com.gomoney.capture.storage.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Cross-source dedup end-to-end (US5, T045, quickstart Scenario 3 step 2):
 * notification + SMS of the same transaction ≤2 min apart collapse to a
 * single recorded transaction. App-side behavior:
 *  - same-minute events → identical fingerprint → local short-circuit;
 *  - cross-minute (≤2 min) events → both queued locally (different round(ts)),
 *    collapsed by the bridge's ±2-min bucket-window scan (see
 *    pkg/androidbridge tests).
 */
@RunWith(RobolectricTestRunner::class)
class CrossSourceDedupTest {

    private lateinit var db: AppDatabase
    private lateinit var deliveryRepository: DeliveryRepository
    private lateinit var dedupRepository: DedupRepository

    private val mellatText = "خريد شماره کارت 6104.****1234 به مبلغ ۵۰۰٬۰۰۰ ریال فروشگاه قطبی"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        deliveryRepository = DeliveryRepository(db.deliveryRecordDao())
        dedupRepository = DedupRepository(db.dedupCacheDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun pipeline() = CapturePipeline(
        db = db,
        settings = SettingsRepository(ApplicationProvider.getApplicationContext()) {
            ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("t", Context.MODE_PRIVATE)
        },
        deliveryRepository = deliveryRepository,
        dedupRepository = dedupRepository,
    )

    private fun rawEvent(id: String, source: String, sourcePackage: String, postedAt: String): RawEvent = RawEvent(
        id = id,
        source = source,
        sourcePackage = sourcePackage,
        bank = null,
        title = if (source == "notification") "بانک ملت" else null,
        text = mellatText,
        postedAt = postedAt,
        capturedAt = postedAt,
    )


    private suspend fun configure() {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext()) {
            ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("t", Context.MODE_PRIVATE)
        }
        settings.setEnabledBankPackages(setOf("ir.mellat.mellatab", "MELLAT"))
        settings.setNotificationCaptureEnabled(true)
        settings.setSmsCaptureEnabled(true)
        settings.setServerUrl("http://192.168.1.10:8787")
        settings.setBearerToken("test-token")
    }

    /** Same real transaction, SMS 28 s later (same minute) → exact fingerprint → local short-circuit. */
    @Test
    fun `same minute notification and sms collapse locally`() = runTest {
        configure()
        val pipeline = pipeline()

        val notification = rawEvent("evt-notif", "notification", "ir.mellat.mellatab", "2026-09-23T20:31:22+03:30")
        val sms = rawEvent("evt-sms", "sms", "MELLAT", "2026-09-23T20:31:50+03:30")

        val first = pipeline.process(notification)
        val second = pipeline.process(sms)

        assertEquals(CapturePipeline.Outcome.QUEUED, first)
        // Identical fingerprint (same round(ts), same bank/account/type/amount/description).
        assertEquals(CapturePipeline.Outcome.DUPLICATE_SHORT_CIRCUITED, second)
        assertEquals(1, db.normalizedTransactionDao().count())
    }

    /** Cross-minute (≤2 min) pair: both queue locally; bridge collapses via bucket window. */
    @Test
    fun `cross minute pair queues both and fingerprints differ only by minute`() = runTest {
        configure()
        val pipeline = pipeline()

        val notification = rawEvent("evt-notif2", "notification", "ir.mellat.mellatab", "2026-09-23T20:31:22+03:30")
        val sms = rawEvent("evt-sms2", "sms", "MELLAT", "2026-09-23T20:32:30+03:30") // 68 s later

        val first = pipeline.process(notification)
        val second = pipeline.process(sms)

        assertEquals(CapturePipeline.Outcome.QUEUED, first)
        assertEquals(CapturePipeline.Outcome.QUEUED, second)
        assertEquals(2, db.normalizedTransactionDao().count())

        // The two rows differ only in the minute-rounded fingerprint component;
        // the bridge's ±2-min window scan (adjacent buckets) collapses them —
        // exactly one Go Money transaction (FR-030, verified in Go tests).
        val parsed = kotlinx.coroutines.flow.first(db.normalizedTransactionDao().observeRecent(10))
        val fingerprints = parsed.map { it.fingerprint }
        assertEquals(2, fingerprints.distinct().size)
        fingerprints.forEach { fp ->
            assertEquals(64, fp.length)
        }
    }

    /** SMS capture disabled → ignored entirely (US5 scenario 2, FR-017). */
    @Test
    fun `sms when capture disabled is ignored`() = runTest {
        val pipeline = pipeline()
        // smsCaptureEnabled defaults to false.
        val sms = rawEvent("evt-sms-off", "sms", "MELLAT", "2026-09-23T20:31:22+03:30")

        val outcome = pipeline.process(sms)
        assertEquals(CapturePipeline.Outcome.IGNORED_DISABLED, outcome)
        assertEquals(0, db.normalizedTransactionDao().count())
    }
}
