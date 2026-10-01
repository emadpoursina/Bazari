package com.gomoney.capture.source

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.capture.CapturePipeline
import com.gomoney.capture.capture.Outcome
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.DedupRepository
import com.gomoney.capture.storage.DeliveryRepository
import com.gomoney.capture.storage.NormalizedTransaction
import com.gomoney.capture.storage.RawEvent
import com.gomoney.capture.storage.ServerAccount
import com.gomoney.capture.storage.SettingsRepository
import com.gomoney.capture.storage.TransactionSource
import kotlinx.coroutines.flow.first
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
 * 005 T031 / FR-011, SC-008: binding a previously unbound source stamps the
 * bound account's currency onto still-held empty-currency rows and moves them
 * `held → queued` in one transaction; rows that already had a currency are
 * never rewritten, and a rebind does not rewrite stamped rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BindStampTest {

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

    private suspend fun seedAccounts(vararg accounts: ServerAccount) {
        db.serverAccountDao().replaceAll(accounts.toList())
    }

    private fun account(id: Int, label: String, currency: String) = ServerAccount(
        id = id,
        label = label,
        currency = currency,
        type = "asset",
        isDefault = false,
        refreshedAt = "2026-09-24T00:00:00+03:30",
    )

    private fun pipeline(captured: MutableList<String> = mutableListOf()) = CapturePipeline(
        db = db,
        settings = settings,
        deliveryRepository = DeliveryRepository(db.deliveryRecordDao()),
        dedupRepository = DedupRepository(db.dedupCacheDao()),
        onCaptured = { captured.add(it.id) },
        sourceRepository = sourceRepository,
        userSourceParser = UserSourceParser(ParseErrorRepository(db.parseErrorDao()), db.serverAccountDao()),
    )

    /** Draft for source id "src-1"; pass a different binding to rebind. */
    private fun sourceDraft(boundAccountId: Int? = null): TransactionSource = TransactionSource(
        id = "src-1",
        name = "Example Bank",
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

    private fun heldEvent(id: String, digits: String) = RawEvent(
        id = id,
        source = "notification",
        sourcePackage = "com.example.bank",
        bank = null,
        title = "Bank",
        text = "خرید مبلغ $digits ریال فروشگاه",
        postedAt = "2026-09-24T08:00:00+03:30",
        capturedAt = "2026-09-24T08:00:00+03:30",
    )

    private suspend fun txById(id: String): NormalizedTransaction? = db.normalizedTransactionDao().bySourceEventId(id)

    @Test
    fun `binding stamps held empty-currency rows and queues them`() = runTest {
        seedAccounts(account(9, "USD Wallet", "USD"))
        assertTrue(sourceRepository.save(sourceDraft(boundAccountId = null)) is SaveResult.Saved)
        val pipeline = pipeline()

        // Two unbound captures → held, empty currency, not queued.
        assertEquals(Outcome.HELD, pipeline.process(heldEvent("e1", "۱۰۰٬۰۰۰")))
        assertEquals(Outcome.HELD, pipeline.process(heldEvent("e2", "۲۰۰٬۰۰۰")))
        assertEquals(2, db.normalizedTransactionDao().count())
        assertTrue(
            db.deliveryRecordDao().observeRecent(10).first().all { it.state == "held" },
        )

        // Bind to the USD account in one save.
        val result = sourceRepository.save(sourceDraft(boundAccountId = 9)) as SaveResult.Saved
        assertEquals(2, result.stampedHeld)

        // Both held rows are stamped USD against account 9 and moved to queued.
        for (txId in listOf("e1", "e2")) {
            val tx = txById(txId)!!
            assertEquals("USD", tx.currency)
            assertEquals(9, tx.accountId)
            assertEquals("queued", db.deliveryRecordDao().bySourceEventId(txId).single().state)
        }
    }

    /** FR-008/FR-011: rows that already had a currency are never rewritten. */
    @Test
    fun `rows that already had a currency are not rewritten by a later bind`() = runTest {
        seedAccounts(account(7, "Rial Account", "IRR"), account(9, "USD Wallet", "USD"))
        // Source bound to IRR: captures carry IRR and are queued.
        assertTrue(sourceRepository.save(sourceDraft(boundAccountId = 7)) is SaveResult.Saved)
        val pipeline = pipeline()
        assertEquals(Outcome.QUEUED, pipeline.process(heldEvent("e1", "۵۰۰٬۰۰۰")))
        assertEquals("IRR", txById("e1")?.currency)
        assertEquals("queued", db.deliveryRecordDao().bySourceEventId("e1").single().state)

        // Rebind to USD: the IRR row keeps its currency and state.
        val result = sourceRepository.save(sourceDraft(boundAccountId = 9)) as SaveResult.Saved
        assertEquals(0, result.stampedHeld)
        assertEquals("IRR", txById("e1")?.currency)
        assertEquals("queued", db.deliveryRecordDao().bySourceEventId("e1").single().state)
    }

    /** FR-008: new captures after a rebind use the new account's currency. */
    @Test
    fun `new captures after a rebind use the new account's currency`() = runTest {
        seedAccounts(account(7, "Rial Account", "IRR"), account(9, "USD Wallet", "USD"))
        assertTrue(sourceRepository.save(sourceDraft(boundAccountId = 7)) is SaveResult.Saved)
        val pipeline = pipeline()
        assertEquals(Outcome.QUEUED, pipeline.process(heldEvent("e1", "۵۰۰٬۰۰۰")))
        assertEquals("IRR", txById("e1")?.currency)

        sourceRepository.save(sourceDraft(boundAccountId = 9))

        assertEquals(Outcome.QUEUED, pipeline.process(heldEvent("e2", "۶۰۰٬۰۰۰")))
        assertEquals("USD", txById("e2")?.currency)
        assertEquals(9, txById("e2")?.accountId)
    }

    /** FR-006: saving a binding to a blank-currency account stamps nothing. */
    @Test
    fun `binding to a blank-currency account stamps nothing`() = runTest {
        seedAccounts(account(3, "Currencyless", ""))
        assertTrue(sourceRepository.save(sourceDraft(boundAccountId = null)) is SaveResult.Saved)
        val pipeline = pipeline()
        assertEquals(Outcome.HELD, pipeline.process(heldEvent("e1", "۱۰۰٬۰۰۰")))

        val result = sourceRepository.save(sourceDraft(boundAccountId = 3)) as SaveResult.Saved
        assertEquals(0, result.stampedHeld)
        assertEquals("", txById("e1")?.currency)
        assertEquals("held", db.deliveryRecordDao().bySourceEventId("e1").single().state)
    }

    /** Stale (absent) account bindings stamp nothing. */
    @Test
    fun `binding to an account missing from the catalog stamps nothing`() = runTest {
        seedAccounts(account(7, "Rial Account", "IRR"))
        assertTrue(sourceRepository.save(sourceDraft(boundAccountId = null)) is SaveResult.Saved)
        val pipeline = pipeline()
        assertEquals(Outcome.HELD, pipeline.process(heldEvent("e1", "۱۰۰٬۰۰۰")))

        val result = sourceRepository.save(sourceDraft(boundAccountId = 99)) as SaveResult.Saved
        assertEquals(0, result.stampedHeld)
        assertEquals("", txById("e1")?.currency)
        assertEquals("held", db.deliveryRecordDao().bySourceEventId("e1").single().state)
    }
}
