package com.gomoney.capture.sync

import com.gomoney.capture.model.ErrorCategory
import com.gomoney.capture.storage.NormalizedTransaction
import com.gomoney.capture.storage.ServerConfiguration
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** BridgeClient response mapping tests (T024, FR-014) with MockWebServer. */
class BridgeClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BridgeClient
    private lateinit var config: ServerConfiguration

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        config = ServerConfiguration(
            serverUrl = server.url("/").toString().removeSuffix("/"),
            bearerToken = "test-token",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

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

    /** 201 created → SENT. */
    @Test
    fun `201 maps to created`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"status":"created","gomoneyTxnId":123}"""))

        val result = BridgeClient().send(config, tx())

        assertEquals(BridgeClient.SendResult.Status.CREATED, result.status)
        assertEquals("Bearer test-token", server.takeRequest().getHeader("Authorization"))
    }

    /** 200 duplicate → SENT with errorCategory=duplicate. */
    @Test
    fun `200 duplicate maps to duplicate terminal`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"duplicate","gomoneyTxnId":5}"""))

        val result = BridgeClient().send(config, tx())

        assertEquals(BridgeClient.SendResult.Status.DUPLICATE, result.status)
        assertEquals(ErrorCategory.DUPLICATE, result.errorCategory)
    }

    /** 400 validation → validation_error, NOT retried automatically. */
    @Test
    fun `400 maps to validation_error not retryable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"validation","details":["amount must be > 0"]}"""))

        val result = BridgeClient().send(config, tx())

        assertEquals(BridgeClient.SendResult.Status.VALIDATION_ERROR, result.status)
        assertEquals(ErrorCategory.VALIDATION_ERROR, result.errorCategory)
        assertTrue(!result.retryable)
    }

    /** 401 unauthorized → server_error surfaced in UI. */
    @Test
    fun `401 maps to server_error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))

        val result = BridgeClient().send(config, tx())

        assertEquals(BridgeClient.SendResult.Status.SERVER_ERROR, result.status)
        assertEquals(ErrorCategory.SERVER_ERROR, result.errorCategory)
    }

    /** 502 gomoney_unreachable → network_error, retry with backoff. */
    @Test
    fun `502 maps to network_error retryable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":"gomoney_unreachable","details":"x"}"""))

        val result = BridgeClient().send(config, tx())

        assertEquals(BridgeClient.SendResult.Status.NETWORK_ERROR, result.status)
        assertEquals(ErrorCategory.NETWORK_ERROR, result.errorCategory)
        assertTrue(result.retryable)
    }

    /** 500 gomoney_error → server_error, retry allowed. */
    @Test
    fun `500 maps to server_error retryable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"gomoney_error","details":"x"}"""))

        val result = BridgeClient().send(config, tx())

        assertEquals(BridgeClient.SendResult.Status.SERVER_ERROR, result.status)
        assertTrue(result.retryable)
    }

    /** Ping endpoint happy path + Go Money unreachable. */
    @Test
    fun `ping reports gomoney reachability`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"gomoneyReachable":true,"serverTime":"x"}"""))
        assertEquals(true, BridgeClient().ping(config).gomoneyReachable)

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":false,"gomoneyReachable":false,"serverTime":"x"}"""))
        assertEquals(false, BridgeClient().ping(config).gomoneyReachable)

        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        assertEquals(true, BridgeClient().ping(config).unauthorized)
    }

    /** Payload never contains raw text (rawTextRef omitted — FR-028). */
    @Test
    fun `request body has no rawText field`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"status":"created"}"""))
        BridgeClient().send(config, tx())

        val body = server.takeRequest().body.readUtf8()
        assertTrue(!body.contains("rawText"))
        assertTrue(body.contains("fingerprint"))
    }
}
