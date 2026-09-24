package com.gomoney.capture.logging

import java.util.UUID

/**
 * Log/UI redaction (T047, FR-028, SC-007):
 *  - production logs NEVER contain raw message text or amounts;
 *  - debug mode OFF → raw text hidden in all UI paths.
 */
object Redactor {

    private val uuidRegex = Regex("""[0-9a-fA-F-]{36}""")

    /** Sanitize a parser reason / error detail for storage and display. */
    fun safeDetail(reason: String, maxLength: Int = 120): String {
        val collapsed = reason.trim().replace(Regex("\\s+"), " ")
        val stripped = collapsed
            .replace(Regex("""\d{4,}"""), "####") // long digit runs (cards/accounts)
            .replace(Regex("""[۰-۹٠-٩]{4,}"""), "####")
            .replace(uuidRegex, "<uuid>")
        return stripped.take(maxLength)
    }

    /**
     * Redact a raw message: only shape metadata survives (length, source
     * markers) — the text itself never appears in production logs.
     */
    fun redactRawText(rawText: String): String = "<redacted:${rawText.length} chars>"

    /**
     * Amounts never reach logs in production: useful for work logs that
     * describe queue items.
     */
    fun redactAmount(amountMinor: Long): String = "<amount>"

    /**
     * Debug-gated view: only call this when `debugModeEnabled` is true
     * (T046). Returns the raw text untouched for the debug detail screen.
     */
    fun debugOnlyRawText(rawText: String, debugModeEnabled: Boolean): String? =
        if (debugModeEnabled) rawText else null

    fun newIdempotencyToken(): String = UUID.randomUUID().toString()
}
