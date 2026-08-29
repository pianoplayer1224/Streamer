package com.streamer.timetable.ui

import com.streamer.timetable.data.Event
import com.streamer.timetable.data.Feed
import java.time.DayOfWeek
import com.streamer.timetable.data.SCHOOL_ZONE
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** Sessions the student treats as noise; hidden by default and excluded from clashes. */
const val MUS_BLOCK_TITLE = "Mus Block"

enum class TimetableTab(val label: String) {
    THIS_WEEK("This week"),
    UPCOMING("Upcoming"),
    PREP("Prep"),
    ALL("All"),
}

/**
 * Where an event sits within a run of overlapping events.
 *
 * The flags let the clash rail be drawn as one unbroken line down a group rather
 * than as separate stripes per card, which is what makes a clash read as a single
 * conflict instead of two unrelated warnings.
 */
data class ClashState(
    val inClash: Boolean = false,
    val continuesAbove: Boolean = false,
    val continuesBelow: Boolean = false,
    /** The other events in this overlap, so the detail sheet can name them. */
    val clashesWith: List<ClashPeer> = emptyList(),
)

/** A conflicting event, reduced to what is worth naming in the detail sheet. */
data class ClashPeer(
    val title: String,
    val startTime: String,
    val endTime: String,
)

/**
 * Flat index of a day's header inside the rendered list.
 *
 * The list is flat -- each day contributes one header plus one item per row -- so a
 * day's position in `sections` is not its position in the list. Scrolling needs this
 * conversion, and getting it wrong lands the viewport in an unrelated day.
 */
fun flatIndexOfSection(sections: List<DaySection>, sectionIndex: Int): Int {
    var index = 0
    for (i in 0 until sectionIndex.coerceAtMost(sections.size)) {
        index += 1 + sections[i].rows.size
    }
    return index
}

/** The first day at or after [today], or -1 when every day is in the past. */
fun indexOfFirstDayFrom(sections: List<DaySection>, today: LocalDate): Int =
    sections.indexOfFirst { !it.date.isBefore(today) }

sealed interface DayRow {
    data class Lesson(val event: Event, val clash: ClashState) : DayRow

    /** A labelled rule between lessons: break, lunch, or the edges of the school day. */
    data class Interval(val label: String) : DayRow
}

data class DaySection(
    val date: LocalDate,
    val rows: List<DayRow>,
    /** Drives the heavier divider drawn where one week gives way to the next. */
    val startsNewWeek: Boolean = false,
) {
    val events: List<Event> get() = rows.filterIsInstance<DayRow.Lesson>().map { it.event }
}

/** Gaps inside the school day that earn a divider, and what to call them. */
private val INTERVALS = listOf(
    LocalTime.of(10, 30) to "Break",
    LocalTime.of(13, 0) to "Lunch",
)

/** The edges of the normal school day. Anything outside them is worth labelling. */
private val SCHOOL_START: LocalTime = LocalTime.of(8, 30)
private val SCHOOL_END: LocalTime = LocalTime.of(16, 30)

