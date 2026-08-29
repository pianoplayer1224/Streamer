package com.streamer.timetable.widget

import com.streamer.timetable.data.Event
import com.streamer.timetable.data.Feed
import com.streamer.timetable.data.SCHOOL_ZONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class WidgetContentTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 7)

    private fun millis(date: LocalDate, time: String) =
        LocalDateTime.of(date, LocalTime.parse(time))
            .atZone(SCHOOL_ZONE).toInstant().toEpochMilli()

    private fun event(
        id: Long,
        date: LocalDate = today,
        start: String,
        end: String,
        title: String = "Lesson",
        feed: Feed = Feed.SIMS_ACADEMIC,
    ) = Event(
        uid = "${feed.key}:$id",
        serverId = id,
        feed = feed.key,
        filterId = 0,
        startDate = date.toString(),
        startMillis = millis(date, start),
        endMillis = millis(date, end),
        startTime = start,
        endTime = end,
        title = title,
        location = "G.09",
        staff = null,
        tutor = null,
        notes = null,
        clashCount = 0,
        isOnline = false,
        isAccompanied = false,
        syncedAtMillis = 0L,
    )

    private fun content(
        events: List<Event>,
        now: String,
        hideMusBlock: Boolean = true,
        maxRows: Int = WIDGET_MAX_ROWS,
    ) = buildWidgetContent(
        events = events,
        today = today,
        nowMillis = millis(today, now),
        hideMusBlock = hideMusBlock,
        lastSyncedMillis = 0L,
        maxRows = maxRows,
    )

    /**
     * A lesson in progress must stay listed. Judging on start time would drop it the
     * moment it began, which is exactly when the room might be checked.
     */
    @Test
    fun lessonInProgressIsStillShown() {
        val result = content(
            listOf(event(1, start = "09:00", end = "10:00", title = "Physics")),
            now = "09:30",
        )
        assertEquals(listOf("Physics"), result.lessons.map { it.title })
    }

    @Test
    fun finishedLessonsAreDropped() {
        val result = content(
            listOf(
                event(1, start = "09:00", end = "10:00", title = "Done"),
                event(2, start = "11:00", end = "12:00", title = "Later"),
            ),
            now = "10:30",
        )
        assertEquals(listOf("Later"), result.lessons.map { it.title })
    }

    @Test
    fun otherDaysAreExcluded() {
        val result = content(
            listOf(
                event(1, date = today.plusDays(1), start = "09:00", end = "10:00", title = "Tomorrow"),
                event(2, start = "09:00", end = "10:00", title = "Today"),
            ),
            now = "08:00",
        )
        assertEquals(listOf("Today"), result.lessons.map { it.title })
    }

    /** The widget must agree with the app rather than showing what it hides. */
    @Test
    fun musBlockIsHonoured() {
        val events = listOf(
            event(1, start = "15:30", end = "16:30", title = "Mus Block"),
            event(2, start = "15:30", end = "16:00", title = "KEYBOARD", feed = Feed.INSTRUMENTAL),
        )
        assertEquals(1, content(events, now = "12:00", hideMusBlock = true).lessons.size)
        assertEquals(2, content(events, now = "12:00", hideMusBlock = false).lessons.size)
    }

    /** RemoteViews has a memory budget, so the row count is capped and reported. */
    @Test
    fun rowsAreCappedAndOverflowCounted() {
        val events = (1..10).map {
            event(it.toLong(), start = "%02d:00".format(it + 7), end = "%02d:45".format(it + 7))
        }
        val result = content(events, now = "00:01", maxRows = 4)
        assertEquals(4, result.lessons.size)
        assertEquals(6, result.hiddenCount)
    }

    @Test
    fun noOverflowWhenEverythingFits() {
        val result = content(
            listOf(event(1, start = "09:00", end = "10:00")),
            now = "08:00",
            maxRows = 4,
        )
        assertEquals(0, result.hiddenCount)
    }

    /** An all-day marker has no end to run past, so it stays for the whole day. */
    @Test
    fun instantMarkersSurviveAllDay() {
        val result = content(
            listOf(event(1, start = "08:30", end = "08:30", title = "Term Starts", feed = Feed.TERM_DATES)),
            now = "20:00",
        )
        assertEquals(listOf("Term Starts"), result.lessons.map { it.title })
    }

    @Test
    fun endOfDayYieldsEmptyContent() {
        val result = content(
            listOf(event(1, start = "09:00", end = "10:00")),
            now = "18:00",
        )
        assertTrue(result.isEmpty)
    }

    @Test
    fun lessonsAreOrderedByStartTime() {
        val result = content(
            listOf(
                event(1, start = "14:00", end = "15:00", title = "Later"),
                event(2, start = "09:00", end = "10:00", title = "Earlier"),
            ),
            now = "08:00",
        )
        assertEquals(listOf("Earlier", "Later"), result.lessons.map { it.title })
    }

    // ---- clash marking ---------------------------------------------------

    /** Overlapping lessons on the widget are flagged, exactly as in the app. */
    @Test
    fun overlappingLessonsAreFlagged() {
        val result = content(
            listOf(
                event(1, start = "15:30", end = "16:30", title = "Mus Block", feed = Feed.SIMS_ACADEMIC),
                event(2, start = "15:30", end = "16:00", title = "KEYBOARD", feed = Feed.INSTRUMENTAL),
            ),
            now = "12:00",
            hideMusBlock = false,
        )
        assertEquals(2, result.clashingUids.size)
        assertTrue(result.lessons.all { result.isClashing(it) })
    }

    /** Back-to-back lessons are not a clash, matching the app's strict rule. */
    @Test
    fun backToBackLessonsAreNotFlagged() {
        val result = content(
            listOf(
                event(1, start = "14:30", end = "15:30"),
                event(2, start = "15:30", end = "16:30"),
            ),
            now = "12:00",
        )
        assertTrue(result.clashingUids.isEmpty())
    }

    /**
     * A clash whose partner has already finished is not marked: the red bar would
     * have no visible counterpart, and the conflict is no longer actionable.
     */
    @Test
    fun clashWithAFinishedLessonIsNotFlagged() {
        val result = content(
            listOf(
                event(1, start = "09:00", end = "10:00", title = "Over"),
                event(2, start = "09:30", end = "12:00", title = "Ongoing"),
            ),
            now = "10:30",
        )
        assertEquals(listOf("Ongoing"), result.lessons.map { it.title })
        assertTrue(result.clashingUids.isEmpty())
    }

    /** Hiding Mus Block resolves the clash it caused, as it does in the app. */
    @Test
    fun hidingMusBlockClearsItsClash() {
        val events = listOf(
            event(1, start = "15:30", end = "16:30", title = "Mus Block", feed = Feed.SIMS_ACADEMIC),
            event(2, start = "15:30", end = "16:00", title = "KEYBOARD", feed = Feed.INSTRUMENTAL),
        )
        val result = content(events, now = "12:00", hideMusBlock = true)
        assertEquals(1, result.lessons.size)
        assertTrue(result.clashingUids.isEmpty())
    }

    /**
     * Regression guard for the debug-date bug.
     *
     * The widget once judged "finished" against the real clock while displaying a
     * debug date, so every lesson on that day counted as still to come and nothing
     * was ever filtered. The clock must belong to the day being shown.
     */
    @Test
    fun finishedFilteringUsesTheDisplayedDayNotTheRealClock() {
        val events = listOf(
            event(1, start = "09:00", end = "10:00", title = "Over"),
            event(2, start = "14:00", end = "15:00", title = "Still to come"),
        )

        // Midday on the displayed day: one gone, one remaining.
        val onDay = buildWidgetContent(
            events = events,
            today = today,
            nowMillis = millis(today, "12:00"),
            hideMusBlock = true,
            lastSyncedMillis = 0L,
        )
        assertEquals(listOf("Still to come"), onDay.lessons.map { it.title })

        // A clock from an earlier date would wrongly keep both.
        val wrongClock = buildWidgetContent(
            events = events,
            today = today,
            nowMillis = millis(today.minusDays(9), "12:00"),
            hideMusBlock = true,
            lastSyncedMillis = 0L,
        )
        assertEquals(2, wrongClock.lessons.size)
    }

    /** Break rules come through from the shared day structure. */
    @Test
    fun breakRulesAppearBetweenLessons() {
        val result = content(
            listOf(
                event(1, start = "09:30", end = "10:30"),
                event(2, start = "11:00", end = "12:00"),
            ),
            now = "08:00",
        )
        val intervals = result.rows.filterIsInstance<com.streamer.timetable.ui.DayRow.Interval>()
        assertEquals(1, intervals.size)
    }
}
