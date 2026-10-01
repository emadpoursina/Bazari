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
import com.gomoney.capture.storage.NormalizedTransaction
import com.gomoney.capture.storage.RawEvent
import com.gomoney.capture.storage.ServerAccount
import com.gomoney.capture.storage.SettingsRepository
import com.gomoney.capture.storage.TransactionSource
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bound-account pipeline tests (T030; FR-013/015, SC-004) plus 005 US1
 * currency inheritance (T015, FR-001/002/003, SC-001/SC-007): a source bound
 * to a server account records its captured transactions against that account,
 * and the transaction's currency is that account's currency — never a
 * hardcoded rial default. IRR-bound sources still produce IRR captures
 * (because the account uses rial, not because of any global default).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BoundAccountPipelineTest {

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var sourceRepository: TransactionSourceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsRepository(context) { context.getSharedPreferences("test-secure", Context.MODE_PRIVATE) }
        sourceRepository = TransactionSourceRepository(db.transactionSourceDao(), db.parseErrorDao(), db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun pipeline(captured: MutableList<String> = mutableListOf()) = CapturePipeline(
        db = db,
        settings = settings,
        deliveryRepository = DeliveryRepository(db.deliveryRecordDao()),
        dedupRepository = DedupRepository(db.dedupCacheDao()),
        onCaptured = { captured.add(it.id) },
        sourceRepository = sourceRepository,
        userSourceParser = UserSourceParser(ParseErrorRepository(db.parseErrorDao()), db.serverAccountDao()),
    )

    private fun account(id: Int, label: String, currency: String) = ServerAccount(
        id = id,
        label = label,
        currency = currency,
        type = "asset",
        isDefault = false,
        refreshedAt = "2026-09-24T00:00:00+03:30",
    )

    private suspend fun seedAccounts(vararg accounts: ServerAccount) {
        db.serverAccountDao().replaceAll(accounts.toList())
    }

    private suspend fun addSource(boundAccountId: Int?): TransactionSource {
        val source = TransactionSource(
            id = UUID.randomUUID().toString(),
            name = "Bound Bank",
            identifier = "com.example.bank",
            channel = "notification",
            enabled = true,
            template = "خرید مبلغ {amount} ریال {direction}",
            incomeKeywords = "واریز",
            expenseKeywords = "خرید",
            boundAccountId = boundAccountId,
            boundAccountLabel = boundAccountId?.let { "Account $it" },
            createdAt = "2026-09-24T00:00:00+03:30",
            updatedAt = "2026-09-24T00:00:00+03:30",
        )
        sourceRepository.save(source)
        return source
    }

    private fun rawEvent(amountDigits: String) = RawEvent(
        id = "evt-" + UUID.randomUUID(),
        source = "notification",
        sourcePackage = "com.example.bank",
        bank = null,
        title = "Example Bank",
        text = "خرید مبلغ $amountDigits ریال فروشگاه",
        postedAt = "2026-09-24T08:00:00+03:30",
        capturedAt = "2026-09-24T08:00:00+03:30",
    )

    private suspend fun configure() {
        settings.setNotificationCaptureEnabled(true)
        settings.setServerUrl("http://192.168.1.10:8787")
        settings.setBearerToken("test-token")
    }

    private suspend fun capturedTx(): NormalizedTransaction =
        db.normalizedTransactionDao().observeRecent(10).first().single()

    @Test
    fun `bound source records the transaction against the bound account`() = runTest {
        configure()
        seedAccounts(account(7, "Rial Account", "IRR"))
        addSource(boundAccountId = 7)
        val captured = mutableListOf<String>()

        val outcome = pipeline(captured).process(rawEvent("۵۰۰٬۰۰۰"))

        assertEquals(Outcome.QUEUED, outcome)
        val tx = capturedTx()
        assertEquals(7, tx.accountId)
        // SC-001/FR-002: the currency is the bound account's — here IRR because
        // the account uses rial, never as a global default.
        assertEquals("IRR", tx.currency)
    }

    /** 005 T015/SC-001/SC-007: a bound USD account stamps USD, not rial. */
    @Test
    fun `bound non-IRR source records the account's currency`() = runTest {
        configure()
        seedAccounts(account(9, "USD Wallet", "USD"))
        addSource(boundAccountId = 9)
        val captured = mutableListOf<String>()

        val outcome = pipeline(captured).process(rawEvent("۵۰۰٬۰۰۰"))

        assertEquals(Outcome.QUEUED, outcome)
        val tx = capturedTx()
        assertEquals(9, tx.accountId)
        assertEquals("USD", tx.currency)
        assertNotEquals("IRR", tx.currency)
        // Delivered via the queued outbox (BridgeClient sends USD unchanged).
        assertEquals("queued", db.deliveryRecordDao().observeRecent(10).first().single().state)
    }

    /** 005 SC-007: two sources bound to different accounts each carry their own currency. */
    @Test
    fun `two sources bound to different accounts carry their own currencies`() = runTest {
        configure()
        seedAccounts(account(7, "Rial Account", "IRR"), account(9, "USD Wallet", "USD"))
        val rialSource = TransactionSource(
            id = "src-rial",
            name = "Rial Bank",
            identifier = "com.rial.bank",
            channel = "notification",
            enabled = true,
            template = "خرید مبلغ {amount} ریال {direction}",
            incomeKeywords = "واریز",
            expenseKeywords = "خرید",
            boundAccountId = 7,
            boundAccountLabel = "Rial Account",
            createdAt = "2026-09-24T00:00:00+03:30",
            updatedAt = "2026-09-24T00:00:00+03:30",
        )
        val usdSource = TransactionSource(
            id = "src-usd",
            name = "USD Bank",
            identifier = "com.usd.bank",
            channel = "notification",
            enabled = true,
            template = "خرید مبلغ {amount} ریال {direction}",
            incomeKeywords = "واریز",
            expenseKeywords = "خرید",
            boundAccountId = 9,
            boundAccountLabel = "USD Wallet",
            createdAt = "2026-09-24T00:00:00+03:30",
            updatedAt = "2026-09-24T00:00:00+03:30",
        )
        sourceRepository.save(rialSource)
        sourceRepository.save(usdSource)
        val pipeline = pipeline()

        pipeline.process(rawEvent("۵۰۰٬۰۰۰").copy(sourcePackage = "com.rial.bank"))
        pipeline.process(rawEvent("۶۰۰٬۰۰۰").copy(sourcePackage = "com.usd.bank"))

        val rows = db.normalizedTransactionDao().observeRecent(10).first()
        assertEquals("IRR", rows.single { it.sourceId == rialSource.id }.currency)
        assertEquals("USD", rows.single { it.sourceId == usdSource.id }.currency)
    }

    @Test
    fun `unbound source records a null account so the bridge mapping applies`() = runTest {
        configure()
        addSource(boundAccountId = null)
        val captured = mutableListOf<String>()

        val outcome = pipeline(captured).process(rawEvent("۶۰۰٬۰۰۰"))

        // 005 FR-010: unbound captures are HELD with empty currency, not queued.
        assertEquals(Outcome.HELD, outcome)
        val tx = capturedTx()
        assertNull(tx.accountId)
        assertEquals("", tx.currency)
        assertEquals("held", db.deliveryRecordDao().observeRecent(10).first().single().state)
    }
}
