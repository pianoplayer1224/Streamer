package com.streamer.timetable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.streamer.timetable.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-books alarms after a restart.
 *
 * Android drops every scheduled alarm on reboot, so without this a phone restarted
 * overnight would silently stop reminding until the app was next opened -- exactly
 * when a morning reminder matters most.
 *
 * That applies to the widget's day-rollover alarm as well as to notifications. Left
 * out, a widget on a phone rebooted overnight would keep showing the previous day.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AlertScheduler.reschedule(appContext)
                WidgetUpdater.scheduleMidnightRefresh(appContext)
                WidgetUpdater.refresh(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
