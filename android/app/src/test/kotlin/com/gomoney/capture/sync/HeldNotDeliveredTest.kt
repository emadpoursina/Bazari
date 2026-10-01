package com.gomoney.capture.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.capture.CapturePipeline
import com.gomoney.capture.capture.Outcome
import com.gomoney.capture.source.ParseErrorRepository
import com.gomoney.capture.source.TransactionSourceRepository
import com.gomoney.capture.source.UserSourceParser
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.DedupRepository
import com.gomoney.capture.storage.DeliveryRepository
import com.gomoney.capture.storage.RawEvent
import com.gomoney.capture.source.SaveResult
import com.gomoney.capture.storage.ServerAccount
import com.gomoney.capture.storage.SettingsRepository
import com.gomoney.capture.storage.TransactionSource
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
 * 005 T030 / FR-010, SC-005: captures from an unbound source are HELD with an
 * empty currency (no invented rial), and the sync engine never sends a held
 * row — even when the bridge is reachable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeldNotDeliveredTest {

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var deliveryRepository: DeliveryRepository
    private lateinit var dedupRepository: DedupRepository
    private lateinit var sourceRepository: TransactionSourceRepository
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsRepository(context) { context.getSharedPreferences("test-secure", Context.MODE_PRIVATE) }
        deliveryRepository = DeliveryRepository(db.deliveryRecordDao())
        dedupRepository = DedupRepository(db.dedupCacheDao())
        sourceRepository = TransactionSourceRepository(db.transactionSourceDao(), db.parseErrorDao(), db)

        server = MockWebServer()
        server.start()
        kotlinx.coroutines.runBlocking {
            settings.setNotificationCaptureEnabled(true)
            settings.setServerUrl(server.url("/").toString().removeSuffix("/"))
            settings.setBearerToken("test-token")
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun pipeline(captured: MutableList<String> = mutableListOf()) = CapturePipeline(
        db = db,
        settings = settings,
        deliveryRepository = deliveryRepository,
        dedupRepository = dedupRepository,
        onCaptured = { captured.add(it.id) },
        sourceRepository = sourceRepository,
        userSourceParser = UserSourceParser(ParseErrorRepository(db.parseErrorDao()), db.serverAccountDao()),
    )

    private suspend fun addUnboundSource(): TransactionSource {
        val source = TransactionSource(
            id = "src-1",
            name = "Unbound Bank",
            identifier = "com.example.bank",
            channel = "notification",
            enabled = true,
            template = "خرید مبلغ {amount} ریال {direction}",
            incomeKeywords = "واریز",
            expenseKeywords = "خرید",
            boundAccountId = null,
            boundAccountLabel = null,
            createdAt = "2026-09-24T00:00:00+03:30",
            updatedAt = "2026-09-24T00:00:00+03:30",
        )
        assertTrue(sourceRepository.save(source) is SaveResult.Saved)
        return source
    }

    /** Unbound capture: held, empty currency, no expedited-sync enqueue. */
    @Test
    fun `unbound capture is held with empty currency and no sync enqueue`() = runTest {
        addUnboundSource()
        val captured = mutableListOf<String>()
        val event = RawEvent(
            id = "e1",
            source = "notification",
            sourcePackage = "com.example.bank",
            bank = null,
            title = "Bank",
            text = "خرید مبلغ ۵۰۰٬۰۰۰ ریال فروشگاه",
            postedAt = "2026-09-24T08:00:00+03:30",
            capturedAt = "2026-09-24T08:00:00+03:30",
        )

        val outcome = pipeline(captured).process(event)

        assertEquals(Outcome.HELD, outcome)
        // No onCaptured call → no expedited sync enqueue (T027).
        assertTrue(captured.isEmpty())
        val tx = db.normalizedTransactionDao().bySourceEventId("e1")!!
        assertEquals("", tx.currency)
        assertNull(tx.accountId)
        assertEquals("held", db.deliveryRecordDao().bySourceEventId("e1").single().state)
    }

    /** Held rows are not drained even when the bridge answers 201. */
    @Test
    fun `sync engine skips held rows even when the bridge is reachable`() = runTest {
        addUnboundSource()
        assertEquals(Outcome.HELD, pipeline().process(
            RawEvent(
                id = "e1",
                source = "notification",
                sourcePackage = "com.example.bank",
                bank = null,
                title = "Bank",
                text = "خرید مبلغ ۵۰۰٬۰۰۰ ریال فروشگاه",
                postedAt = "2026-09-24T08:00:00+03:30",
                capturedAt = "2026-09-24T08:00:00+03:30",
            ),
        ))
        assertEquals(0, db.deliveryRecordDao().pendingForDelivery().size)

        // A reachable bridge (queued 201 response) must NOT touch the held row.
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"status":"created","gomoneyTxnId":7}"""))
        SyncEngine.drain(
            db,
            settings.current(),
            deliveryRepository,
            dedupRepository,
        )

        assertEquals("held", db.deliveryRecordDao().bySourceEventId("e1").single().state)
        assertEquals(0, server.requestCount)
        assertNull(dedupRepository.lookup(db.normalizedTransactionDao().bySourceEventId("e1")!!.fingerprint))
    }

    /** The bridge bound-currency sanity: stamp-then-drain delivers the stamped row (covered in BindStampTest). */
    @Test
    fun `stamped held row becomes queued and is drained to sent`() = runTest {
        addUnboundSource()
        assertEquals(Outcome.HELD, pipeline().process(
            RawEvent(
                id = "e1",
                source = "notification",
                sourcePackage = "com.example.bank",
                bank = null,
                title = "Bank",
                text = "خرید مبلغ ۵۰۰٬۰۰۰ ریال فروشگاه",
                postedAt = "2026-09-24T08:00:00+03:30",
                capturedAt = "2026-09-24T08:00:00+03:30",
            ),
        ))

        // Bind → stamp held → queued.
        db.serverAccountDao().replaceAll(
            listOf(
                ServerAccount(
                    id = 9,
                    label = "USD Wallet",
                    currency = "USD",
                    type = "asset",
                    isDefault = false,
                    refreshedAt = "2026-09-24T00:00:00+03:30",
                ),
            ),
        )
        val bound = db.transactionSourceDao().byId("src-1")!!.copy(
            boundAccountId = 9,
            boundAccountLabel = "USD Wallet",
        )
        val result = sourceRepository.save(bound) as SaveResult.Saved
        assertEquals(1, result.stampedHeld)
        assertEquals("queued", db.deliveryRecordDao().bySourceEventId("e1").single().state)

        // Drain delivers the stamped row with its currency.
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"status":"created","gomoneyTxnId":7}"""))
        SyncEngine.drain(db, settings.current(), deliveryRepository, dedupRepository)

        assertEquals("sent", db.deliveryRecordDao().bySourceEventId("e1").single().state)
        assertEquals(1, server.requestCount)
    }
}
