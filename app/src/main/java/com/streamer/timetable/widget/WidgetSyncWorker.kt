package com.streamer.timetable.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.streamer.timetable.data.SCHOOL_ZONE
import com.streamer.timetable.sync.SyncRepository
import com.streamer.timetable.sync.SyncResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Runs the sync a widget refresh asked for.
 *
 * The work does not belong in the click callback itself: that callback is a broadcast,
 * and a broadcast receiver gets roughly ten seconds before Android may kill its
 * process. A sync is eleven sequential requests over NTLM and regularly exceeds that,
 * so it was being cut short partway through.
 *
 * WorkManager is not bound by that window, and it survives the process being killed.
 */
class WidgetSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            val prefs = WidgetSync.prefs(applicationContext)
            val today = prefs.getString("debug_date_override", null)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: LocalDate.now(SCHOOL_ZONE)

            // The outcome is kept, not discarded: "offline" and "never ran" look
            // the same on a home screen otherwise.
            val outcome = when (val result = SyncRepository(applicationContext).sync(today)) {
                is SyncResult.Success ->
                    if (result.eventCount == 0) "no events" else "updated"

                is SyncResult.AuthFailed -> "sign in needed"
                is SyncResult.Failed -> "offline"
            }
            WidgetSync.recordResult(applicationContext, outcome)
        } finally {
            // NonCancellable, because the redraw is a suspend call and REPLACE
            // cancels a running worker on the next press. In a cancelled coroutine
            // every suspension point throws at once, so the flag was cleared but the
            // widget was never told -- leaving "syncing" on screen indefinitely.
            withContext(NonCancellable) {
                WidgetSync.end(applicationContext)
                WidgetUpdater.refresh(applicationContext)
            }
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "widget_sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetSyncWorker>()
                // Expedited so a deliberate button press is not queued behind the
                // system's idea of a good time to run background work.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                // REPLACE, not KEEP: with KEEP a press was silently dropped whenever
                // an earlier request was still unfinished -- including one deferred
                // because the expedited quota had run out. A deliberate press should
                // always supersede whatever is queued.
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
