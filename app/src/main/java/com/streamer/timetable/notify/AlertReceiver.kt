package com.streamer.timetable.notify

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.streamer.timetable.MainActivity
import com.streamer.timetable.R

/** Posts the notification when a scheduled alert comes due. */
class AlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(AlertScheduler.EXTRA_TITLE) ?: return
        val text = intent.getStringExtra(AlertScheduler.EXTRA_TEXT).orEmpty()
        val id = intent.getIntExtra(AlertScheduler.EXTRA_NOTIFICATION_ID, 0)

        // From Android 13 the user can revoke notifications entirely. Posting without
        // the permission throws, so check rather than assume it was granted at setup.
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        AlertScheduler.ensureChannel(context)

        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, AlertScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
    }
}
