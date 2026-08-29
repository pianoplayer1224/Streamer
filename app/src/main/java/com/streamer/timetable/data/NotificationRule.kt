package com.streamer.timetable.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** Which day a [TimingMode.FIXED_TIME] rule fires on, relative to the event. */
enum class FixedAnchor {
    /** The event's own day. */
    EVENT_DAY,

    /** The evening before. */
    DAY_BEFORE,

    /** Monday of the event's week, for a look-ahead at the start of the week. */
    WEEK_START,
}

/** How a rule decides *when* to fire relative to the events it matches. */
enum class TimingMode {
    /** A number of minutes before the event starts. */
    BEFORE_START,

    /** A wall-clock time, on the event's day or the day before. */
    FIXED_TIME,
}

/**
 * A saved notification rule.
 *
 * Rules are matched against events by *criteria* rather than pinned to a specific
 * event id, which is what lets one rule keep working for every future occurrence:
 * "any lesson with KEYBOARD in the title" survives next term's new event ids, where
 * a rule bound to id 876132 would fire once and then be dead.
 *
 * Criteria are combined with AND -- every field that is set must match. Fields left
 * null are simply not considered.
 */
@Entity(tableName = "notification_rules")
data class NotificationRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String = "",
    val enabled: Boolean = true,

    /** Substring of the event title, case-insensitive. */
    val titleContains: String? = null,

    /** Substring of the room, case-insensitive. */
    val locationContains: String? = null,

    /** Substring of the tutor or staff name, case-insensitive. */
    val tutorContains: String? = null,

    /** A [Feed] key, to restrict the rule to one category. */
    val feedKey: String? = null,

    val timingMode: TimingMode = TimingMode.BEFORE_START,

    /** Minutes before the start, for [TimingMode.BEFORE_START]. */
    val minutesBefore: Int = 15,

    /** Wall-clock time "HH:mm", for [TimingMode.FIXED_TIME]. */
    val fixedTime: String = "07:30",

    val fixedAnchor: FixedAnchor = FixedAnchor.EVENT_DAY,
) {
    /**
     * How many criteria this rule actually constrains on.
     *
     * A rule with none would match every event in the timetable, so it is treated as
     * incomplete rather than as a wildcard -- silently notifying for everything is
     * never what someone meant to build.
     */
    val criteriaCount: Int
        get() = listOf(titleContains, locationContains, tutorContains, feedKey)
            .count { !it.isNullOrBlank() }

    val isComplete: Boolean get() = criteriaCount > 0

    val feed: Feed? get() = feedKey?.let { Feed.fromKey(it) }

    /** A short human description of the criteria, for the rule list. */
    fun criteriaSummary(): String {
        val parts = buildList {
            titleContains?.takeIf { it.isNotBlank() }?.let { add("name has \"$it\"") }
            locationContains?.takeIf { it.isNotBlank() }?.let { add("room $it") }
            tutorContains?.takeIf { it.isNotBlank() }?.let { add("with $it") }
            feed?.let { add(it.label.lowercase()) }
        }
        return if (parts.isEmpty()) "No criteria set" else parts.joinToString(", ")
    }

    /** A short human description of the timing. */
    fun timingSummary(): String = when (timingMode) {
        TimingMode.BEFORE_START -> when {
            minutesBefore == 0 -> "At start time"
            minutesBefore % 60 == 0 -> "${minutesBefore / 60} h before"
            else -> "$minutesBefore min before"
        }

        TimingMode.FIXED_TIME -> when (fixedAnchor) {
            FixedAnchor.EVENT_DAY -> "At $fixedTime on the day"
            FixedAnchor.DAY_BEFORE -> "At $fixedTime the day before"
            FixedAnchor.WEEK_START -> "At $fixedTime on Monday"
        }
    }
}

/**
 * Whether [event] satisfies every criterion this rule sets.
 *
 * Tutor matching also considers the staff field: the two feeds populate different
 * columns for what a student thinks of as the same thing, so checking only one would
 * make a rule work for instrumental lessons but silently fail for academic ones.
 */
fun NotificationRule.matches(event: Event): Boolean {
    if (!isComplete) return false

    titleContains?.takeIf { it.isNotBlank() }?.let {
        if (!event.title.contains(it, ignoreCase = true)) return false
    }
    locationContains?.takeIf { it.isNotBlank() }?.let {
        if (event.location?.contains(it, ignoreCase = true) != true) return false
    }
    tutorContains?.takeIf { it.isNotBlank() }?.let {
        val who = listOfNotNull(event.tutor, event.staff).joinToString(" ")
        if (!who.contains(it, ignoreCase = true)) return false
    }
    feedKey?.takeIf { it.isNotBlank() }?.let {
        if (event.feed != it) return false
    }
    return true
}

/**
 * When this rule should fire for [event], in epoch millis.
 *
 * Returns null when the rule's own configuration cannot produce a time, which is
 * only possible if a stored time string is malformed.
 */
fun NotificationRule.triggerAtMillis(event: Event): Long? = when (timingMode) {
    TimingMode.BEFORE_START -> event.startMillis - minutesBefore * 60_000L

    TimingMode.FIXED_TIME -> runCatching {
        val eventDay = LocalDate.parse(event.startDate)
        val day = when (fixedAnchor) {
            FixedAnchor.EVENT_DAY -> eventDay
            FixedAnchor.DAY_BEFORE -> eventDay.minusDays(1)
            FixedAnchor.WEEK_START -> eventDay.with(
                TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
            )
        }
        day.atTime(LocalTime.parse(fixedTime))
            .atZone(SCHOOL_ZONE)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
}

/** One scheduled firing: a rule, the event that triggered it, and when. */
data class PendingAlert(
    val rule: NotificationRule,
    val event: Event,
    val triggerAtMillis: Long,
) {
    /**
     * Stable identity for the alarm slot.
     *
     * Combining rule and event means rescheduling replaces the same alarm rather
     * than stacking duplicates, and two rules matching one lesson stay distinct.
     */
    val requestCode: Int get() = (rule.id.toString() + "|" + event.uid).hashCode()
}

/**
 * Every alert that should be scheduled, soonest first.
 *
 * Alerts already in the past are dropped: after a sync the window includes days gone
 * by, and scheduling those would fire a burst of notifications for lessons that
 * already happened.
 */
fun buildPendingAlerts(
    rules: List<NotificationRule>,
    events: List<Event>,
    nowMillis: Long,
    limit: Int = 100,
): List<PendingAlert> = rules
    .filter { it.enabled && it.isComplete }
    .flatMap { rule ->
        events.filter { rule.matches(it) }.mapNotNull { event ->
            rule.triggerAtMillis(event)
                ?.takeIf { it > nowMillis }
                ?.let { PendingAlert(rule, event, it) }
        }
    }
    .let(::collapseWeeklyDuplicates)
    .sortedBy { it.triggerAtMillis }
    .take(limit)

/**
 * Reduces a week-start rule to one alert per week.
 *
 * Without this, a rule anchored to Monday morning that matches five lessons would
 * fire five notifications at the same instant. The earliest lesson is kept, so the
 * reminder still names something real.
 */
private fun collapseWeeklyDuplicates(alerts: List<PendingAlert>): List<PendingAlert> {
    val (weekly, rest) = alerts.partition {
        it.rule.timingMode == TimingMode.FIXED_TIME &&
            it.rule.fixedAnchor == FixedAnchor.WEEK_START
    }
    val collapsed = weekly
        .groupBy { it.rule.id to it.triggerAtMillis }
        .map { (_, group) -> group.minBy { it.event.startMillis } }
    return rest + collapsed
}
