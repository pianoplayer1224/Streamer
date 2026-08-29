package com.streamer.timetable.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.streamer.timetable.data.AppDatabase
import com.streamer.timetable.data.PendingAlert
import com.streamer.timetable.data.buildPendingAlerts

/**
 * Turns notification rules into scheduled alarms.
 *
 * Alarms are rescheduled wholesale rather than incrementally -- after a sync, after a
 * rule edit, and after a reboot. A timetable changes underneath us (lessons move, get
 * cancelled), so reconciling individual alarms would mean tracking which ones are
 * still valid; recomputing the whole set from the current data cannot drift.
 *
 * Request codes are derived from rule and event ids, so a rebuild replaces matching
 * alarms in place via FLAG_UPDATE_CURRENT rather than stacking duplicates. An alarm
 * whose event has since been cancelled is caught at delivery instead: [AlertReceiver]
 * confirms the event still exists before showing anything.
 */
object AlertScheduler {

    const val CHANNEL_ID = "lesson_alerts"

    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_NOTIFICATION_ID = "notification_id"

    /**
     * How many alarms to hold at once.
     *
     * The system caps how many alarms an app may schedule, and there is no value in
     * booking something six weeks out that a later sync will recompute anyway. Each
     * sync re-runs this, so the window keeps rolling forward.
     */
    private const val MAX_SCHEDULED = 50

    suspend fun reschedule(context: Context) {
        val appContext = context.applicationContext
        val database = AppDatabase.get(appContext)

        val alerts = buildPendingAlerts(
            rules = database.notificationRuleDao().getAll(),
            events = database.eventDao().allOnce(),
            nowMillis = System.currentTimeMillis(),
            limit = MAX_SCHEDULED,
        )

        ensureChannel(appContext)
        alerts.forEach { schedule(appContext, it) }
    }

    /**
     * What the scheduler currently believes, for the debug panel.
     *
     * Notification failures are silent by nature -- nothing arrives, and there is no
     * way to tell a wrong rule from a missing permission from an alert that is simply
     * still in the future. This reports all three.
     */
    data class Diagnostics(
        val notificationsAllowed: Boolean,
        val exactAlarmsAllowed: Boolean,
        val ruleCount: Int,
        val scheduledCount: Int,
        val nextAlertAtMillis: Long?,
        val nextAlertLabel: String?,
    )

    suspend fun diagnose(context: Context): Diagnostics {
        val appContext = context.applicationContext
        val database = AppDatabase.get(appContext)
        val rules = database.notificationRuleDao().getAll()

        val alerts = buildPendingAlerts(
            rules = rules,
            events = database.eventDao().allOnce(),
            nowMillis = System.currentTimeMillis(),
            limit = MAX_SCHEDULED,
        )

        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        val next = alerts.firstOrNull()

        return Diagnostics(
            notificationsAllowed = NotificationManagerCompat.from(appContext)
                .areNotificationsEnabled(),
            exactAlarmsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager?.canScheduleExactAlarms() == true,
            ruleCount = rules.size,
            scheduledCount = alerts.size,
            nextAlertAtMillis = next?.triggerAtMillis,
            nextAlertLabel = next?.event?.title,
        )
    }

    /** Posts a notification immediately, to prove the delivery path end to end. */
    fun showTestNotification(context: Context) {
        val intent = Intent(context, AlertReceiver::class.java).apply {
            putExtra(EXTRA_TITLE, "Streamer test")
            putExtra(EXTRA_TEXT, "If you can see this, notifications work.")
            putExtra(EXTRA_NOTIFICATION_ID, TEST_NOTIFICATION_ID)
        }
        context.sendBroadcast(intent)
    }

    /**
     * Books a real alarm a short way out.
     *
     * Distinct from the immediate test: this exercises AlarmManager and the wake-up
     * path, which is where an exact-alarm restriction would show up.
     */
    fun scheduleTestAlert(context: Context, delaySeconds: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        ensureChannel(context)

        val intent = Intent(context, AlertReceiver::class.java).apply {
            putExtra(EXTRA_TITLE, "Streamer scheduled test")
            putExtra(EXTRA_TEXT, "Booked ${'$'}delaySeconds seconds earlier.")
            putExtra(EXTRA_NOTIFICATION_ID, TEST_NOTIFICATION_ID + 1)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            TEST_NOTIFICATION_ID + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val at = System.currentTimeMillis() + delaySeconds * 1000
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private const val TEST_NOTIFICATION_ID = 990001

    private fun schedule(context: Context, alert: PendingAlert) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return

        val intent = Intent(context, AlertReceiver::class.java).apply {
            putExtra(EXTRA_TITLE, alert.event.title)
            putExtra(EXTRA_TEXT, describe(alert))
            putExtra(EXTRA_NOTIFICATION_ID, alert.requestCode)
        }

        val pending = PendingIntent.getBroadcast(
            context,
            alert.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Exact alarms need a user-granted permission from Android 12 onward. A
        // lesson reminder that drifts by fifteen minutes is worthless, so exact is
        // attempted first -- but an inexact alarm still beats no alarm, so the
        // fallback is silent rather than an error the user cannot act on here.
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            manager.canScheduleExactAlarms()

        if (canBeExact) {
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                alert.triggerAtMillis,
                pending,
            )
        } else {
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                alert.triggerAtMillis,
                pending,
            )
        }
    }

    private fun describe(alert: PendingAlert): String {
        val event = alert.event
        val time = if (event.isInstant) "all day" else "${event.startTime} - ${event.endTime}"
        val where = event.location?.let { " - $it" }.orEmpty()
        val who = (event.tutor ?: event.staff)?.let { " - $it" }.orEmpty()
        return "$time$where$who"
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Lesson alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Reminders for lessons matching your notification rules"
            }
        )
    }
}
