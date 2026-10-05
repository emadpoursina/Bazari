package com.gomoney.capture.capture

import com.gomoney.capture.storage.NotificationCaptureRecord
import com.gomoney.capture.storage.NotificationCaptureRecordDao
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Result of asking the identity guard whether a notification may be
 * processed (006 contracts/notification-identity.md §2).
 */
enum class ClaimOutcome {
    /** No row → claim granted (insert `pending`), or a stale `pending` row taken over. */
    GRANTED,

    /** Row is `recorded`: the notification already produced its one event. */
    ALREADY_RECORDED,

    /** Row is `pending` younger than 60 s: another attempt owns the key. */
    IN_PROGRESS,
}

/**
 * Per-notification identity guard (006 FR-003, research R2): at most one
 * event ever per `StatusBarNotification.key`, shared by the real-time
 * `onNotificationPosted` path and the manual scan loop.
 *
 * Protocol:
 *  ```
 *  claim(key)   no row → granted; `recorded` → ALREADY_RECORDED;
 *               `pending` < 60 s → IN_PROGRESS (race guard);
 *               `pending` ≥ 60 s → take over → granted (crash recovery)
 *  commit(key, eventId)  terminal: state = `recorded`
 *  release(key)          delete the row — nothing persisted, re-examinable
 *  ```
 *
 * The guard never decides *whether* a notification qualifies — the
 * `CapturePipeline` stays the only admission gate (contract rule 3).
 */
class NotificationIdentityRegistry(
    private val dao: NotificationCaptureRecordDao,
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
    private val staleAfterSeconds: Long = STALE_AFTER_SECONDS,
) {

    /** Attempt to own [key] for processing (claim / stale take-over). */
    suspend fun claim(key: String): ClaimOutcome {
        val now = clock()
        repeat(RACE_RETRIES) {
            val row = dao.byKey(key)
            if (row == null) {
                val inserted = dao.insertIfAbsent(
                    NotificationCaptureRecord(
                        notificationKey = key,
                        eventId = null,
                        state = NotificationCaptureRecord.STATE_PENDING,
                        updatedAt = now.toString(),
                    ),
                )
                if (inserted != -1L) return ClaimOutcome.GRANTED
                return@repeat // lost a race — re-read and classify
            }
            when (row.state) {
                NotificationCaptureRecord.STATE_RECORDED -> return ClaimOutcome.ALREADY_RECORDED
                NotificationCaptureRecord.STATE_PENDING -> {
                    if (ageSeconds(row, now) >= staleAfterSeconds) {
                        // Crash/interruption recovery: refresh the claim, but
                        // only while it is still `pending` (never reopen a
                        // committed row).
                        if (dao.takeOverPending(key, now.toString()) == 1) {
                            return ClaimOutcome.GRANTED
                        }
                        return@repeat // recorded or deleted meanwhile — re-read
                    }
                    return ClaimOutcome.IN_PROGRESS
                }
                else -> return ClaimOutcome.IN_PROGRESS // unreachable: state is validated
            }
        }
        return ClaimOutcome.IN_PROGRESS
    }

    /**
     * Terminal: the pipeline persisted something for [key] (event [eventId]).
     * The notification may never produce another event.
     */
    suspend fun commit(key: String, eventId: String) {
        dao.commit(key, eventId, clock().toString())
    }

    /** Nothing persisted — delete the claim so future scans/posts re-examine it. */
    suspend fun release(key: String) {
        dao.release(key)
    }

    /** True when the key already produced its one event (skip, count as skipped). */
    suspend fun isRecorded(key: String): Boolean =
        dao.byKey(key)?.state == NotificationCaptureRecord.STATE_RECORDED

    private fun ageSeconds(row: NotificationCaptureRecord, now: OffsetDateTime): Long =
        runCatching { Duration.between(OffsetDateTime.parse(row.updatedAt), now).seconds }
            // An unparseable timestamp must not swallow the key forever:
            // treat it as stale and take the claim over.
            .getOrDefault(Long.MAX_VALUE)

    companion object {
        /** Stale-`pending` take-over window (006 research R2, contract §2). */
        const val STALE_AFTER_SECONDS = 60L

        /** Bounded re-read attempts when a concurrent writer wins a race. */
        private const val RACE_RETRIES = 3
    }
}
