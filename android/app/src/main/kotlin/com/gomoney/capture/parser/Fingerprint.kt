package com.gomoney.capture.parser

import com.gomoney.capture.model.TxType
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Deterministic fingerprint + bucket-key computation (FR-009, clarified Q3=B).
 *
 * fingerprint = sha256(bank|accountHint|type|amountMinor|round(ts)|normalizedDescription)
 * bucketKey   = bank|accountHint|type|amountMinor|floor(unix(ts)/120)
 *
 * round(ts) is minute precision UTC — identical sources match exactly;
 * notification+SMS pairs ≤2 min apart fall back to the bucket-window scan on
 * the bridge (which scans ADJACENT buckets and verifies |Δt| ≤ 120 s, so
 * bucket boundaries never hide a match).
 */
object Fingerprint {

    fun fingerprintInput(
        bank: String,
        accountHint: String,
        type: TxType,
        amountMinor: Long,
        txAt: String,
        description: String,
    ): String {
        val roundedMinute = roundMinute(txAt)
        val normalizedDescription = AmountNormalizer.normalizeDescription(description)
        return "$bank|$accountHint|${type.name.lowercase()}|$amountMinor|$roundedMinute|$normalizedDescription"
    }

    fun compute(
        bank: String,
        accountHint: String,
        type: TxType,
        amountMinor: Long,
        txAt: String,
        description: String,
    ): String = sha256Hex(fingerprintInput(bank, accountHint, type, amountMinor, txAt, description))

    /** Epoch-minute rounding (ISO-8601 UTC minute precision). */
    fun roundMinute(txAt: String): Long {
        val epochSeconds = toEpochSecond(txAt)
        return epochSeconds / 60
    }

    /** 2-minute epoch bucket: floor(unix(ts)/120). */
    fun bucketKey(
        bank: String,
        accountHint: String,
        type: TxType,
        amountMinor: Long,
        txAt: String,
    ): String {
        val bucket = toEpochSecond(txAt) / 120
        return "$bank|$accountHint|${type.name.lowercase()}|$amountMinor|$bucket"
    }

    fun toEpochSecond(txAt: String): Long {
        val parsed = runCatching { OffsetDateTime.parse(txAt) }
            .getOrElse { Instant.parse(txAt) }
        return parsed.toInstant().atOffset(ZoneOffset.UTC).toEpochSecond()
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
