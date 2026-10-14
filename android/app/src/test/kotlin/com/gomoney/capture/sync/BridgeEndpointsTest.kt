package com.gomoney.capture.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.DedupRepository
import com.gomoney.capture.storage.DeliveryRepository
import com.gomoney.capture.storage.NormalizedTransaction
import com.gomoney.capture.storage.RawEvent
import com.gomoney.capture.storage.ServerConfiguration
import com.gomoney.capture.storage.SettingsRepository
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Multiple bridge endpoints with ordered fallback: CRUD, ordering, legacy
 * migration, ping-first-reachable selection, and sync failover.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeEndpointsTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var primary: MockWebServer
    private lateinit var secondary: MockWebServer
    private val prefsName = "test-endpoints-${UUID.randomUUID()}"

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        SettingsRepository.dataStoreFor(context).edit { it.clear() }
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().apply()
        settings = SettingsRepository(context) {
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        }
        primary = MockWebServer()
        primary.start()
        secondary = MockWebServer()
        secondary.start()
    }

    @After
    fun tearDown() = runTest {
        primary.shutdown()
        secondary.shutdown()
        SettingsRepository.dataStoreFor(context).edit { it.clear() }
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun url(server: MockWebServer) = server.url("/").toString().removeSuffix("/")

    private fun configFor(server: MockWebServer, token: String = "test-token") = ServerConfiguration(
        serverUrl = url(server),
        bearerToken = token,
    )

    private fun tx(fingerprint: String = "f".repeat(64)) = NormalizedTransaction(
        id = "11111111-1111-1111-1111-111111111111",
        sourceEventId = "22222222-2222-2222-2222-222222222222",
        source = "notification",
        bank = "mellat",
        accountHint = "****1234",
        type = "expense",
        amountMinor = 500_000L,
        currency = "IRR",
        txAt = "2026-09-23T20:31:22+03:30",
        description = "Card purchase",
        rawTextRef = "22222222-2222-2222-2222-222222222222",
        fingerprint = fingerprint,
        parserName = "MellatParser",
        confidence = "HIGH",
    )

    @Test
    fun `crud keeps priority order`() = runTest {
        val tailscale = settings.addEndpoint("Tailscale", url(primary), "t1")
        val lan = settings.addEndpoint("Home LAN", url(secondary), "t2")

        assertEquals(listOf("Tailscale", "Home LAN"), settings.allEndpoints().map { it.name })

        settings.moveEndpoint(lan.id, up = true)
        assertEquals(listOf("Home LAN", "Tailscale"), settings.allEndpoints().map { it.name })

        settings.setEndpointEnabled(tailscale.id, false)
        assertEquals(listOf("Home LAN"), settings.currentEndpoints().map { it.name })

        settings.updateEndpoint(lan.id, "LAN", url(secondary), null, true)
        assertEquals("LAN", settings.allEndpoints().first { it.id == lan.id }.name)
        // Null token leaves the stored token untouched.
        assertEquals("t2", settings.allEndpoints().first { it.id == lan.id }.bearerToken)

        assertTrue(settings.deleteEndpoint(tailscale.id))
        assertEquals(listOf("LAN"), settings.allEndpoints().map { it.name })
    }

    @Test
    fun `reorder sets try order`() = runTest {
        val a = settings.addEndpoint("A", url(primary), "t")
        val b = settings.addEndpoint("B", url(secondary), "t")
        settings.reorderEndpoints(listOf(b.id, a.id))
        assertEquals(listOf("B", "A"), settings.allEndpoints().map { it.name })
        assertEquals(listOf(url(secondary), url(primary)), settings.currentConfigs().map { it.serverUrl })
    }

    @Test
    fun `legacy single url migrates to a Default endpoint`() = runTest {
        settings.setServerUrl(url(primary))
        settings.setBearerToken("legacy-token")

        val endpoints = settings.currentEndpoints()
        assertEquals(1, endpoints.size)
        assertEquals("Default", endpoints.single().name)
        assertEquals(url(primary), endpoints.single().url)
        assertEquals("legacy-token", endpoints.single().bearerToken)
    }

    @Test
    fun `ping uses first reachable endpoint`() = runTest {
        // First endpoint is dead (shut down), second is live.
        val dead = MockWebServer()
        val deadUrl = dead.url("/").toString().removeSuffix("/")
        dead.shutdown()
        val deadConfig = ServerConfiguration(serverUrl = deadUrl, bearerToken = "x")
        secondary.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"gomoneyReachable":true}"""))

        val selection = BridgeClient().pingFirstReachable(listOf(deadConfig, configFor(secondary)))

        assertEquals(1, selection.index)
        assertEquals(url(secondary), selection.config?.serverUrl)
        assertTrue(selection.result.ok)
    }

    @Test
    fun `ping skips blank urls and reports unreachable when all dead`() = runTest {
        val dead = MockWebServer()
        val deadUrl = dead.url("/").toString().removeSuffix("/")
        dead.shutdown()
        val selection = BridgeClient().pingFirstReachable(
            listOf(
                ServerConfiguration(serverUrl = "", bearerToken = "x"),
                ServerConfiguration(serverUrl = deadUrl, bearerToken = "x"),
            ),
        )
        assertEquals(null, selection.config)
        assertEquals(false, selection.result.ok)
    }

    @Test
    fun `send fails over from dead primary to live secondary`() = runTest {
        val dead = MockWebServer()
        val deadUrl = dead.url("/").toString().removeSuffix("/")
        dead.shutdown()
        secondary.enqueue(MockResponse().setResponseCode(201).setBody("""{"status":"created"}"""))

        val result = BridgeClient().sendWithFallback(
            listOf(
                ServerConfiguration(serverUrl = deadUrl, bearerToken = "x"),
                configFor(secondary),
            ),
            tx(),
        )

        assertEquals(BridgeClient.SendResult.Status.CREATED, result.status)
        assertEquals(1, secondary.requestCount)
    }

    @Test
    fun `send stops at validation error without trying next`() = runTest {
        primary.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"validation"}"""))
        secondary.enqueue(MockResponse().setResponseCode(201).setBody("""{"status":"created"}"""))

        val result = BridgeClient().sendWithFallback(
            listOf(configFor(primary), configFor(secondary)),
            tx(),
        )

        assertEquals(BridgeClient.SendResult.Status.VALIDATION_ERROR, result.status)
        assertEquals(0, secondary.requestCount)
    }

    @Test
    fun `fetchAccounts fails over to secondary`() = runTest {
        primary.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))
        secondary.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"accounts":[{"id":3,"label":"L","currency":"IRR","type":"expense","isDefault":true}]}""",
            ),
        )

        val result = BridgeClient().fetchAccountsWithFallback(listOf(configFor(primary), configFor(secondary)))

        assertTrue(result.ok)
        assertEquals(3, result.items.single().id)
    }

    @Test
    fun `drain with endpoint list fails over to secondary`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val delivery = DeliveryRepository(db.deliveryRecordDao())
            val dedup = DedupRepository(db.dedupCacheDao())
            db.rawEventDao().insert(
                RawEvent("e1", "notification", "pkg", null, null, "text", "2026-09-23T20:31:22+03:30", "2026-09-23T20:31:22+03:30"),
            )
            db.normalizedTransactionDao().insert(
                tx().copy(id = "e1", sourceEventId = "e1", rawTextRef = "e1"),
            )
            delivery.createQueued("e1", "e1")

            val dead = MockWebServer()
            val deadUrl = dead.url("/").toString().removeSuffix("/")
            dead.shutdown()
            secondary.enqueue(MockResponse().setResponseCode(201).setBody("""{"status":"created"}"""))

            SyncEngine.drain(
                db,
                listOf(
                    ServerConfiguration(serverUrl = deadUrl, bearerToken = "x"),
                    configFor(secondary),
                ),
                delivery,
                dedup,
            )

            assertEquals("sent", db.deliveryRecordDao().byId("e1")?.state)
        } finally {
            db.close()
        }
    }

    @Test
    fun `drain with empty endpoint list does nothing`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val delivery = DeliveryRepository(db.deliveryRecordDao())
            val dedup = DedupRepository(db.dedupCacheDao())
            db.rawEventDao().insert(
                RawEvent("e9", "notification", "pkg", null, null, "text", "2026-09-23T20:31:22+03:30", "2026-09-23T20:31:22+03:30"),
            )
            db.normalizedTransactionDao().insert(
                tx().copy(id = "e9", sourceEventId = "e9", rawTextRef = "e9"),
            )
            delivery.createQueued("e9", "e9")

            SyncEngine.drain(db, emptyList(), delivery, dedup)

            assertEquals("queued", db.deliveryRecordDao().byId("e9")?.state)
        } finally {
            db.close()
        }
    }
}
