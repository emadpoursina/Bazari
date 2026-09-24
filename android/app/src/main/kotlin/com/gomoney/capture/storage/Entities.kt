package com.gomoney.capture.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gomoney.capture.model.EventSource
import com.gomoney.capture.model.TxType

/**
 * The immutable, unmodified captured event (data-model.md §1).
 * Written before any processing (FR-002); never editable after write.
 */
@Entity(tableName = "raw_events")
data class RawEvent(
    @PrimaryKey val id: String,
    val source: String, // EventSource wire value: notification | sms
    val sourcePackage: String,
    val bank: String?,
    val title: String?,
    val text: String, // raw message text — never logged, never leaves the phone
    val postedAt: String, // ISO-8601 with offset
    val capturedAt: String, // ISO-8601 with offset
)

/** Helpers mirroring data-model.md validation rules for entity fields. */
fun RawEvent.eventSource(): EventSource =
    EventSource.wire(source) ?: EventSource.NOTIFICATION

/**
 * Provider-independent parse output (data-model.md §2). One row per parsed
 * RawEvent; `fingerprint` is globally unique.
 */
@Entity(
    tableName = "normalized_transactions",
    foreignKeys = [
        ForeignKey(
            entity = RawEvent::class,
            parentColumns = ["id"],
            childColumns = ["sourceEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sourceEventId"),
        Index(value = ["fingerprint"], unique = true),
    ],
)
data class NormalizedTransaction(
    @PrimaryKey val id: String,
    val sourceEventId: String,
    val source: String,
    val bank: String, // lowercase slug, e.g. "mellat"
    val accountHint: String, // masked, e.g. "****1234"
    val type: String, // TxType wire value: expense | income
    val amountMinor: Long, // canonical integer minor units (whole IRR)
    val currency: String, // MVP: always IRR
    val txAt: String, // ISO-8601 with offset
    val description: String, // normalized (whitespace-collapsed), ≤200 chars
    val rawTextRef: String, // reference to RawEvent.id, not a copy
    val fingerprint: String, // 64 hex (§Fingerprint)
    val parserName: String,
    val confidence: String, // HIGH | MEDIUM | LOW
)

/** TxType accessor outside Room's field processing. */
fun NormalizedTransaction.txType(): TxType =
    TxType.wire(type) ?: TxType.EXPENSE

/**
 * Outbox lifecycle record, 1:1 with NormalizedTransaction (data-model.md §3).
 * A parse failure also creates a row (keyed by the raw event id) so the
 * failure is visible in the UI instead of being silently dropped (US6).
 */
@Entity(
    tableName = "delivery_records",
    foreignKeys = [
        ForeignKey(
            entity = RawEvent::class,
            parentColumns = ["id"],
            childColumns = ["sourceEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("state"), Index("sourceEventId")],
)
data class DeliveryRecord(
    @PrimaryKey val id: String, // = NormalizedTransaction.id, or raw event id for parse errors
    val state: String, // DeliveryState wire value
    val attempts: Int,
    val lastAttemptAt: String?,
    val nextRetryAt: String?,
    val errorCategory: String?, // ErrorCategory wire value, null while healthy
    val errorDetail: String?, // sanitized only — no raw message text
    val sourceEventId: String,
)

/**
 * Local short-circuit so the phone doesn't re-send known duplicates after
 * restarts (data-model.md §4).
 */
@Entity(tableName = "dedup_cache")
data class DedupCache(
    @PrimaryKey val fingerprint: String,
    val resolvedAt: String,
    val outcome: String, // sent | duplicate
)
