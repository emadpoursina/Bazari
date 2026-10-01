package com.gomoney.capture.storage

import androidx.room.ColumnInfo
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
    val amountMinor: Long, // canonical integer minor units (whole IRR when currency == IRR)
    val currency: String, // bound server account's currency; "" = unknown (held, 005)
    val txAt: String, // ISO-8601 with offset
    val description: String, // normalized (whitespace-collapsed), ≤200 chars
    val rawTextRef: String, // reference to RawEvent.id, not a copy
    val fingerprint: String, // 64 hex (§Fingerprint)
    val parserName: String,
    val confidence: String, // HIGH | MEDIUM | LOW
    val userMemo: String? = null,
    @ColumnInfo(defaultValue = "'synced'")
    val memoSyncState: String = "synced", // pending | synced
    val gomoneyTxnId: String? = null,
    // --- notification-engine additions (data-model.md §7) ---
    val sourceId: String? = null, // originating user TransactionSource.id
    val accountId: Int? = null, // bound source account recorded against
    val destinationAccountId: Int? = null, // user-selected destination account
    val categoryId: Int? = null, // user-selected category
    @ColumnInfo(defaultValue = "'synced'")
    val assignmentSyncState: String = "synced", // pending | synced
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

/**
 * A user-defined capture source (data-model.md §1, FR-001/002/003/005/006).
 * The unique `(identifier, channel)` index is the race backstop for the
 * duplicate-source rule (FR-005, SC-009).
 */
@Entity(
    tableName = "transaction_sources",
    indices = [
        Index(value = ["identifier", "channel"], unique = true),
        Index("enabled"),
    ],
)
data class TransactionSource(
    @PrimaryKey val id: String,
    val name: String,
    val identifier: String, // package id (notification) or SMS sender id
    val channel: String, // Channel wire value: notification | sms
    val enabled: Boolean,
    val template: String, // exactly one {direction} and one {amount}
    val incomeKeywords: String, // newline/comma-separated
    val expenseKeywords: String,
    val boundAccountId: Int? = null,
    val boundAccountLabel: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

/** Normalize the stored comma/newline-separated keyword list into words. */
fun String.keywordList(): List<String> =
    split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

/**
 * A message from a defined source that could not be parsed (data-model.md §3,
 * FR-010/028). Keyed by the local raw event so no second copy of the text is
 * made; `sourceId` is nulled (not cascaded) when the source is removed.
 */
@Entity(
    tableName = "parse_errors",
    foreignKeys = [
        ForeignKey(
            entity = RawEvent::class,
            parentColumns = ["id"],
            childColumns = ["sourceEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceEventId"), Index("occurredAt")],
)
data class ParseErrorMessage(
    @PrimaryKey val id: String,
    val sourceId: String?, // null when the source was removed
    val sourceEventId: String, // RawEvent.id (local display source)
    val failureReason: String, // sanitized only — never raw text
    val occurredAt: String, // ISO-8601
)

/** Server account offered for selection (data-model.md §5). */
@Entity(tableName = "server_accounts")
data class ServerAccount(
    @PrimaryKey val id: Int,
    val label: String,
    val currency: String,
    val type: String,
    val isDefault: Boolean,
    val refreshedAt: String,
)

/** Server category offered for selection (data-model.md §6). */
@Entity(tableName = "server_categories")
data class ServerCategory(
    @PrimaryKey val id: Int,
    val label: String,
    val refreshedAt: String,
)
