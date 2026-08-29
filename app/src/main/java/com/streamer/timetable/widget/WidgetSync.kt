package com.streamer.timetable.widget

import android.content.Context

/**
 * Tracks whether a widget-triggered sync is in flight.
 *
 * Stored as a start *timestamp* rather than a boolean, deliberately. The previous
 * boolean was set before the sync and cleared in a `finally`, which only runs if the
 * process survives. A widget click runs inside a broadcast, and Android may kill that
 * process after about ten seconds -- so a sync that overran left the flag stuck true,
 * and the "already syncing" guard then ignored every later press. The button appeared
 * to work sometimes and do nothing at other times, with no way to reset it.
 *
 * A timestamp cannot latch: it simply ages out.
 */
object WidgetSync {

    private const val KEY_STARTED_AT = "widget_sync_started_at"

    /**
     * How long a sync may claim to be running before the claim is disbelieved.
     *
     * Comfortably longer than a real sync, short enough that a killed one does not
     * disable the button for any noticeable time.
     */
    private const val STALE_AFTER_MILLIS = 90_000L

    fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("streamer_sync", Context.MODE_PRIVATE)

    fun isRunning(context: Context): Boolean {
        val startedAt = prefs(context).getLong(KEY_STARTED_AT, 0L)
        if (startedAt == 0L) return false
        return System.currentTimeMillis() - startedAt < STALE_AFTER_MILLIS
    }

    fun begin(context: Context) {
        prefs(context).edit().putLong(KEY_STARTED_AT, System.currentTimeMillis()).apply()
    }

    fun end(context: Context) {
        prefs(context).edit().remove(KEY_STARTED_AT).apply()
    }

    /**
     * Records how the last widget-triggered sync went.
     *
     * Without this an offline sync and a sync that never ran look identical on the
     * home screen: the label disappears and nothing changes. The outcome is shown
     * briefly so a failure explains itself rather than reading as a dead button.
     */
    fun recordResult(context: Context, result: String) {
        prefs(context).edit()
            .putString(KEY_LAST_RESULT, result)
            .putLong(KEY_LAST_RESULT_AT, System.currentTimeMillis())
            .apply()
    }

    /** The recent outcome, or null once it has aged out of relevance. */
    fun recentResult(context: Context): String? {
        val prefs = prefs(context)
        val at = prefs.getLong(KEY_LAST_RESULT_AT, 0L)
        if (at == 0L || System.currentTimeMillis() - at > RESULT_VISIBLE_MILLIS) return null
        return prefs.getString(KEY_LAST_RESULT, null)
    }

    private const val KEY_LAST_RESULT = "widget_last_result"
    private const val KEY_LAST_RESULT_AT = "widget_last_result_at"

    /** Long enough to read, short enough not to mask the staleness note. */
    private const val RESULT_VISIBLE_MILLIS = 45_000L
}
