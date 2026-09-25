package com.gomoney.capture.capture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.model.DeliveryState
import com.gomoney.capture.model.EventSource
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.DedupRepository
import com.gomoney.capture.storage.DeliveryRepository
import com.gomoney.capture.storage.RawEvent
import com.gomoney.capture.storage.ServerConfiguration
import com.gomoney.capture.storage.SettingsRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Capture pipeline tests (T022): quickstart scenarios 1.1–1.4 with in-memory
 * Room — allowed allow-listed capture, non-allow-listed ignored, toggle off
 * captures nothing, revoked listener access stops capture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapturePipelineTest {

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var deliveryRepository: DeliveryRepository
    private lateinit var dedupRepository: DedupRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // Tests construct the repository with a preference-backed token store
        // (plain SharedPreferences) to avoid EncryptedSharedPreferences in JVM tests.
        settings = SettingsRepository(context) { context.getSharedPreferences("test-secure", Context.MODE_PRIVATE) }
        deliveryRepository = DeliveryRepository(db.deliveryRecordDao())
        dedupRepository = DedupRepository(db.dedupCacheDao())
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
    )

    private fun rawEvent(
        pkg: String = "ir.mellat.mellatab",
        text: String = "خريد شماره کارت 6104.****1234 به مبلغ ۵۰۰٬۰۰۰ ریال فروشگاه",
        postedAt: String = "2026-09-23T20:31:22+03:30",
        source: String = "notification",
    ) = RawEvent(
        id = "evt-" + postedAt.hashCode().toString(16),
        source = source,
        sourcePackage = pkg,
        bank = null,
        title = "بانک ملت",
        text = text,
        postedAt = postedAt,
        capturedAt = postedAt,
    )

    private suspend fun configure(
        packages: Set<String> = setOf("ir.mellat.mellatab"),
        notificationEnabled: Boolean = true,
        smsEnabled: Boolean = false,
    ) {
        settings.setEnabledBankPackages(packages)
        settings.setNotificationCaptureEnabled(notificationEnabled)
        settings.setSmsCaptureEnabled(smsEnabled)
        settings.setServerUrl("http://192.168.1.10:8787")
        settings.setBearerToken("test-token")
    }

    /** Scenario 1.1 — supported bank notification → queued transaction. */
    @Test
    fun `allow listed notification produces queued transaction`() = runTest {
        configure()
        val pipeline = pipeline()

        val outcome = pipeline.process(rawEvent())

        assertEquals(Outcome.QUEUED, outcome)
        assertEquals(1, db.normalizedTransactionDao().count())
        // The outbox row is queued for delivery.
        assertEquals(1, db.deliveryRecordDao().pendingForDelivery().size)
    }

    /** Scenario 1.2 — non-allow-listed notifications are ignored (not stored). */
    @Test
    fun `non allow listed notification ignored`() = runTest {
        configure(packages = setOf("ir.mellat.mellatab"))
        val outcome = pipeline().process(rawEvent(pkg = "com.random.app"))

        assertEquals(Outcome.IGNORED_NOT_ALLOW_LISTED, outcome)
        assertEquals(0, db.normalizedTransactionDao().count())
    }

    /** Scenario 1.3 — notification capture disabled → nothing captured. */
    @Test
    fun `capture toggle off captures nothing`() = runTest {
        configure(notificationEnabled = false)
        val outcome = pipeline().process(rawEvent())

        assertEquals(Outcome.IGNORED_DISABLED, outcome)
        assertEquals(0, db.normalizedTransactionDao().count())
    }

    /** Scenario 1.4 — no parser match → retained flagged parse_error, not dropped. */
    @Test
    fun `unmatched event retained as parse_error`() = runTest {
        configure()
        val outcome = pipeline().process(rawEvent(text = "سلام، این یک پیام بی‌ربط است"))

        assertEquals(Outcome.RETAINED_PARSE_ERROR, outcome)
        val record = db.deliveryRecordDao().byId(rawEvent().id)
        assertEquals(DeliveryState.PARSED.name.lowercase(), record?.state)
        assertEquals("parse_error", record?.errorCategory)
    }

    /** Same real transaction captured twice → second collapses (US3 hook). */
    @Test
    fun `identical re-capture short-circuits locally`() = runTest {
        configure()
        val pipeline = pipeline()
        val event = rawEvent()

        val first = pipeline.process(event)
        assertEquals(Outcome.QUEUED, first)

        // A second identical capture (same bank/type/amount/description but a
        // different raw event id/timestamps) — exact fingerprint dedup.
        val secondEvent = rawEvent(postedAt = "2026-09-23T20:31:22+03:30").copy(id = "evt-second")
        val second = pipeline.process(secondEvent)

        assertEquals(
            Outcome.DUPLICATE_SHORT_CIRCUITED,
            second,
        )
        assertEquals(1, db.normalizedTransactionDao().count())
    }

    /** Permission gate stops capture when revoked (T022 edge case). */
    @Test
    fun `capture gate blocks when listener access revoked`() {
        val gate = CaptureGate(
            object : CaptureGate.CapturePermission {
                override fun isNotificationListenerAccessGranted() = false
                override fun isSmsPermissionGranted() = false
            },
        )
        val config = ServerConfiguration(
            serverUrl = "http://x",
            notificationCaptureEnabled = true,
            smsCaptureEnabled = true,
        )

        assertTrue(!gate.allows(EventSource.NOTIFICATION, config))
        assertTrue(!gate.allows(EventSource.SMS, config))
    }
}
