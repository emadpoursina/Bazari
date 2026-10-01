package com.gomoney.capture.capture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.model.DeliveryState
import com.gomoney.capture.model.EventSource
import com.gomoney.capture.source.ParseErrorRepository
import com.gomoney.capture.source.TransactionSourceRepository
import com.gomoney.capture.source.UserSourceParser
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.DedupRepository
import com.gomoney.capture.storage.DeliveryRepository
import com.gomoney.capture.storage.RawEvent
import com.gomoney.capture.storage.ServerConfiguration
import com.gomoney.capture.storage.SettingsRepository
import com.gomoney.capture.storage.TransactionSource
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Capture pipeline tests, repointed for 005 source-only admission (T020/T026):
 * an enabled source with a user template captures; non-source identifiers are
 * ignored without persisting (including former allow-list and shipped
 * parser-only identifiers — T023); master capture toggles still gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapturePipelineTest {

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var deliveryRepository: DeliveryRepository
    private lateinit var dedupRepository: DedupRepository
    private lateinit var sourceRepository: TransactionSourceRepository
    private lateinit var userSourceParser: UserSourceParser

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
        sourceRepository = TransactionSourceRepository(db.transactionSourceDao(), db.parseErrorDao())
        userSourceParser = UserSourceParser(ParseErrorRepository(db.parseErrorDao()), db.serverAccountDao())
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
        userSourceParser = userSourceParser,
    )

    private fun rawEvent(
        pkg: String = "ir.mellat.mellatab",
        text: String = "خريد شماره کارت 6104.****1234 به مبلغ ۵۰۰٬۰۰۰ ریال فروشگاه",
        postedAt: String = "2026-09-23T20:31:22+03:30",
        source: String = "notification",
        id: String = "evt-" + UUID.randomUUID(),
    ) = RawEvent(
        id = id,
        source = source,
        sourcePackage = pkg,
        bank = null,
        title = "بانک ملت",
        text = text,
        postedAt = postedAt,
        capturedAt = postedAt,
    )

    private suspend fun configure(
        notificationEnabled: Boolean = true,
        smsEnabled: Boolean = false,
    ) {
        settings.setNotificationCaptureEnabled(notificationEnabled)
        settings.setSmsCaptureEnabled(smsEnabled)
        settings.setServerUrl("http://192.168.1.10:8787")
        settings.setBearerToken("test-token")
    }

    private suspend fun addSource(
        identifier: String = "ir.mellat.mellatab",
        channel: String = "notification",
        enabled: Boolean = true,
        boundAccountId: Int? = 7,
    ): TransactionSource {
        val source = TransactionSource(
            id = UUID.randomUUID().toString(),
            name = "Seeded Bank",
            identifier = identifier,
            channel = channel,
            enabled = enabled,
            template = "{direction} مبلغ {amount} ریال",
            incomeKeywords = "واریز",
            expenseKeywords = "خريد",
            boundAccountId = boundAccountId,
            boundAccountLabel = boundAccountId?.let { "Account $it" },
            createdAt = "2026-09-24T00:00:00+03:30",
            updatedAt = "2026-09-24T00:00:00+03:30",
        )
        sourceRepository.save(source)
        return source
    }

    /** Cached catalog account so bound captures carry a currency (005 FR-002). */
    private suspend fun addRialAccount(id: Int = 7) {
        db.serverAccountDao().replaceAll(
            listOf(
                com.gomoney.capture.storage.ServerAccount(
                    id = id,
                    label = "Rial Account",
                    currency = "IRR",
                    type = "asset",
                    isDefault = false,
                    refreshedAt = "2026-09-24T00:00:00+03:30",
                ),
            ),
        )
    }

    /** 005 US2 acceptance 2 — enabled source + template → considered for capture. */
    @Test
    fun `enabled source with template produces queued transaction`() = runTest {
        configure()
        addRialAccount()
        addSource()
        val captured = mutableListOf<String>()

        val outcome = pipeline(captured).process(rawEvent())

        assertEquals(Outcome.QUEUED, outcome)
        assertEquals(1, db.normalizedTransactionDao().count())
        // The outbox row is queued for delivery.
        assertEquals(1, db.deliveryRecordDao().pendingForDelivery().size)
    }

    /** 005 FR-014/T023 — non-source identifier ignored, nothing persisted. */
    @Test
    fun `non source notification ignored without persisting`() = runTest {
        configure()
        addSource()
        val event = rawEvent(pkg = "com.random.app")

        val outcome = pipeline().process(event)

        assertEquals(Outcome.IGNORED_NO_SOURCE, outcome)
        assertEquals(0, db.normalizedTransactionDao().count())
        assertNull(db.rawEventDao().byId(event.id))
    }

    /** 005 FR-014/T023 — a former Settings allow-list package without a source captures nothing. */
    @Test
    fun `former allow-list identifier without a source is ignored`() = runTest {
        configure()
        // No source defined for this former allow-list entry.
        val event = rawEvent(pkg = "com.samanpr.blu", text = "پیام تبلیغاتی بدون مبلغ تراکنش")

        val outcome = pipeline().process(event)

        assertEquals(Outcome.IGNORED_NO_SOURCE, outcome)
        assertEquals(0, db.normalizedTransactionDao().count())
        assertNull(db.rawEventDao().byId(event.id))
    }

    /** 005 FR-014/T023 — a shipped parser-only identifier (SampleBank) captures nothing. */
    @Test
    fun `former parser-only identifier is ignored without a source`() = runTest {
        configure()
        val event = rawEvent(pkg = "com.sample.bank", text = "خرید مبلغ ۵۰۰٬۰۰۰ ریال فروشگاه")

        val outcome = pipeline().process(event)

        assertEquals(Outcome.IGNORED_NO_SOURCE, outcome)
        assertEquals(0, db.normalizedTransactionDao().count())
        assertNull(db.rawEventDao().byId(event.id))
    }

    /** 005 FR-017 — a disabled source stops capture (sources are the only gate). */
    @Test
    fun `disabled source is ignored`() = runTest {
        configure()
        addSource(enabled = false)
        val event = rawEvent()

        val outcome = pipeline().process(event)

        assertEquals(Outcome.IGNORED_NO_SOURCE, outcome)
        assertEquals(0, db.normalizedTransactionDao().count())
        assertNull(db.rawEventDao().byId(event.id))
    }

    /** Master toggle still gates (FR-016): notification capture disabled → nothing captured. */
    @Test
    fun `capture toggle off captures nothing`() = runTest {
        configure(notificationEnabled = false)
        addSource()
        val outcome = pipeline().process(rawEvent())

        assertEquals(Outcome.IGNORED_DISABLED, outcome)
        assertEquals(0, db.normalizedTransactionDao().count())
    }

    /** 005 — a matching enabled source whose template fails → retained parse_error, not dropped. */
    @Test
    fun `template mismatch on a matching source retained as parse_error`() = runTest {
        configure()
        addSource()
        val event = rawEvent(text = "سلام، این یک پیام بی‌ربط است")

        val outcome = pipeline().process(event)

        assertEquals(Outcome.RETAINED_PARSE_ERROR, outcome)
        assertEquals(0, db.normalizedTransactionDao().count())
        assertTrue(db.rawEventDao().byId(event.id) != null)
        val record = db.deliveryRecordDao().byId(event.id)
        assertEquals(DeliveryState.PARSED.name.lowercase(), record?.state)
        assertEquals("parse_error", record?.errorCategory)
    }

    /** Same real transaction captured twice from the same source → second collapses. */
    @Test
    fun `identical re-capture short-circuits locally`() = runTest {
        configure()
        addRialAccount()
        addSource()
        val pipeline = pipeline()

        val first = pipeline.process(rawEvent())
        assertEquals(Outcome.QUEUED, first)

        // A second identical capture (different raw event id) — exact fingerprint dedup.
        val second = pipeline.process(rawEvent())
        assertEquals(Outcome.DUPLICATE_SHORT_CIRCUITED, second)
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
