package com.streamer.timetable.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

/**
 * The widget's refresh button.
 *
 * Deliberately does almost nothing itself: it marks a sync as started, redraws so the
 * press registers, and hands the actual work to [WidgetSyncWorker]. Running the sync
 * here instead meant it happened inside a broadcast, where the process can be killed
 * after about ten seconds -- long before eleven NTLM requests finish.
 */
class RefreshAction : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val appContext = context.applicationContext

        // A press while one is genuinely in flight is ignored: NTLM binds its
        // handshake to a connection, so overlapping syncs would fight over it. The
        // claim ages out, so a killed sync cannot disable the button permanently.
        if (WidgetSync.isRunning(appContext)) return

        WidgetSync.begin(appContext)
        WidgetUpdater.refresh(appContext)
        WidgetSyncWorker.enqueue(appContext)
    }
}
