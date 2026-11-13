package com.gomoney.capture.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.SettingsRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 009 FR-012–FR-014 (contracts/nightly-leftover.md):
 * One-time WorkManager worker scheduled for the next 21:00 device-local time.
 * Evaluates leftover work, stamps DataStore, posts a reminder if leftovers exist,
 * and re-enqueues itself for the following day's 21:00.
 */
class NightlyLeftoverWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val settings = SettingsRepository(applicationContext)

        val lastEvaluated = settings.getNightlyLastEvaluatedLocalDate()
        val lastCheckAt = settings.getNightlyLastCheckAt()
        val now = LocalDateTime.now()
        val decision = NightlyLeftover.evaluateGate(lastEvaluated, now)

        if (decision.shouldEvaluate && decision.targetDate != null) {
            val snapshot = NightlyLeftover.querySnapshot(db, lastCheckAt)
            val nowInstant = Instant.now().toString()
            val targetDateStr = decision.targetDate.toString()
            // Stamp DataStore even when leftover is false (spec & contract §2)
            settings.setNightlyEvaluation(nowInstant, targetDateStr)
            if (snapshot.hasLeftover) {
                NightlyLeftoverNotifier.notify(
                    applicationContext,
                    snapshot.unsyncedCount,
                    snapshot.missingNoteCount,
                )
            }
        }

        // Re-enqueue next 21:00
        scheduleNext(applicationContext)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "nightly_leftover_work"

        fun computeDelayToNext2100(
            now: LocalDateTime = LocalDateTime.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): Duration {
            val today2100 = now.toLocalDate().atTime(21, 0)
            val target = if (now.isBefore(today2100)) {
                today2100
            } else {
                today2100.plusDays(1)
            }
            val nowZoned = now.atZone(zone)
            val targetZoned = target.atZone(zone)
            val duration = Duration.between(nowZoned, targetZoned)
            return if (duration.isNegative || duration.isZero) Duration.ofMinutes(1) else duration
        }

        fun scheduleNext(context: Context) {
            val delay = computeDelayToNext2100()
            val request = OneTimeWorkRequestBuilder<NightlyLeftoverWorker>()
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
