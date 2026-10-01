package com.gomoney.capture.capture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.model.Channel
import com.gomoney.capture.model.Direction
import com.gomoney.capture.source.MatchResult
import com.gomoney.capture.source.ParseErrorRepository
import com.gomoney.capture.source.TemplateMatcher
import com.gomoney.capture.source.TransactionSourceRepository
import com.gomoney.capture.source.UserSourceParser
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.DedupRepository
import com.gomoney.capture.storage.DeliveryRepository
import com.gomoney.capture.storage.RawEvent
import com.gomoney.capture.storage.SettingsRepository
import com.gomoney.capture.storage.TransactionSource
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
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
 * User-source pipeline tests (T017; FR-004/010/022/024, SC-002/003): source
 * resolution, capture, parse-error retention, non-source ignore, channel
 * mismatch ignore — plus the T014 fixtures run through the template contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserSourcePipelineTest {

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var deliveryRepository: DeliveryRepository
    private lateinit var dedupRepository: DedupRepository
    private lateinit var sourceRepository: TransactionSourceRepository
    private lateinit var parseErrorRepository: ParseErrorRepository
    private lateinit var userSourceParser: UserSourceParser

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsRepository(context) { context.getSharedPreferences("test-secure", Context.MODE_PRIVATE) }
        deliveryRepository = DeliveryRepository(db.deliveryRecordDao())
        dedupRepository = DedupRepository(db.dedupCacheDao())
        sourceRepository = TransactionSourceRepository(db.transactionSourceDao(), db.parseErrorDao())
        parseErrorRepository = ParseErrorRepository(db.parseErrorDao())
        userSourceParser = UserSourceParser(parseErrorRepository)
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

    private suspend fun configure(notificationEnabled: Boolean = true) {
        settings.setNotificationCaptureEnabled(notificationEnabled)
        settings.setSmsCaptureEnabled(true)
        settings.setServerUrl("http://192.168.1.10:8787")
        settings.setBearerToken("test-token")
    }

    private suspend fun addSource(
        identifier: String = "com.example.bank",
        channel: String = "notification",
        template: String = "خرید مبلغ {amount} ریال {direction}",
        income: String = "واریز",
        expense: String = "خرید",
        enabled: Boolean = true,
        boundAccountId: Int? = null,
    ): TransactionSource {
        val source = TransactionSource(
            id = UUID.randomUUID().toString(),
            name = "Example Bank",
            identifier = identifier,
            channel = channel,
            enabled = enabled,
            template = template,
            incomeKeywords = income,
            expenseKeywords = expense,
            boundAccountId = boundAccountId,
            boundAccountLabel = boundAccountId?.let { "Account $it" },
            createdAt = "2026-09-24T00:00:00+03:30",
            updatedAt = "2026-09-24T00:00:00+03:30",
        )
        sourceRepository.save(source)
        return source
    }

    private fun rawEvent(
        pkg: String = "com.example.bank",
        text: String = "خرید مبلغ ۵۰۰٬۰۰۰ ریال فروشگاه",
        source: String = "notification",
        postedAt: String = "2026-09-24T08:00:00+03:30",
        id: String = "evt-" + UUID.randomUUID(),
    ) = RawEvent(
        id = id,
        source = source,
        sourcePackage = pkg,
        bank = null,
        title = "Example Bank",
        text = text,
        postedAt = postedAt,
        capturedAt = postedAt,
    )

    @Test
    fun `matching user source message is captured with direction and amount`() = runTest {
        configure()
        val source = addSource()
        val captured = mutableListOf<String>()

        val outcome = pipeline(captured).process(rawEvent())

        // 005 FR-009/010: the source is unbound → the capture is HELD with an
        // empty currency (no invented rial), and never queued for delivery.
        assertEquals(Outcome.HELD, outcome)
        assertEquals(1, db.normalizedTransactionDao().count())
        val tx = db.normalizedTransactionDao().observeRecent(10).first().single()
        assertEquals(source.id, tx.sourceId)
        assertEquals("expense", tx.type)
        assertEquals(500_000L, tx.amountMinor)
        assertEquals("", tx.currency)
        assertEquals("UserSourceParser", tx.parserName)
        assertNull(tx.accountId)
        assertEquals("held", db.deliveryRecordDao().bySourceEventId(tx.sourceEventId).single().state)
        assertTrue(parseErrorRepository.all().isEmpty())
    }

    @Test
    fun `unrelated identifier is ignored and never persisted`() = runTest {
        configure()
        addSource()
        val event = rawEvent(pkg = "com.other.app", text = "خرید مبلغ ۱ ریال فروشگاه")

        val outcome = pipeline().process(event)

        assertEquals(Outcome.IGNORED_NO_SOURCE, outcome)
        assertEquals(0, db.normalizedTransactionDao().count())
        assertNull(db.rawEventDao().byId(event.id))
    }

    @Test
    fun `channel mismatch is ignored`() = runTest {
        configure()
        // Source is configured for SMS; the event arrives as a notification.
        addSource(channel = "sms")
        val event = rawEvent(source = "notification")

        val outcome = pipeline().process(event)

        assertEquals(Outcome.IGNORED_NO_SOURCE, outcome)
        assertEquals(0, db.normalizedTransactionDao().count())
        assertNull(db.rawEventDao().byId(event.id))
    }

    @Test
    fun `parse failure is retained with a sanitized reason and no transaction`() = runTest {
        configure()
        addSource()
        val event = rawEvent(text = "خرید مبلغ ریال فروشگاه")

        val outcome = pipeline().process(event)

        assertEquals(Outcome.RETAINED_PARSE_ERROR, outcome)
        assertEquals(0, db.normalizedTransactionDao().count())
        val error = parseErrorRepository.all().single()
        assertTrue(error.failureReason.contains("amount"))
        assertTrue(!error.failureReason.contains("فروشگاه"))
        // The raw message is retained locally for review (FR-010), never sent.
        assertTrue(db.rawEventDao().byId(event.id) != null)
    }

    @Test
    fun `disabled source stops capture until re-enabled`() = runTest {
        configure()
        val source = addSource(enabled = false)

        val ignored = pipeline().process(rawEvent(text = "خرید مبلغ ۹۰۰٬۰۰۰ ریال فروشگاه"))
        assertEquals(Outcome.IGNORED_NO_SOURCE, ignored)
        assertEquals(0, db.normalizedTransactionDao().count())

        sourceRepository.setEnabled(source.id, true)
        val captured = pipeline().process(rawEvent(text = "خرید مبلغ ۹۰۰٬۰۰۰ ریال فروشگاه"))
        assertEquals(Outcome.HELD, captured) // unbound → held (005)
        assertEquals(1, db.normalizedTransactionDao().count())
    }

    /** T014 fixtures exercise the documented template contract end-to-end. */
    @Test
    fun `user source fixtures match their expected contract`() {
        val dir = File("src/test/resources/fixtures/usersource")
        val files = dir.listFiles { file -> file.extension == "json" }?.sortedBy { it.name }.orEmpty()
        assertTrue("usersource fixtures missing", files.isNotEmpty())

        files.forEach { file ->
            val json = JSONObject(file.readText())
            val sourceJson = json.getJSONObject("source")
            val rawJson = json.getJSONObject("raw")
            val source = TransactionSource(
                id = "fixture",
                name = json.optString("name", file.name),
                identifier = sourceJson.getString("identifier"),
                channel = sourceJson.getString("channel"),
                enabled = true,
                template = sourceJson.getString("template"),
                incomeKeywords = sourceJson.getJSONArray("incomeKeywords").joinStrings(),
                expenseKeywords = sourceJson.getJSONArray("expenseKeywords").joinStrings(),
                createdAt = "2026-09-24T00:00:00+03:30",
                updatedAt = "2026-09-24T00:00:00+03:30",
            )
            val compiled = TemplateMatcher.compile(source.template)
            val result = TemplateMatcher.match(compiled, rawJson.getString("text"), source)

            if (json.has("expected")) {
                val expected = json.getJSONObject("expected")
                val success = result as MatchResult.Success
                assertEquals(
                    "${file.name}: direction",
                    Direction.valueOf(expected.getString("direction")),
                    success.direction,
                )
                assertEquals("${file.name}: amount", expected.getLong("amountMinor"), success.amountMinor)
            } else {
                val expected = json.getJSONObject("expectedFailure")
                val failure = result as MatchResult.Failure
                assertTrue(
                    "${file.name}: reason '${failure.reason}'",
                    failure.reason.contains(expected.getString("reason_contains"), ignoreCase = true),
                )
            }
        }
    }

    /** T061: capture-to-persist must stay comfortably under the 2 s budget (SC-002/006). */
    @Test
    fun `capture to queued stays under the two second budget`() = runTest {
        configure()
        addSource()

        val startedAt = System.nanoTime()
        val outcome = pipeline().process(rawEvent(text = "خرید مبلغ ۳۰۰٬۰۰۰ ریال فروشگاه"))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(Outcome.HELD, outcome) // unbound source → held (005)
        assertTrue("capture-to-persist took ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun `resolve returns sources by matching channel only`() = runTest {        val notification = addSource(identifier = "com.example.bank", channel = "notification")
        addSource(identifier = "EXAMPLEBANK", channel = "sms")

        assertEquals(notification.id, sourceRepository.resolve("com.example.bank", Channel.NOTIFICATION)?.id)
        assertNull(sourceRepository.resolve("com.example.bank", Channel.SMS))
        assertNull(sourceRepository.resolve("EXAMPLEBANK", Channel.NOTIFICATION))
    }

    private fun JSONArray.joinStrings(): String = (0 until length()).joinToString(",") { optString(it) }
}
