package com.streamer.timetable.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Refreshes the offline timetable in the background.
 *
 * Note the failure handling: a transport error retries with backoff, but an
 * authentication failure does *not*. Active Directory counts failed logons, so a
 * worker that kept retrying a rejected password on a schedule could lock the
 * student's school account. A rejected password is a permanent failure until the
 * user supplies a new one.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = SyncRepository(applicationContext)
        if (!repository.hasCredentials()) return Result.success()

        return when (repository.sync()) {
            is SyncResult.Success -> Result.success()
            is SyncResult.Failed -> Result.retry()
            // Deliberately not Result.retry(): see the class comment.
            is SyncResult.AuthFailed -> Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "streamer_daily_sync"

        /**
         * Books the recurring sync at [intervalHours], or cancels it when that is 0.
         *
         * UPDATE rather than KEEP, so changing the interval in Options takes effect
         * immediately instead of being ignored in favour of the existing booking.
         */
        fun schedule(context: Context, intervalHours: Int) {
            if (intervalHours <= 0) {
                cancel(context)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // WorkManager will not run a periodic job more often than 15 minutes;
            // the choices offered are all hours, so this is only a floor guard.
            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                intervalHours.toLong().coerceAtLeast(1),
                TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
