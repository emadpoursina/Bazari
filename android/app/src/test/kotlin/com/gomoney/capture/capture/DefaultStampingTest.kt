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
import com.gomoney.capture.storage.ServerAccount
import com.gomoney.capture.storage.ServerCategory
import com.gomoney.capture.storage.SettingsRepository
import com.gomoney.capture.storage.TransactionSource
import java.util.UUID
import kotlinx.coroutines.flow.first
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
 * 008 T005/T016 — capture-time default stamping (FR-007, FR-010, FR-007b;
 * contracts/defaults-stamping.md §2): fill-if-null, catalog validation,
 * `assignmentSyncState='pending'` only when stamped, held rows stamped but
 * never expedited, and no currency comparison anywhere (FR-007b).
 *
 * Follows the CapturePipelineTest pattern (research R9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultStampingTest {

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
        settings = SettingsRepository(context) {
            context.getSharedPreferences("test-stamping", Context.MODE_PRIVATE)
        }
        deliveryRepository = DeliveryRepository(db.deliveryRecordDao())
        dedupRepository = DedupRepository(db.dedupCacheDao())
        sourceRepository = TransactionSourceRepository(db.transactionSourceDao(), db.parseErrorDao())
        userSourceParser = UserSourceParser(ParseErrorRepository(db.parseErrorDao()), db.serverAccountDao())
        // Process-wide DataStore file: start every test from "no defaults".
        kotlinx.coroutines.runBlocking {
            settings.clearDefaultCategory()
            settings.clearDefaultAccount()
        }
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.runBlocking {
            settings.clearDefaultCategory()
            settings.clearDefaultAccount()
        }
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
        id: String = "evt-" + UUID.randomUUID(),
    ) = RawEvent(
        id = id,
        source = "notification",
        sourcePackage = pkg,
        bank = null,
        title = "بانک ملت",
        text = text,
        postedAt = postedAt,
        capturedAt = postedAt,
    )

    private suspend fun configure() {
        settings.setNotificationCaptureEnabled(true)
        settings.setSmsCaptureEnabled(false)
        settings.setServerUrl("http://192.168.1.10:8787")
        settings.setBearerToken("test-token")
    }

    private suspend fun addSource(
        boundAccountId: Int? = 7,
        identifier: String = "ir.mellat.mellatab",
    ): TransactionSource {
        val source = TransactionSource(
            id = UUID.randomUUID().toString(),
            name = "Test Bank",
            identifier = identifier,
            channel = "notification",
            enabled = true,
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

    /** Cached catalog: rial account (bound) + a USD account + a category. */
    private suspend fun seedCatalog() {
        db.serverAccountDao().replaceAll(
            listOf(
                ServerAccount(
                    id = 7,
                    label = "Rial Account",
                    currency = "IRR",
                    type = "asset",
                    isDefault = false,
                    refreshedAt = "2026-09-24T00:00:00+03:30",
                ),
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
        db.serverCategoryDao().replaceAll(
            listOf(
                ServerCategory(
                    id = 42,
                    label = "Groceries",
                    refreshedAt = "2026-09-24T00:00:00+03:30",
                ),
            ),
        )
    }

    // --- integration: stamping inside CapturePipeline.processSource ---

    /** FR-007: defaults set + valid catalog → both stamped + pending. */
    @Test
    fun `valid defaults are stamped on a new capture and marked pending`() = runTest {
        configure()
        seedCatalog()
        addSource()
        settings.setDefaultCategoryId(42)
        settings.setDefaultAccountId(9)
        val captured = mutableListOf<String>()

        val event = rawEvent()
        val outcome = pipeline(captured).process(event)

        assertEquals(Outcome.QUEUED, outcome)
        val tx = db.normalizedTransactionDao().bySourceEventId(event.id)
            ?: error("captured row missing")
        assertEquals(42, tx.categoryId)
        assertEquals(9, tx.destinationAccountId)
        assertEquals("pending", tx.assignmentSyncState)
        // Invariants: nothing else touched.
        assertEquals("IRR", tx.currency)
        assertNull(tx.userMemo)
        assertEquals("synced", tx.memoSyncState)
        // Queued rows keep the existing expedited follow-up (R5).
        assertEquals(listOf(tx.id), captured)
    }

    /** No defaults → fields null and assignmentSyncState stays 'synced'. */
    @Test
    fun `capture without defaults keeps null assignment and synced state`() = runTest {
        configure()
        seedCatalog()
        addSource()

        val event = rawEvent()
        val outcome = pipeline().process(event)

        assertEquals(Outcome.QUEUED, outcome)
        val tx = db.normalizedTransactionDao().bySourceEventId(event.id)
            ?: error("captured row missing")
        assertNull(tx.categoryId)
        assertNull(tx.destinationAccountId)
        assertEquals("synced", tx.assignmentSyncState)
    }

    /** FR-010: stale default ID → not stamped; stored value untouched (SC-006). */
    @Test
    fun `stale default id is not stamped and the stored default stays put`() = runTest {
        configure()
        seedCatalog()
        addSource()
        // 999 resolves in neither catalog table.
        settings.setDefaultCategoryId(999)
        settings.setDefaultAccountId(999)

        val event = rawEvent()
        val outcome = pipeline().process(event)

        assertEquals(Outcome.QUEUED, outcome)
        val tx = db.normalizedTransactionDao().bySourceEventId(event.id)
            ?: error("captured row missing")
        assertNull(tx.categoryId)
        assertNull(tx.destinationAccountId)
        assertEquals("synced", tx.assignmentSyncState)
        // The stored default is not modified or deleted (contracts §2 row 3).
        assertEquals(999, settings.currentDefaults().defaultCategoryId)
        assertEquals(999, settings.currentDefaults().defaultAccountId)
    }

    /** FR-008: stamping runs only at capture — Outcome distribution unchanged. */
    @Test
    fun `outcome distribution is unaffected by stamping`() = runTest {
        configure()
        seedCatalog()
        settings.setDefaultCategoryId(42)
        settings.setDefaultAccountId(9)
        addSource(boundAccountId = 7)
        addSource(boundAccountId = null, identifier = "com.unbound.bank")

        val queued = pipeline().process(rawEvent())
        val held = pipeline().process(
            // Same parseable text — blank currency comes from the unbound
            // source (T027 hold), not from a parse failure.
            rawEvent(pkg = "com.unbound.bank"),
        )

        assertEquals(Outcome.QUEUED, queued)
        assertEquals(Outcome.HELD, held)
    }

    /**
     * R5 / held-row exception (spec FR-007a as resolved by research): a
     * blank-currency (held) row IS stamped pending at insert but gets NO
     * expedited sync — onCaptured (the enqueue hook) must not fire.
     */
    @Test
    fun `held blank-currency row is stamped pending but not expedited`() = runTest {
        configure()
        seedCatalog()
        addSource(boundAccountId = null)
        settings.setDefaultCategoryId(42)
        settings.setDefaultAccountId(9)
        val captured = mutableListOf<String>()

        val event = rawEvent()
        val outcome = pipeline(captured).process(event)

        assertEquals(Outcome.HELD, outcome)
        assertTrue(captured.isEmpty()) // T027: no expedited sync for held rows
        val tx = db.normalizedTransactionDao().bySourceEventId(event.id)
            ?: error("captured row missing")
        assertEquals(42, tx.categoryId)
        assertEquals(9, tx.destinationAccountId)
        assertEquals("pending", tx.assignmentSyncState)
        assertEquals("held", db.deliveryRecordDao().byId(tx.id)?.state)
    }

    /** FR-007b (T016): default account in another currency is stamped anyway. */
    @Test
    fun `default account with a different currency is stamped with no warning path`() = runTest {
        configure()
        seedCatalog()
        addSource(boundAccountId = 7) // tx.currency = IRR
        settings.setDefaultAccountId(9) // USD wallet
        settings.setDefaultCategoryId(42)

        val event = rawEvent()
        val outcome = pipeline().process(event)

        assertEquals(Outcome.QUEUED, outcome)
        val tx = db.normalizedTransactionDao().bySourceEventId(event.id)
            ?: error("captured row missing")
        // No currency comparison anywhere: destination stamped, currency kept.
        assertEquals(9, tx.destinationAccountId)
        assertEquals("IRR", tx.currency)
        assertEquals("pending", tx.assignmentSyncState)
        assertEquals(Outcome.QUEUED, outcome)
    }

    /** Half-stamped case: one valid default + one stale → only the valid one. */
    @Test
    fun `only the valid default is stamped when the other id is stale`() = runTest {
        configure()
        seedCatalog()
        addSource()
        settings.setDefaultCategoryId(42) // valid
        settings.setDefaultAccountId(999) // stale

        val event = rawEvent()
        pipeline().process(event)

        val tx = db.normalizedTransactionDao().bySourceEventId(event.id)
            ?: error("captured row missing")
        assertEquals(42, tx.categoryId)
        assertNull(tx.destinationAccountId)
        assertEquals("pending", tx.assignmentSyncState)
    }

    // --- direct: the pure fill-if-null helper (research R9) ---

    /** Existing (non-null) assignment wins over both defaults (spec Assumption). */
    @Test
    fun `pre-existing assignment is never overwritten by defaults`() = runTest {
        val existing = baseTx().copy(
            categoryId = 5,
            destinationAccountId = 6,
            assignmentSyncState = "synced",
        )

        val stamped = existing.stampWithDefaults(
            defaultCategoryId = 42,
            defaultAccountId = 9,
            categoryExists = { true },
            accountExists = { true },
        )

        assertEquals(5, stamped.categoryId)
        assertEquals(6, stamped.destinationAccountId)
        // Nothing was stamped → state untouched (stays synced).
        assertEquals("synced", stamped.assignmentSyncState)
    }

    /** Half-filled row: only the null field is filled, then pending. */
    @Test
    fun `only the null field is filled on a half-assigned row`() = runTest {
        val existing = baseTx().copy(categoryId = 5)

        val stamped = existing.stampWithDefaults(
            defaultCategoryId = 42,
            defaultAccountId = 9,
            categoryExists = { true },
            accountExists = { true },
        )

        assertEquals(5, stamped.categoryId)
        assertEquals(9, stamped.destinationAccountId)
        assertEquals("pending", stamped.assignmentSyncState)
    }

    /** No defaults at all → the row is returned untouched. */
    @Test
    fun `no defaults leaves the row byte-identical`() = runTest {
        val existing = baseTx()

        val stamped = existing.stampWithDefaults(
            defaultCategoryId = null,
            defaultAccountId = null,
            categoryExists = { true },
            accountExists = { true },
        )

        assertEquals(existing, stamped)
    }

    private fun baseTx() = com.gomoney.capture.storage.NormalizedTransaction(
        id = "tx-test",
        sourceEventId = "evt-test",
        source = "notification",
        bank = "user",
        accountHint = "****1234",
        type = "expense",
        amountMinor = 500_000L,
        currency = "IRR",
        txAt = "2026-09-23T20:31:22+03:30",
        description = "shop",
        rawTextRef = "evt-test",
        fingerprint = "a".repeat(64),
        parserName = "UserSourceParser",
        confidence = "HIGH",
    )
}
