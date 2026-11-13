package com.gomoney.capture.sync

import com.gomoney.capture.storage.AppDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * 009 FR-012–FR-016 (data-model §3b, contracts/nightly-leftover.md):
 * Pure evaluation logic and database snapshot for nightly leftover reminders.
 */
object NightlyLeftover {

    data class LeftoverCandidate(
        val id: String,
        val userMemo: String?,
        val memoSyncState: String?,
        val assignmentSyncState: String?,
        val deliveryState: String?,
        val capturedAt: String,
    )

    data class LeftoverSnapshot(
        val unsyncedIds: Set<String>,
        val missingNoteIds: Set<String>,
    ) {
        val unsyncedCount: Int get() = unsyncedIds.size
        val missingNoteCount: Int get() = missingNoteIds.size
        val allLeftoverIds: Set<String> get() = unsyncedIds + missingNoteIds
        val hasLeftover: Boolean get() = unsyncedCount > 0 || missingNoteCount > 0
    }

    data class GateDecision(
        val shouldEvaluate: Boolean,
        val targetDate: LocalDate?,
    )

    sealed interface LeftoverTapDestination {
        data class Detail(val txId: String) : LeftoverTapDestination
        object EventsList : LeftoverTapDestination
    }

    /**
     * FR-013 / research R5: computes the leftover snapshot from candidate transactions.
     * - unsynced: distinct tx where delivery not 'sent' OR memo pending OR assignment pending.
     * - missing note: distinct tx with blank memo AND capturedAt >= lastCheckAt (or all if lastCheckAt is null).
     */
    fun computeLeftoverSnapshot(
        candidates: List<LeftoverCandidate>,
        lastCheckAt: String?,
    ): LeftoverSnapshot {
        val unsynced = mutableSetOf<String>()
        val missingNotes = mutableSetOf<String>()

        for (candidate in candidates) {
            val isUnsynced = candidate.deliveryState != "sent" ||
                candidate.memoSyncState == "pending" ||
                candidate.assignmentSyncState == "pending"
            if (isUnsynced) {
                unsynced.add(candidate.id)
            }

            val isBlankMemo = candidate.userMemo.isNullOrBlank()
            if (isBlankMemo) {
                val inWindow = if (lastCheckAt == null) {
                    true
                } else {
                    isCapturedAtOnOrAfter(candidate.capturedAt, lastCheckAt)
                }
                if (inWindow) {
                    missingNotes.add(candidate.id)
                }
            }
        }

        return LeftoverSnapshot(
            unsyncedIds = unsynced,
            missingNoteIds = missingNotes,
        )
    }

    /**
     * FR-012 / research R4: evaluates whether the nightly check should run for the current date/time.
     * Runs at 21:00 device-local, at most once per local calendar date.
     * Stamped date prevents re-evaluating the same date.
     * Missed dates run with targetDate = yesterday.
     */
    fun evaluateGate(
        lastEvaluatedLocalDate: String?,
        now: LocalDateTime,
        cutoffTime: LocalTime = LocalTime.of(21, 0),
    ): GateDecision {
        val today = now.toLocalDate()
        val nowTime = now.toLocalTime()
        val lastDate = lastEvaluatedLocalDate?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }

        if (lastDate == null) {
            return if (!nowTime.isBefore(cutoffTime)) {
                GateDecision(shouldEvaluate = true, targetDate = today)
            } else {
                GateDecision(shouldEvaluate = false, targetDate = null)
            }
        }

        val targetDate = if (!nowTime.isBefore(cutoffTime)) today else today.minusDays(1)
        return if (!lastDate.isBefore(targetDate)) {
            // lastDate >= targetDate -> skip!
            GateDecision(shouldEvaluate = false, targetDate = targetDate)
        } else {
            GateDecision(shouldEvaluate = true, targetDate = targetDate)
        }
    }

    /**
     * FR-015: Reminder text including both counts; zero on one side is shown when the other is non-zero.
     */
    fun formatReminderText(unsyncedCount: Int, missingNoteCount: Int): String {
        return "$unsyncedCount unsynced, $missingNoteCount missing a note."
    }

    /**
     * FR-016: Tap destination from re-queried leftover ID union.
     * 1 leftover -> Detail; 0 or 2+ -> EventsList.
     */
    fun resolveLeftoverTap(leftoverIds: Set<String>): LeftoverTapDestination {
        return if (leftoverIds.size == 1) {
            LeftoverTapDestination.Detail(leftoverIds.first())
        } else {
            LeftoverTapDestination.EventsList
        }
    }

    /**
     * Re-queries candidate transactions from Room and computes the current snapshot.
     */
    suspend fun querySnapshot(db: AppDatabase, lastCheckAt: String?): LeftoverSnapshot {
        val rows = db.normalizedTransactionDao().leftoverCandidates()
        val candidates = rows.map {
            LeftoverCandidate(
                id = it.id,
                userMemo = it.userMemo,
                memoSyncState = it.memoSyncState,
                assignmentSyncState = it.assignmentSyncState,
                deliveryState = it.deliveryState,
                capturedAt = it.capturedAt,
            )
        }
        return computeLeftoverSnapshot(candidates, lastCheckAt)
    }

    fun isCapturedAtOnOrAfter(capturedAtStr: String, sinceInstantStr: String): Boolean {
        return runCatching {
            val capturedInstant = parseToInstant(capturedAtStr)
            val sinceInstant = parseToInstant(sinceInstantStr)
            !capturedInstant.isBefore(sinceInstant)
        }.getOrElse {
            capturedAtStr >= sinceInstantStr
        }
    }

    private fun parseToInstant(isoString: String): Instant {
        return try {
            Instant.parse(isoString)
        } catch (_: DateTimeParseException) {
            OffsetDateTime.parse(isoString).toInstant()
        }
    }
}
