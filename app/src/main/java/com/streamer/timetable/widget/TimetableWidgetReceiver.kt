package com.streamer.timetable.widget

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import com.streamer.timetable.data.SCHOOL_ZONE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

class TimetableWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TimetableWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdater.scheduleMidnightRefresh(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdater.cancelMidnightRefresh(context)
    }
}

/**
 * Rolls the widget over to the new day.
 *
 * A widget showing "today" has no idea the date changed. Left alone it keeps showing
 * yesterday's lessons all through the next morning -- precisely when it would be
 * glanced at before school. `updatePeriodMillis` is not a fix: its floor is 30
 * minutes and it is suspended under Doze, so it can miss midnight entirely.
 *
 * An exact alarm at 00:00 that re-books itself is the reliable way, and it is cheap:
 * one wakeup per day.
 */
class WidgetMidnightReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                TimetableWidget().updateAll(appContext)
            } finally {
                // Re-book before finishing, or the rollover happens exactly once.
                WidgetUpdater.scheduleMidnightRefresh(appContext)
                pending.finish()
            }
        }
    }
}

object WidgetUpdater {

    private const val REQUEST_CODE = 880011
    private const val ROLLOFF_REQUEST_CODE = 880012
    private const val RECHECK_REQUEST_CODE = 880013

    /**
     * Pushes fresh content to every placed widget, reporting what happened.
     *
     * Two paths, because relying on `updateAll` alone left the widget frozen: it
     * resolves glance ids itself and quietly does nothing if that lookup comes back
     * empty, and the previous version wrapped it in `runCatching` so any failure was
     * invisible. The broadcast is the system's own update path -- it makes the
     * launcher call `onUpdate`, which re-runs `provideGlance` from scratch.
     *
     * Returns a short description for the debug panel, since a widget that will not
     * refresh gives no other clue about which half is at fault.
     */
    suspend fun refresh(context: Context): String {
        val appContext = context.applicationContext

        val ids = AppWidgetManager.getInstance(appContext)
            .getAppWidgetIds(ComponentName(appContext, TimetableWidgetReceiver::class.java))

        if (ids.isEmpty()) return "no widgets placed"

        val direct = runCatching { TimetableWidget().updateAll(appContext) }

        // Belt and braces: even when updateAll succeeds, this guarantees a rebuild.
        appContext.sendBroadcast(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                component = ComponentName(appContext, TimetableWidgetReceiver::class.java)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
        )

        return direct.fold(
            onSuccess = { "refreshed ${ids.size} widget(s)" },
            onFailure = { "broadcast only: ${it::class.simpleName}" },
        )
    }

    fun scheduleMidnightRefresh(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return

        // A minute past, not exactly midnight: it avoids a race with the system's own
        // date change and costs nothing in accuracy.
        val nextMidnight = LocalDate.now(SCHOOL_ZONE)
            .plusDays(1)
            .atTime(LocalTime.of(0, 1))
            .atZone(SCHOOL_ZONE)
            .toInstant()
            .toEpochMilli()

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, WidgetMidnightReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            manager.canScheduleExactAlarms()

        if (canBeExact) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC, nextMidnight, pendingIntent)
        } else {
            // Inexact still rolls the day over, just not on the stroke of midnight.
            manager.setAndAllowWhileIdle(AlarmManager.RTC, nextMidnight, pendingIntent)
        }
    }

    /**
     * Refreshes once at [atMillis], when the lesson on top is due to end.
     *
     * Without this the widget only changes on a sync or at midnight, so a finished
     * lesson stays on the home screen for hours. Booked per render, and only for the
     * next end time, so it is one alarm at a time rather than one per lesson.
     */
    fun scheduleRefreshAt(context: Context, atMillis: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ROLLOFF_REQUEST_CODE,
            Intent(context, WidgetMidnightReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Inexact is fine here: a lesson dropping off a minute late is unremarkable,
        // and it avoids spending the exact-alarm budget on cosmetics.
        manager.setAndAllowWhileIdle(AlarmManager.RTC, atMillis, pendingIntent)
    }

    /**
     * A short backstop redraw while a sync is believed to be running.
     *
     * Belt and braces for the "syncing" label: if the worker never gets to report
     * back -- killed, cancelled, or simply lost -- this makes the widget re-read the
     * flag shortly afterwards and clear it. A stuck label is otherwise invisible to
     * the app, because only a redraw would notice.
     */
    fun scheduleSyncRecheck(context: Context, atMillis: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            RECHECK_REQUEST_CODE,
            Intent(context, WidgetMidnightReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.setAndAllowWhileIdle(AlarmManager.RTC, atMillis, pendingIntent)
    }

    fun cancelMidnightRefresh(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, WidgetMidnightReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.cancel(pendingIntent)
    }
}
