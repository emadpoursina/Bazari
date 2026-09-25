package com.gomoney.capture.sync

import com.gomoney.capture.model.ErrorCategory
import com.gomoney.capture.storage.DeliveryRecord
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Sticky outage reason derived from durable Room rows (FR-020/023): the
 * dashboard banner never forgets why the queue halted, even across restarts.
 * Pure logic — no Android dependencies — so it is unit-testable.
 */
object SyncBanner {

    enum class Kind { ALL_CLEAR, NEEDS_ATTENTION, OFFLINE_RETRYABLE, WAITING }

    data class Banner(
        val kind: Kind,
        val pendingTotal: Int = 0,
        val needsAttentionCount: Int = 0,
        val lastCategory: String? = null,
        val lastDetail: String? = null,
        val lastAttemptAt: String? = null,
    )

    fun forPending(pending: List<DeliveryRecord>): Banner {
        if (pending.isEmpty()) return Banner(Kind.ALL_CLEAR)
        val needsAttention = pending.count { isNeedsAttention(it) }
        if (needsAttention > 0) {
            val latest = latestAttempt(pending.filter { isNeedsAttention(it) })
            return Banner(
                kind = Kind.NEEDS_ATTENTION,
                pendingTotal = pending.size,
                needsAttentionCount = needsAttention,
                lastCategory = latest?.errorCategory,
                lastDetail = latest?.errorDetail,
                lastAttemptAt = latest?.lastAttemptAt,
            )
        }
        val withFailure = pending.filter { it.errorCategory != null }
        if (withFailure.isNotEmpty()) {
            val latest = latestAttempt(withFailure)
            return Banner(
                kind = Kind.OFFLINE_RETRYABLE,
                pendingTotal = pending.size,
                lastCategory = latest?.errorCategory,
                lastDetail = latest?.errorDetail,
                lastAttemptAt = latest?.lastAttemptAt,
            )
        }
        return Banner(kind = Kind.WAITING, pendingTotal = pending.size)
    }

    fun isNeedsAttention(record: DeliveryRecord): Boolean {
        val category = record.errorCategory ?: return false
        if (category == ErrorCategory.VALIDATION_ERROR.name.lowercase()) return true
        if (category == ErrorCategory.PARSE_ERROR.name.lowercase()) return true
        if (category == ErrorCategory.CAPTURE_ERROR.name.lowercase()) return true
        if (category == ErrorCategory.SERVER_ERROR.name.lowercase() &&
            (record.errorDetail ?: "").contains("unauthorized", ignoreCase = true)
        ) {
            return true
        }
        // Retained parse-error rows (state=parsed, no normalized tx).
        if (record.state == "parsed") return true
        return false
    }

    fun message(banner: Banner): String = when (banner.kind) {
        Kind.ALL_CLEAR -> "All caught up — nothing waiting to send."
        Kind.NEEDS_ATTENTION -> buildString {
            append("${banner.pendingTotal} waiting — ${banner.needsAttentionCount} need attention")
            banner.lastCategory?.let { append(" ($it") }
            banner.lastDetail?.let { append(": $it") }
            if (banner.lastCategory != null) append(")")
            append(". Auto-retry won't help — fix mapping/token, then Retry.")
        }
        Kind.OFFLINE_RETRYABLE -> buildString {
            append("${banner.pendingTotal} waiting — bridge unreachable")
            banner.lastCategory?.let { append(" ($it)") }
            banner.lastAttemptAt?.let { append(" since ${shortTime(it)}") }
            append(". Will auto-send when you're home.")
        }
        Kind.WAITING -> "${banner.pendingTotal} waiting to send — will auto-send."
    }

    private fun latestAttempt(rows: List<DeliveryRecord>): DeliveryRecord? =
        rows.maxByOrNull { it.lastAttemptAt.orEmpty() }

    private fun shortTime(iso: String): String = runCatching {
        OffsetDateTime.parse(iso).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrDefault(iso)
}
