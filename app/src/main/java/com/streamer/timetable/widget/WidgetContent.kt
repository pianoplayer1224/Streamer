package com.streamer.timetable.widget

import com.streamer.timetable.data.Event
import com.streamer.timetable.ui.DayRow
import com.streamer.timetable.ui.MUS_BLOCK_TITLE
import com.streamer.timetable.ui.buildDayRows
import java.time.LocalDate

/**
 * How many rows a widget may hold.
 *
 * RemoteViews has a hard memory budget per widget and the launcher silently drops one
 * that exceeds it, so a cap is not cosmetic. It sits well above what fits on screen
 * because the list scrolls -- the cap guards memory, not layout.
 */
const val WIDGET_MAX_ROWS = 14

/**
 * What the widget renders.
 *
 * Holds the same [DayRow] structure the app's list uses, so clash marking and break
 * dividers come from one place rather than being recomputed here.
 */
data class WidgetContent(
    val date: LocalDate,
    val rows: List<DayRow>,
    val hiddenCount: Int,
    val lastSyncedMillis: Long,
) {
    val lessons: List<Event>
        get() = rows.filterIsInstance<DayRow.Lesson>().map { it.event }

    val clashingUids: Set<String>
        get() = rows.filterIsInstance<DayRow.Lesson>()
            .filter { it.clash.inClash }
            .map { it.event.uid }
            .toSet()

    val isEmpty: Boolean get() = lessons.isEmpty()

    fun isClashing(event: Event): Boolean = event.uid in clashingUids
}

/**
 * Picks today's remaining lessons.
 *
 * "Remaining" is judged on the *end* time, not the start: a lesson you are sitting in
 * has not finished and should stay on the widget, whereas judging by start time would
 * drop it the moment it began -- exactly when you might glance to check the room.
 *
 * Mus Block is honoured here too, so the widget agrees with the app rather than
 * showing sessions the timetable is deliberately hiding.
 */
fun buildWidgetContent(
    events: List<Event>,
    today: LocalDate,
    nowMillis: Long,
    hideMusBlock: Boolean,
    lastSyncedMillis: Long,
    maxRows: Int = WIDGET_MAX_ROWS,
): WidgetContent {
    val remaining = events.asSequence()
        .filter { it.startDate == today.toString() }
        .filter { !(hideMusBlock && it.title.equals(MUS_BLOCK_TITLE, ignoreCase = true)) }
        // Instants such as "Term Starts" have no duration to run out, so they stay
        // for the whole day rather than vanishing at midnight-plus-one.
        .filter { it.isInstant || it.endMillis > nowMillis }
        .sortedWith(compareBy({ !it.isInstant }, { it.startMillis }, { it.title }))
        .toList()

    // Capped before structuring, so clashes and dividers are computed across exactly
    // what will be drawn: a red bar always has a visible partner, and a divider never
    // marks a gap between two lessons the widget is not showing.
    val shown = remaining.take(maxRows)

    return WidgetContent(
        date = today,
        rows = buildDayRows(shown),
        hiddenCount = (remaining.size - maxRows).coerceAtLeast(0),
        lastSyncedMillis = lastSyncedMillis,
    )
}