/** Monday-start weeks, matching the site's `firstDay: 1`. */
fun weekStart(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

fun isCurrentWeek(date: LocalDate, today: LocalDate): Boolean =
    weekStart(date) == weekStart(today)

/**
 * The clock, on whichever day is being displayed.
 *
 * With a debug date set, the real clock belongs to a different day entirely, so
 * judging "already finished" against it would mark every lesson as still to come.
 * Time-of-day is taken from the real clock and applied to the displayed date.
 */
fun effectiveNowMillis(today: LocalDate): Long =
    if (today == LocalDate.now(SCHOOL_ZONE)) {
        System.currentTimeMillis()
    } else {
        today.atTime(LocalTime.now(SCHOOL_ZONE))
            .atZone(SCHOOL_ZONE).toInstant().toEpochMilli()
    }

/**
 * Turns stored events into the day-grouped rows the list renders.
 *
 * Filtering happens before clash detection, deliberately. A clash that exists only
 * because of a hidden Mus Block session is not a clash the student can act on, so
 * hiding the session resolves the warning rather than leaving a red bar with no
 * visible cause.
 */
fun buildSections(
    events: List<Event>,
    tab: TimetableTab,
    enabledFeeds: Set<Feed>,
    hideMusBlock: Boolean,
    today: LocalDate,
    nowMillis: Long,
): List<DaySection> {
    val visible = events.asSequence()
        .filter { it.feedType in enabledFeeds }
        .filter { !(hideMusBlock && it.title.equals(MUS_BLOCK_TITLE, ignoreCase = true)) }
        .filter { matchesTab(it, tab, today, nowMillis) }
        .toList()

    var previousWeek: LocalDate? = null

    return visible
        .groupBy { LocalDate.parse(it.startDate) }
        .toSortedMap()
        .map { (date, dayEvents) ->
            val week = weekStart(date)
            // The first day in the list opens its week rather than breaking from one.
            val newWeek = previousWeek != null && week != previousWeek
            previousWeek = week
            DaySection(date, buildDayRows(dayEvents), startsNewWeek = newWeek)
        }
}

/**
 * How many events of each category the given tab would show.
 *
 * Applies the tab and Mus Block filters but deliberately *not* the feed filter: the
 * number has to mean "how many are here", not "how many are here right now", or
 * switching a chip off would zero its own count and there would be nothing left to
 * say what turning it back on would bring.
 *
 * Shares [matchesTab] with [buildSections], so a chip can never claim a count the
 * list does not go on to show.
 */
fun countVisibleByFeed(
    events: List<Event>,
    tab: TimetableTab,
    hideMusBlock: Boolean,
    today: LocalDate,
    nowMillis: Long,
): Map<Feed, Int> = events.asSequence()
    .filter { !(hideMusBlock && it.title.equals(MUS_BLOCK_TITLE, ignoreCase = true)) }
    .filter { matchesTab(it, tab, today, nowMillis) }
    .mapNotNull { it.feedType }
    .groupingBy { it }
    .eachCount()

/**
 * Which events a tab shows.
 *
 * Upcoming looks forward only, and additionally starts with the routine academic
 * timetable switched off (handled by the view model, not here). Days already gone are
 * hidden outright, and so is anything earlier today that has already finished.
 *
 * "Finished" is judged on the *end* time, matching the widget: a lesson you are
 * sitting in has not finished, and dropping it the moment it began would remove it
 * exactly when you might check the room.
 */
private fun matchesTab(
    event: Event,
    tab: TimetableTab,
    today: LocalDate,
    nowMillis: Long,
): Boolean {
    val date = LocalDate.parse(event.startDate)
    val feed = event.feedType ?: return false

    return when (tab) {
        TimetableTab.THIS_WEEK -> isCurrentWeek(date, today)
        TimetableTab.PREP -> feed == Feed.PREP
        // The date test still matters: an instant such as "Term Starts" has no end
        // to run past, so without it a marker from a past day would linger forever.
        TimetableTab.UPCOMING ->
            !date.isBefore(today) && (event.isInstant || event.endMillis > nowMillis)
        TimetableTab.ALL -> true
    }
}

/**
 * Orders a day, marks overlapping runs, and drops dividers into the real gaps.
 *
 * Public because the widget renders from it too. Deriving clashes and break markers
 * twice would eventually produce two answers, and a widget contradicting the app
 * about whether a lesson clashes is worse than neither showing it.
 */
fun buildDayRows(dayEvents: List<Event>): List<DayRow> {
    val sorted = dayEvents.sortedWith(
        compareBy({ !it.isInstant }, { it.startMillis }, { it.endMillis }, { it.title })
    )

    val clusters = clusterOverlaps(sorted)
    val rows = mutableListOf<DayRow>()

    // Both edges are boundary rules, like Break and Lunch: they sit *between* the
    // lessons they separate rather than heading the day. Only drawn when something
    // actually falls outside the school day, so a normal day carries no extra
    // furniture.
    val timed = sorted.filterNot { it.isInstant }
    val hasEarly = timed.any { startOf(it) < SCHOOL_START }
    val beforeSchoolUid =
        if (hasEarly) timed.firstOrNull { startOf(it) >= SCHOOL_START }?.uid else null
    val afterSchoolUid = timed.firstOrNull { startOf(it) >= SCHOOL_END }?.uid

    sorted.forEachIndexed { index, event ->
        val cluster = clusters[index]
        val previous = sorted.getOrNull(index - 1)

        when {
            // After-school is checked first so that a day made up of only an early
            // and a late lesson gets one rule between them rather than two stacked.
            event.uid == afterSchoolUid && index > 0 ->
                rows += DayRow.Interval("After school")

            event.uid == beforeSchoolUid && index > 0 ->
                rows += DayRow.Interval("Before school")

            // A divider only makes sense in an actual gap, so a lesson spanning
            // 10:30 correctly gets no break drawn through it.
            previous != null -> intervalBetween(previous, event)?.let {
                rows += DayRow.Interval(it)
            }
        }

        val sameAbove = cluster >= 0 && index > 0 && clusters[index - 1] == cluster
        val sameBelow = cluster >= 0 && index < sorted.lastIndex && clusters[index + 1] == cluster

        val peers = if (cluster >= 0) {
            sorted.filterIndexed { j, _ -> j != index && clusters[j] == cluster }
                .map { ClashPeer(it.title, it.startTime, it.endTime) }
        } else {
            emptyList()
        }

        rows += DayRow.Lesson(
            event = event,
            clash = ClashState(
                inClash = cluster >= 0,
                continuesAbove = sameAbove,
                continuesBelow = sameBelow,
                clashesWith = peers,
            ),
        )
    }

    return rows
}

/**
 * Assigns each event a cluster id, or -1 when it overlaps nothing.
 *
 * Overlap is strict: back-to-back lessons where one ends exactly as the next begins
 * (14:30-15:30 followed by 15:30-16:30, which is common here) must not be treated
 * as clashing, or most of the timetable would light up red.
 *
 * Instants such as "Term Starts" are skipped entirely -- a zero-length marker
 * cannot meaningfully conflict with a lesson.
 */
private fun clusterOverlaps(sorted: List<Event>): IntArray {
    val clusters = IntArray(sorted.size) { -1 }
    var nextCluster = 0

    for (i in sorted.indices) {
        if (sorted[i].isInstant) continue
        for (j in i + 1 until sorted.size) {
            val a = sorted[i]
            val b = sorted[j]
            if (b.isInstant) continue
            // Sorted by start, so once a later event starts at or after this one
            // ends, nothing further can overlap it.
            if (b.startMillis >= a.endMillis) break

            if (a.startMillis < b.endMillis && b.startMillis < a.endMillis) {
                val existing = listOf(clusters[i], clusters[j]).firstOrNull { it >= 0 }
                val id = existing ?: nextCluster++
                clusters[i] = id
                clusters[j] = id
            }
        }
    }
    return clusters
}

private fun startOf(event: Event): LocalTime = LocalTime.parse(event.startTime)

private fun intervalBetween(previous: Event, next: Event): String? {
    if (previous.isInstant || next.isInstant) return null
    if (next.startMillis <= previous.endMillis) return null

    val gapStart = LocalTime.parse(previous.endTime)
    val gapEnd = LocalTime.parse(next.startTime)

    return INTERVALS.firstOrNull { (boundary, _) ->
        !boundary.isBefore(gapStart) && !boundary.isAfter(gapEnd)
    }?.second
}
