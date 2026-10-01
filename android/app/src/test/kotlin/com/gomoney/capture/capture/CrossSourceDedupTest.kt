package com.gomoney.capture.capture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.source.ParseErrorRepository
import com.gomoney.capture.source.TransactionSourceRepository
import com.gomoney.capture.source.UserSourceParser
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.DedupRepository
import com.gomoney.capture.storage.DeliveryRepository
import com.gomoney.capture.storage.RawEvent
import com.gomoney.capture.storage.SettingsRepository
import com.gomoney.capture.storage.TransactionSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Cross-source dedup end-to-end (US5, T045), repointed for 005 source-only
 * capture (T026): shipped parsers no longer run, so cross-channel Mellat
 * captures come from two seeded sources (notification package + SMS sender).
 * Behavior change: each source produces `bank=user` with its own identifier as
 * the account hint, so the two rows carry DIFFERENT fingerprints and both stay
 * queued — the local exact-fingerprint short-circuit now applies only to
 * identical re-captures from the SAME source, which is asserted here.
 */
@RunWith(RobolectricTestRunner::class)
class CrossSourceDedupTest {

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var deliveryRepository: DeliveryRepository
    private lateinit var dedupRepository: DedupRepository
    private lateinit var sourceRepository: TransactionSourceRepository

    private val mellatText = "خريد شماره کارت 6104.****1234 به مبلغ ۵۰۰٬۰۰۰ ریال فروشگاه قطبی"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        deliveryRepository = DeliveryRepository(db.deliveryRecordDao())
        dedupRepository = DedupRepository(db.dedupCacheDao())
        sourceRepository = TransactionSourceRepository(db.transactionSourceDao(), db.parseErrorDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun pipeline(captured: MutableList<String> = mutableListOf()) = CapturePipeline(
        db = db,
        settings = settings,
        deliveryRepository = deliveryRepository,
        dedupRepository = dedupRepository,
        onCaptured = { captured.add(it.id) },
        sourceRepository = sourceRepository,
        userSourceParser = UserSourceParser(
            ParseErrorRepository(db.parseErrorDao()),
            db.serverAccountDao(),
        ),
    )

    private suspend fun addSource(identifier: String, channel: String): TransactionSource {
        val source = TransactionSource(
            id = UUID.randomUUID().toString(),
            name = "Mellat $channel",
            identifier = identifier,
            channel = channel,
            enabled = true,
            template = "{direction} مبلغ {amount} ریال",
            incomeKeywords = "واریز",
            expenseKeywords = "خريد",
            boundAccountId = 7,
            boundAccountLabel = "Rial Account",
            createdAt = "2026-09-24T00:00:00+03:30",
            updatedAt = "2026-09-24T00:00:00+03:30",
        )
        sourceRepository.save(source)
        return source
    }

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
        this.settings = settings
        settings.setNotificationCaptureEnabled(true)
        settings.setSmsCaptureEnabled(true)
        settings.setServerUrl("http://192.168.1.10:8787")
        settings.setBearerToken("test-token")
        // 005: a cached catalog account so bound captures carry a currency.
        db.serverAccountDao().replaceAll(
            listOf(
                com.gomoney.capture.storage.ServerAccount(
                    id = 7,
                    label = "Rial Account",
                    currency = "IRR",
                    type = "asset",
                    isDefault = false,
                    refreshedAt = "2026-09-24T00:00:00+03:30",
                ),
            ),
        )
    }

    /** Identical re-capture from the SAME source → exact fingerprint → local short-circuit. */
    @Test
    fun `same source identical re-capture collapses locally`() = runTest {
        configure()
        addSource("ir.mellat.mellatab", "notification")
        val pipeline = pipeline()

        val first = pipeline.process(rawEvent("evt-notif", "notification", "ir.mellat.mellatab", "2026-09-23T20:31:22+03:30"))
        val second = pipeline.process(rawEvent("evt-notif-2", "notification", "ir.mellat.mellatab", "2026-09-23T20:31:50+03:30"))

        assertEquals(Outcome.QUEUED, first)
        assertEquals(Outcome.DUPLICATE_SHORT_CIRCUITED, second)
        assertEquals(1, db.normalizedTransactionDao().count())
    }

    /**
     * Cross-channel pair via seeded sources: both queue locally with distinct
     * fingerprints (different source identifiers as account hints); the
     * ±2-min bucket-window bridge collapse (bank/hint based) no longer applies
     * across user sources — both rows remain queued for delivery.
     */
    @Test
    fun `cross channel pair queues both with distinct fingerprints`() = runTest {
        configure()
        addSource("ir.mellat.mellatab", "notification")
        addSource("MELLAT", "sms")
        val pipeline = pipeline()

        val notification = rawEvent("evt-notif2", "notification", "ir.mellat.mellatab", "2026-09-23T20:31:22+03:30")
        val sms = rawEvent("evt-sms2", "sms", "MELLAT", "2026-09-23T20:32:30+03:30") // 68 s later

        val first = pipeline.process(notification)
        val second = pipeline.process(sms)

        assertEquals(Outcome.QUEUED, first)
        assertEquals(Outcome.QUEUED, second)
        assertEquals(2, db.normalizedTransactionDao().count())

        val parsed = db.normalizedTransactionDao().observeRecent(10).first()
        val fingerprints = parsed.map { it.fingerprint }
        assertEquals(2, fingerprints.distinct().size)
        fingerprints.forEach { fp ->
            assertEquals(64, fp.length)
        }
    }

    /** SMS capture disabled → ignored entirely (US5 scenario 2, FR-017). */
    @Test
    fun `sms when capture disabled is ignored`() = runTest {
        configure()
        addSource("MELLAT", "sms")
        settings.setSmsCaptureEnabled(false)
        val pipeline = pipeline()
        val sms = rawEvent("evt-sms-off", "sms", "MELLAT", "2026-09-23T20:31:22+03:30")

        val outcome = pipeline.process(sms)
        assertEquals(Outcome.IGNORED_DISABLED, outcome)
        assertEquals(0, db.normalizedTransactionDao().count())
    }
}
