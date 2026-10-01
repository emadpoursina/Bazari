package com.gomoney.capture.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.capture.CapturePipeline
import com.gomoney.capture.capture.Outcome
import com.gomoney.capture.model.Channel
import com.gomoney.capture.source.ParseErrorRepository
import com.gomoney.capture.source.TransactionSourceRepository
import com.gomoney.capture.source.UserSourceParser
import com.gomoney.capture.storage.SettingsRepository
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
import java.util.UUID

/**
 * Source lifecycle tests (T051; FR-003, SC-008): disabling stops capture while
 * other sources keep working, re-enabling resumes, and removing a source keeps
 * already-captured transactions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceLifecycleTest {

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var sourceRepository: TransactionSourceRepository
    private lateinit var parseErrorRepository: ParseErrorRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsRepository(context) { context.getSharedPreferences("test-secure", Context.MODE_PRIVATE) }
        sourceRepository = TransactionSourceRepository(db.transactionSourceDao(), db.parseErrorDao())
        parseErrorRepository = ParseErrorRepository(db.parseErrorDao())
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
        userSourceParser = UserSourceParser(parseErrorRepository),
    )

    private suspend fun addSource(identifier: String, name: String): TransactionSource {
        val source = TransactionSource(
            id = UUID.randomUUID().toString(),
            name = name,
            identifier = identifier,
            channel = "notification",
            enabled = true,
            template = "خرید مبلغ {amount} ریال {direction}",
            incomeKeywords = "واریز",
            expenseKeywords = "خرید",
            createdAt = "2026-09-24T00:00:00+03:30",
            updatedAt = "2026-09-24T00:00:00+03:30",
        )
        sourceRepository.save(source)
        return source
    }

    private fun rawEvent(pkg: String, amountDigits: String, second: Int = 0) = RawEvent(
        id = "evt-" + UUID.randomUUID(),
        source = "notification",
        sourcePackage = pkg,
        bank = null,
        title = "Bank",
        text = "خرید مبلغ $amountDigits ریال فروشگاه",
        postedAt = "2026-09-24T08:00:0$second+03:30",
        capturedAt = "2026-09-24T08:00:0$second+03:30",
    )

    private fun nonMatchingEvent() = RawEvent(
        id = "evt-" + UUID.randomUUID(),
        source = "notification",
        sourcePackage = "com.example.bank",
        bank = null,
        title = "Bank",
        text = "خرید مبلغ ریال فروشگاه",
        postedAt = "2026-09-24T08:00:01+03:30",
        capturedAt = "2026-09-24T08:00:01+03:30",
    )

    private suspend fun configure() {
        settings.setNotificationCaptureEnabled(true)
        settings.setServerUrl("http://192.168.1.10:8787")
        settings.setBearerToken("test-token")
    }

    @Test
    fun `disable stops capture while other sources keep working, re-enable resumes`() = runTest {
        configure()
        val disabled = addSource("com.example.disabled", "Disabled Bank")
        val active = addSource("com.example.active", "Active Bank")
        val pipeline = pipeline()

        assertEquals(Outcome.HELD, pipeline.process(rawEvent(disabled.identifier, "۵۰۰٬۰۰۰")))

        sourceRepository.setEnabled(disabled.id, false)
        // The disabled source is ignored...
        assertEquals(
            Outcome.IGNORED_NO_SOURCE,
            pipeline.process(rawEvent(disabled.identifier, "۶۰۰٬۰۰۰")),
        )
        // ...while the other source is unaffected.
        assertEquals(Outcome.HELD, pipeline.process(rawEvent(active.identifier, "۷۰۰٬۰۰۰")))
        assertEquals(2, db.normalizedTransactionDao().count())

        sourceRepository.setEnabled(disabled.id, true)
        assertEquals(Outcome.HELD, pipeline.process(rawEvent(disabled.identifier, "۸۰۰٬۰۰۰")))
        assertEquals(3, db.normalizedTransactionDao().count())
    }

    @Test
    fun `removing a source retains captured transactions and nulls parse-error references`() = runTest {
        configure()
        val source = addSource("com.example.bank", "Example Bank")
        val captured = mutableListOf<String>()
        val pipeline = pipeline(captured)

        assertEquals(Outcome.HELD, pipeline.process(rawEvent(source.identifier, "۵۰۰٬۰۰۰")))
        assertEquals(Outcome.RETAINED_PARSE_ERROR, pipeline.process(nonMatchingEvent()))
        assertEquals(1, parseErrorRepository.all().size)

        assertTrue(sourceRepository.remove(source.id))

        assertNull(sourceRepository.byId(source.id))
        assertNull(sourceRepository.resolve(source.identifier, Channel.NOTIFICATION))
        // Already-captured transactions survive and keep their source reference.
        assertEquals(1, db.normalizedTransactionDao().count())
        assertEquals(source.id, db.normalizedTransactionDao().observeRecent(10).first().single().sourceId)
        // The review-list entry survives with its source reference cleared.
        assertNull(parseErrorRepository.all().single().sourceId)
        // Its messages are ignored after removal.
        assertEquals(
            Outcome.IGNORED_NO_SOURCE,
            pipeline.process(rawEvent(source.identifier, "۹۰۰٬۰۰۰")),
        )
        assertEquals(1, db.normalizedTransactionDao().count())
    }
}
