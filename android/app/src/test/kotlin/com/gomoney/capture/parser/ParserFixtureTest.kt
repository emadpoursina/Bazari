package com.gomoney.capture.parser

import com.gomoney.capture.storage.RawEvent
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fixture-driven parser tests (T013, FR-029): iterates ALL fixture JSON files
 * under fixtures/<bank>/. Raw event in → expected NormalizedTransaction out
 * (or sanitized Failure). Verifies parser selection via ParserRegistry,
 * Persian-digit normalization via AmountNormalizer, and the parse-error
 * retention path (US6 scenario 2). Runs under Robolectric so org.json works
 * on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParserFixtureTest {

    private val registry = ParserRegistry()

    private fun fixtures(): List<Pair<String, JSONObject>> {
        val dir = File("src/test/resources/fixtures")
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            // User-source fixtures have their own contract (no ParserRegistry
            // selection) and are exercised by UserSourcePipelineTest.
            .filterNot { it.path.contains("usersource") }
            .map { it.path.removePrefix("src/test/resources/").removePrefix("fixtures/") to JSONObject(it.readText()) }
            .toList()
    }

    private fun rawEvent(fixture: JSONObject): RawEvent {
        val raw = fixture.getJSONObject("raw")
        return RawEvent(
            id = "fixture-event",
            source = raw.getString("source"),
            sourcePackage = raw.getString("sourcePackage"),
            bank = null,
            title = raw.optString("title", "").takeIf { it.isNotEmpty() && it != "null" },
            text = raw.getString("text"),
            postedAt = raw.getString("postedAt"),
            capturedAt = raw.getString("postedAt"),
        )
    }

    private fun checkFixture(fixturePath: String, fixture: JSONObject) {
        val event = rawEvent(fixture)
        val parser = registry.select(event)
        assertNotNull("registry must select a parser for $fixturePath", parser)

        val result = parser!!.parse(event)

        val expected = fixture.optJSONObject("expected")
        val expectedFailure = fixture.optJSONObject("expectedFailure")

        if (expected != null) {
            assertTrue(
                "expected Success for $fixturePath but got Failure: $result",
                result is ParseResult.Success,
            )
            val tx = (result as ParseResult.Success).transaction

            assertEquals(expected.getString("bank"), tx.bank)
            assertEquals(expected.getString("accountHint"), tx.accountHint)
            assertEquals(expected.getString("type"), tx.type)
            assertEquals(expected.getLong("amountMinor"), tx.amountMinor)
            assertEquals(expected.getString("currency"), tx.currency)
            assertEquals(parser.name, tx.parserName)

            val descriptionEquals = expected.optString("description_equals", "")
            if (descriptionEquals.isNotEmpty()) {
                assertEquals(
                    "description must equal the fixed safe constant for $fixturePath",
                    descriptionEquals,
                    tx.description,
                )
            }

            val descriptionContains = expected.optString("description_contains", "")
            if (descriptionContains.isNotEmpty()) {
                val needle = AmountNormalizer.normalizeDigits(descriptionContains)
                    .let { AmountNormalizer.normalizeDescription(AmountNormalizer.stripCurrencyWords(it)) }
                    .take(10)
                assertTrue(
                    "description '$tx.description' should contain '$descriptionContains'",
                    AmountNormalizer.normalizeDescription(tx.description).contains(needle),
                )
            }
        } else if (expectedFailure != null) {
            assertTrue(
                "expected Failure for $fixturePath but got Success: $result",
                result is ParseResult.Failure,
            )
            val failure = result as ParseResult.Failure
            val reasonContains = expectedFailure.optString("reason_contains", "")
            if (reasonContains.isNotEmpty()) {
                assertTrue(
                    "reason '${failure.reason}' should contain '$reasonContains'",
                    failure.reason.contains(reasonContains),
                )
            }
            // Sanitization rule (FR-023/028): failure reasons must never echo raw text.
            assertTrue(failure.reason.length < 200)
        } else {
            fail("fixture $fixturePath must define expected or expectedFailure")
        }
    }

    @Test
    fun `fixtures parse as expected`() {
        val all = fixtures()
        assertTrue("no fixtures found under fixtures/", all.isNotEmpty())
        for ((fixturePath, fixture) in all) {
            checkFixture(fixturePath, fixture)
        }
    }

    @Test
    fun `unmatched source yields retention not crash`() {
        // US6 scenario 2: an event from a package no parser claims is retained
        // (flagged parse_error by the pipeline) — the registry returns null
        // without throwing.
        for ((fixturePath, fixture) in fixtures()) {
            val event = rawEvent(fixture).copy(sourcePackage = "com.totally.unknown.app")
            val parser = registry.select(event)
            // No parser claims it — pipeline retains + flags; registry returns null.
            if (parser != null && parser.canParse(event)) {
                fail("parser ${parser.name} should not claim com.totally.unknown.app ($fixturePath)")
            }
        }
    }
}

/** Registry selection semantics (parser-interface.md §1). */
class ParserRegistrySelectionTest {

    @Test
    fun `first canParse wins in priority order`() {
        val registry = ParserRegistry()
        val mellat = RawEvent(
            id = "e1",
            source = "notification",
            sourcePackage = "ir.mellat.mellatab",
            bank = null,
            title = "t",
            text = "خريد ۵۰۰ ریال",
            postedAt = "2026-09-23T20:31:22+03:30",
            capturedAt = "2026-09-23T20:31:22+03:30",
        )
        val selected = registry.select(mellat)
        assertEquals("MellatParser", selected?.name)

        val blu = RawEvent(
            id = "e-blu",
            source = "notification",
            sourcePackage = "com.samanpr.blu",
            bank = null,
            title = "بلو",
            text = "برداشت وجه\nمبلغ ۱۰٬۰۰۰٬۰۰۰ ریال\nموجودی: ۱۰٬۸۶۲٬۲۵۲ ریال",
            postedAt = "2026-09-24T20:31:22+03:30",
            capturedAt = "2026-09-24T20:31:22+03:30",
        )
        assertEquals("BlueParser", registry.select(blu)?.name)

        val order = registry.parsers.map { it.name }
        assertEquals(
            listOf("MellatParser", "MelliParser", "SamanParser", "BlueParser", "SampleBankParser", "GenericParser"),
            order,
        )
    }

    @Test
    fun `parsers are pure - same input same output`() {
        val parser = MellatParser()
        val event = RawEvent(
            id = "e2",
            source = "notification",
            sourcePackage = "ir.mellat.mellatab",
            bank = null,
            title = "t",
            text = "خريد شماره کارت 6104.****1234 به مبلغ ۵۰۰٬۰۰۰ ریال",
            postedAt = "2026-09-23T20:31:22+03:30",
            capturedAt = "2026-09-23T20:31:22+03:30",
        )
        val r1 = parser.parse(event)
        val r2 = parser.parse(event)
        assertEquals(r1, r2)
    }
}
