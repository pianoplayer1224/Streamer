package com.streamer.timetable.ui

import com.streamer.timetable.data.Event
import com.streamer.timetable.data.Feed
import com.streamer.timetable.data.SCHOOL_ZONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class TimetableSectionsTest {

    private val monday = LocalDate.of(2026, 9, 7)

    private fun event(
        id: Long,
        date: LocalDate = monday,
        start: String,
        end: String,
        title: String = "Lesson",
        feed: Feed = Feed.SIMS_ACADEMIC,
    ): Event {
        fun millis(t: String) = LocalDateTime.of(date, LocalTime.parse(t))
            .atZone(SCHOOL_ZONE).toInstant().toEpochMilli()

        return Event(
            uid = "${feed.key}:$id",
            serverId = id,
            feed = feed.key,
            filterId = 0,
            startDate = date.toString(),
            startMillis = millis(start),
            endMillis = millis(end),
            startTime = start,
            endTime = end,
            title = title,
            location = null,
            staff = null,
            tutor = null,
            notes = null,
            clashCount = 0,
            isOnline = false,
            isAccompanied = false,
            syncedAtMillis = 0L,
        )
    }

    private fun millisOn(date: LocalDate, time: String) =
        LocalDateTime.of(date, LocalTime.parse(time))
            .atZone(SCHOOL_ZONE).toInstant().toEpochMilli()

    private fun sections(
        events: List<Event>,
        tab: TimetableTab = TimetableTab.ALL,
        hideMusBlock: Boolean = false,
        today: LocalDate = monday,
        // Start of day by default: nothing has finished yet, which is what these
        // tests assumed before the clock existed.
        nowMillis: Long = millisOn(monday, "00:00"),
        weekStartDay: java.time.DayOfWeek = java.time.DayOfWeek.MONDAY,
    ) = buildSections(
        events, tab, Feed.entries.toSet(), hideMusBlock, today, nowMillis, weekStartDay,
    )

    private fun counts(
        events: List<Event>,
        tab: TimetableTab,
        hideMusBlock: Boolean = false,
        today: LocalDate = monday,
        nowMillis: Long = millisOn(monday, "00:00"),
    ) = countVisibleByFeed(events, tab, hideMusBlock, today, nowMillis)

    private fun lessons(section: DaySection) = section.rows.filterIsInstance<DayRow.Lesson>()

    private fun intervals(section: DaySection) =
        section.rows.filterIsInstance<DayRow.Interval>().map { it.label }

    // ---- clash detection -------------------------------------------------

    /**
     * The real case: Mus Block 15:30-16:30 against a keyboard lesson 15:30-16:00,
     * across two different feeds. Both must be flagged.
     */
    @Test
    fun overlappingEventsAcrossFeedsClash() {
        val result = sections(
            listOf(
                event(1, start = "15:30", end = "16:30", title = "Mus Block"),
                event(2, start = "15:30", end = "16:00", title = "KEYBOARD", feed = Feed.INSTRUMENTAL),
            )
        )
        val rows = lessons(result.single())
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.clash.inClash })
    }

    /**
     * 14:30-15:30 followed by 15:30-16:30 is the normal shape of this timetable.
     * Treating touching events as clashing would paint most of the week red.
     */
    @Test
    fun backToBackLessonsDoNotClash() {
        val result = sections(
            listOf(
                event(1, start = "14:30", end = "15:30"),
                event(2, start = "15:30", end = "16:30"),
            )
        )
        assertTrue(lessons(result.single()).none { it.clash.inClash })
    }

    /** The rail must join up: the first continues below, the last continues above. */
    @Test
    fun clashRailIsContinuousAcrossTheGroup() {
        val result = sections(
            listOf(
                event(1, start = "09:00", end = "11:00"),
                event(2, start = "09:30", end = "10:00", feed = Feed.INSTRUMENTAL),
                event(3, start = "09:45", end = "10:15", feed = Feed.MEDICAL),
            )
        )
        val rows = lessons(result.single())
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.clash.inClash })

        assertFalse("first has nothing above it", rows.first().clash.continuesAbove)
        assertTrue("first joins downward", rows.first().clash.continuesBelow)
        assertTrue("middle joins upward", rows[1].clash.continuesAbove)
        assertTrue("middle joins downward", rows[1].clash.continuesBelow)
        assertTrue("last joins upward", rows.last().clash.continuesAbove)
        assertFalse("last has nothing below it", rows.last().clash.continuesBelow)
    }

    /** A lone lesson is never a clash, however the day is arranged around it. */
    @Test
    fun nonOverlappingLessonsAreNotFlagged() {
        val result = sections(
            listOf(
                event(1, start = "09:30", end = "10:30"),
                event(2, start = "11:00", end = "12:00"),
            )
        )
        assertTrue(lessons(result.single()).none { it.clash.inClash })
    }

    /** Zero-length markers such as "Term Starts" cannot conflict with a lesson. */
    @Test
    fun instantMarkersDoNotCauseClashes() {
        val result = sections(
            listOf(
                event(1, start = "08:30", end = "08:30", title = "Term Starts", feed = Feed.TERM_DATES),
                event(2, start = "08:30", end = "09:30"),
            )
        )
        assertTrue(lessons(result.single()).none { it.clash.inClash })
    }

    /**
     * Hiding Mus Block must resolve the clash it caused, not leave a red bar with
     * nothing visible to explain it.
     */
    @Test
    fun hidingMusBlockAlsoRemovesTheClashItCaused() {
        val events = listOf(
            event(1, start = "15:30", end = "16:30", title = "Mus Block"),
            event(2, start = "15:30", end = "16:00", title = "KEYBOARD", feed = Feed.INSTRUMENTAL),
        )

        val shown = lessons(sections(events, hideMusBlock = false).single())
        assertTrue("clash expected while Mus Block is visible", shown.all { it.clash.inClash })

        val hidden = lessons(sections(events, hideMusBlock = true).single())
        assertEquals(1, hidden.size)
        assertEquals("KEYBOARD", hidden.single().event.title)
        assertFalse("clash should resolve with Mus Block hidden", hidden.single().clash.inClash)
    }

    // ---- break and lunch dividers ---------------------------------------

    @Test
    fun breakAndLunchDividersLandInTheGaps() {
        val result = sections(
            listOf(
                event(1, start = "09:30", end = "10:30"),
                event(2, start = "11:00", end = "12:00"),
                event(3, start = "12:00", end = "13:00"),
                event(4, start = "14:30", end = "15:30"),
            )
        )
        val labels = result.single().rows.filterIsInstance<DayRow.Interval>().map { it.label }
        assertEquals(listOf("Break", "Lunch"), labels)
    }

    /** A lesson running through 10:30 has no gap, so no divider may be drawn. */
    @Test
    fun noDividerWhenALessonSpansTheBoundary() {
        val result = sections(
            listOf(
                event(1, start = "10:00", end = "11:00"),
                event(2, start = "11:00", end = "12:00"),
            )
        )
        assertTrue(result.single().rows.filterIsInstance<DayRow.Interval>().isEmpty())
    }

    // ---- tabs ------------------------------------------------------------

    /** Upcoming looks forward only; days already gone are not upcoming. */
    @Test
    fun upcomingTabHidesPastDays() {
        val events = listOf(
            event(1, date = monday.minusDays(1), start = "09:00", end = "10:00",
                title = "Past", feed = Feed.INSTRUMENTAL),
            event(2, date = monday, start = "09:00", end = "10:00",
                title = "Today", feed = Feed.INSTRUMENTAL),
            event(3, date = monday.plusDays(2), start = "09:00", end = "10:00",
                title = "Later", feed = Feed.INSTRUMENTAL),
        )
        val upcoming = sections(events, tab = TimetableTab.UPCOMING)
            .flatMap { lessons(it) }.map { it.event.title }

        // Today counts as upcoming; yesterday does not.
        assertEquals(listOf("Today", "Later"), upcoming)

        // All still shows everything, so the two tabs are genuinely different.
        val all = sections(events, tab = TimetableTab.ALL)
            .flatMap { lessons(it) }.map { it.event.title }
        assertEquals(listOf("Past", "Today", "Later"), all)
    }

    /** Turning the timetable feed off is what makes Upcoming different. */
    @Test
    fun droppingTheTimetableFeedLeavesTheIrregularEvents() {
        val events = listOf(
            event(1, start = "09:30", end = "10:30", title = "Maths"),
            event(2, start = "15:30", end = "16:00", title = "KEYBOARD", feed = Feed.INSTRUMENTAL),
        )
        val titles = buildSections(
            events,
            TimetableTab.UPCOMING,
            Feed.entries.toSet() - Feed.SIMS_ACADEMIC,
            hideMusBlock = false,
            today = monday,
            nowMillis = millisOn(monday, "00:00"),
        ).flatMap { lessons(it) }.map { it.event.title }
        assertEquals(listOf("KEYBOARD"), titles)
    }

    // ---- school-day edges ------------------------------------------------

    /**
     * The rule separates the early lesson from the school day, so it sits between
     * the two rather than heading the day.
     */
    @Test
    fun beforeSchoolLabelSeparatesEarlyLessonsFromTheSchoolDay() {
        val early = sections(
            listOf(
                event(1, start = "07:45", end = "08:30", title = "Early practice"),
                event(2, start = "09:30", end = "10:30"),
            )
        ).single()
        assertEquals("Before school", intervals(early).first())

        // Row order must be: early lesson, rule, school-day lesson.
        val labels = early.rows.map {
            when (it) {
                is DayRow.Lesson -> it.event.title
                is DayRow.Interval -> "<${it.label}>"
            }
        }
        assertEquals(listOf("Early practice", "<Before school>", "Lesson"), labels)

        val normal = sections(
            listOf(
                event(1, start = "08:30", end = "09:30"),
                event(2, start = "09:30", end = "10:30"),
            )
        ).single()
        assertTrue(intervals(normal).none { it == "Before school" })
    }

    @Test
    fun afterSchoolLabelAppearsOnlyForLateLessons() {
        val late = sections(
            listOf(
                event(1, start = "15:30", end = "16:30"),
                event(2, start = "16:30", end = "17:30", title = "Orchestra"),
            )
        ).single()
        assertTrue(intervals(late).contains("After school"))

        val normal = sections(
            listOf(
                event(1, start = "14:30", end = "15:30"),
                event(2, start = "15:30", end = "16:30"),
            )
        ).single()
        assertTrue(intervals(normal).none { it == "After school" })
    }

    /** A day can be labelled at both ends without the two interfering. */
    @Test
    fun bothEdgesCanBeLabelledOnOneDay() {
        val day = sections(
            listOf(
                event(1, start = "07:45", end = "08:15", title = "Early"),
                event(2, start = "09:30", end = "10:30"),
                event(3, start = "17:00", end = "18:00", title = "Late"),
            )
        ).single()
        val labels = intervals(day)
        assertTrue(labels.contains("Before school"))
        assertTrue(labels.contains("After school"))
    }

    // ---- week grouping ---------------------------------------------------

    /** Only the boundary day flags a new week, and never the very first day. */
    @Test
    fun newWeekIsFlaggedAtTheBoundaryOnly() {
        val events = listOf(
            event(1, date = monday, start = "09:00", end = "10:00"),
            event(2, date = monday.plusDays(1), start = "09:00", end = "10:00"),
            event(3, date = monday.plusDays(7), start = "09:00", end = "10:00"),
        )
        val result = sections(events)
        assertEquals(3, result.size)
        assertFalse("first day opens its week", result[0].startsNewWeek)
        assertFalse("same week as previous", result[1].startsNewWeek)
        assertTrue("crosses into a new week", result[2].startsNewWeek)
    }

    @Test
    fun thisWeekTabCoversMondayToSundayOnly() {
        val events = listOf(
            event(1, date = monday.minusDays(3), start = "09:00", end = "10:00", title = "LastWeek"),
            event(2, date = monday, start = "09:00", end = "10:00", title = "ThisWeek"),
            event(3, date = monday.plusDays(6), start = "09:00", end = "10:00", title = "Sunday"),
            event(4, date = monday.plusDays(7), start = "09:00", end = "10:00", title = "NextWeek"),
        )
        val titles = sections(events, tab = TimetableTab.THIS_WEEK)
            .flatMap { lessons(it) }.map { it.event.title }
        assertEquals(listOf("ThisWeek", "Sunday"), titles)
    }

    @Test
    fun prepTabShowsOnlyPrep() {
        val events = listOf(
            event(1, start = "09:00", end = "10:00", title = "Maths"),
            event(2, start = "09:00", end = "10:00", title = "Essay", feed = Feed.PREP),
        )
        val titles = sections(events, tab = TimetableTab.PREP)
            .flatMap { lessons(it) }.map { it.event.title }
        assertEquals(listOf("Essay"), titles)
    }

    /** Weeks run Monday-start, matching the site's `firstDay: 1`. */
    @Test
    fun weeksStartOnMonday() {
        assertEquals(monday, weekStart(monday))
        assertEquals(monday, weekStart(monday.plusDays(6)))
        assertTrue(isCurrentWeek(monday.plusDays(6), monday))
        assertFalse(isCurrentWeek(monday.plusDays(7), monday))
        assertFalse(isCurrentWeek(monday.minusDays(1), monday))
    }

    // ---- clash naming ----------------------------------------------------

    /** The detail sheet needs to name the conflict, not just report that one exists. */
    @Test
    fun clashCarriesTheOtherEventsItCollidesWith() {
        val result = sections(
            listOf(
                event(1, start = "15:30", end = "16:30", title = "Mus Block"),
                event(2, start = "15:30", end = "16:00", title = "KEYBOARD", feed = Feed.INSTRUMENTAL),
            )
        )
        val rows = lessons(result.single()).associateBy { it.event.title }

        val keyboardPeers = rows.getValue("KEYBOARD").clash.clashesWith
        assertEquals(1, keyboardPeers.size)
        assertEquals("Mus Block", keyboardPeers.single().title)
        assertEquals("15:30", keyboardPeers.single().startTime)
        assertEquals("16:30", keyboardPeers.single().endTime)

        // An event never lists itself as its own conflict.
        val musPeers = rows.getValue("Mus Block").clash.clashesWith
        assertEquals(listOf("KEYBOARD"), musPeers.map { it.title })
    }

    @Test
    fun eventsWithoutAClashListNoPeers() {
        val result = sections(
            listOf(
                event(1, start = "09:30", end = "10:30"),
                event(2, start = "11:00", end = "12:00"),
            )
        )
        assertTrue(lessons(result.single()).all { it.clash.clashesWith.isEmpty() })
    }

    // ---- scroll anchoring ------------------------------------------------

    /**
     * The rendered list is flat: one header per day plus one item per row. Scrolling
     * by section index lands in the wrong day entirely once any day has events, so
     * the conversion has to account for every item each day contributes.
     */
    @Test
    fun flatIndexAccountsForHeadersAndRows() {
        val result = sections(
            listOf(
                event(1, date = monday, start = "09:30", end = "10:30"),
                event(2, date = monday, start = "11:00", end = "12:00"),
                event(3, date = monday.plusDays(1), start = "09:00", end = "10:00"),
            )
        )
        assertEquals(2, result.size)

        // Day one: header + 2 lessons + the Break rule between them = 4 items.
        val dayOneItems = 1 + result[0].rows.size
        assertEquals(0, flatIndexOfSection(result, 0))
        assertEquals(dayOneItems, flatIndexOfSection(result, 1))
        assertTrue("second day must not start at index 1", flatIndexOfSection(result, 1) > 1)
    }

    @Test
    fun firstDayFromTodayFallsThroughEmptyDays() {
        val result = sections(
            listOf(
                event(1, date = monday.minusDays(2), start = "09:00", end = "10:00"),
                event(2, date = monday.plusDays(3), start = "09:00", end = "10:00"),
            )
        )
        // Today itself has no events, so it anchors on the next day that does.
        assertEquals(1, indexOfFirstDayFrom(result, monday))
        assertEquals(0, indexOfFirstDayFrom(result, monday.minusDays(5)))
        assertEquals(-1, indexOfFirstDayFrom(result, monday.plusDays(10)))
    }

    // ---- chip counts -----------------------------------------------------

    /** A chip's number must describe the tab it is sitting on, not the whole store. */
    @Test
    fun countsAreScopedToTheTab() {
        val events = listOf(
            event(1, date = monday.minusDays(3), start = "09:00", end = "10:00",
                title = "LastWeek", feed = Feed.INSTRUMENTAL),
            event(2, date = monday, start = "09:00", end = "10:00",
                title = "ThisWeek", feed = Feed.INSTRUMENTAL),
            event(3, date = monday, start = "11:00", end = "12:00",
                title = "Maths", feed = Feed.SIMS_ACADEMIC),
        )

        val thisWeek = counts(events, TimetableTab.THIS_WEEK)
        assertEquals(1, thisWeek[Feed.INSTRUMENTAL])
        assertEquals(1, thisWeek[Feed.SIMS_ACADEMIC])

        val all = counts(events, TimetableTab.ALL)
        assertEquals(2, all[Feed.INSTRUMENTAL])
    }

    /** Upcoming counts look forward only, matching what that tab lists. */
    @Test
    fun countsFollowTheUpcomingDateFilter() {
        val events = listOf(
            event(1, date = monday.minusDays(1), start = "09:00", end = "10:00",
                feed = Feed.INSTRUMENTAL),
            event(2, date = monday.plusDays(1), start = "09:00", end = "10:00",
                feed = Feed.INSTRUMENTAL),
        )
        val upcoming = counts(events, TimetableTab.UPCOMING)
        assertEquals(1, upcoming[Feed.INSTRUMENTAL])
    }

    /**
     * Turning a chip off must not zero its own count, or there would be nothing left
     * to say what switching it back on would bring.
     */
    @Test
    fun countsIgnoreWhichChipsAreEnabled() {
        val events = listOf(
            event(1, date = monday, start = "09:00", end = "10:00", feed = Feed.INSTRUMENTAL),
        )
        // countVisibleByFeed takes no feed set at all, which is the guarantee.
        val counts = counts(events, TimetableTab.ALL)
        assertEquals(1, counts[Feed.INSTRUMENTAL])
    }

    /** Hidden Mus Block sessions are excluded from the counts as well as the list. */
    @Test
    fun countsRespectHiddenMusBlock() {
        val events = listOf(
            event(1, date = monday, start = "15:30", end = "16:30",
                title = "Mus Block", feed = Feed.SIMS_ACADEMIC),
            event(2, date = monday, start = "09:00", end = "10:00",
                title = "Maths", feed = Feed.SIMS_ACADEMIC),
        )
        assertEquals(1, counts(events, TimetableTab.ALL, hideMusBlock = true)[Feed.SIMS_ACADEMIC])
        assertEquals(2, counts(events, TimetableTab.ALL)[Feed.SIMS_ACADEMIC])
    }

    /** The count must equal what the list actually renders for that feed. */
    @Test
    fun countsAgreeWithTheRenderedList() {
        val events = listOf(
            event(1, date = monday, start = "09:00", end = "10:00", feed = Feed.INSTRUMENTAL),
            event(2, date = monday, start = "11:00", end = "12:00", feed = Feed.INSTRUMENTAL),
            event(3, date = monday.plusDays(2), start = "09:00", end = "10:00", feed = Feed.SIMS_ACADEMIC),
        )
        val tab = TimetableTab.THIS_WEEK
        val counts = counts(events, tab)
        val rendered = buildSections(
            events, tab, Feed.entries.toSet(), false, monday, millisOn(monday, "00:00"),
        )
            .flatMap { it.events }
            .mapNotNull { it.feedType }
            .groupingBy { it }
            .eachCount()
        assertEquals(rendered, counts)
    }

    /** Upcoming drops lessons that already finished earlier today. */
    @Test
    fun upcomingHidesLessonsFinishedEarlierToday() {
        val events = listOf(
            event(1, start = "09:00", end = "10:00", title = "Over", feed = Feed.INSTRUMENTAL),
            event(2, start = "14:00", end = "15:00", title = "Later", feed = Feed.INSTRUMENTAL),
        )
        val titles = sections(
            events,
            tab = TimetableTab.UPCOMING,
            nowMillis = millisOn(monday, "12:00"),
        ).flatMap { lessons(it) }.map { it.event.title }

        assertEquals(listOf("Later"), titles)
    }

    /** A lesson in progress is not finished, and must survive. */
    @Test
    fun upcomingKeepsALessonInProgress() {
        val titles = sections(
            listOf(event(1, start = "09:00", end = "10:00", title = "Now", feed = Feed.INSTRUMENTAL)),
            tab = TimetableTab.UPCOMING,
            nowMillis = millisOn(monday, "09:30"),
        ).flatMap { lessons(it) }.map { it.event.title }

        assertEquals(listOf("Now"), titles)
    }

    /** All keeps the whole day regardless of the time; only Upcoming trims. */
    @Test
    fun allTabIsUnaffectedByTheClock() {
        val events = listOf(
            event(1, start = "09:00", end = "10:00", title = "Over"),
            event(2, start = "14:00", end = "15:00", title = "Later"),
        )
        val titles = sections(
            events,
            tab = TimetableTab.ALL,
            nowMillis = millisOn(monday, "12:00"),
        ).flatMap { lessons(it) }.map { it.event.title }

        assertEquals(listOf("Over", "Later"), titles)
    }

    /** Counts must trim with the list, or a chip would promise a lesson that is gone. */
    @Test
    fun upcomingCountsFollowTheClock() {
        val events = listOf(
            event(1, start = "09:00", end = "10:00", feed = Feed.INSTRUMENTAL),
            event(2, start = "14:00", end = "15:00", feed = Feed.INSTRUMENTAL),
        )
        val midday = counts(
            events,
            TimetableTab.UPCOMING,
            nowMillis = millisOn(monday, "12:00"),
        )
        assertEquals(1, midday[Feed.INSTRUMENTAL])
    }

    /** An all-day marker has no end to run past, so it stays for its whole day. */
    @Test
    fun upcomingKeepsTodaysInstantMarkers() {
        val titles = sections(
            listOf(event(1, start = "08:30", end = "08:30", title = "Term Starts", feed = Feed.TERM_DATES)),
            tab = TimetableTab.UPCOMING,
            nowMillis = millisOn(monday, "20:00"),
        ).flatMap { lessons(it) }.map { it.event.title }

        assertEquals(listOf("Term Starts"), titles)
    }

    // ---- configurable week start -----------------------------------------

    /**
     * A Saturday-start week gathers a different seven days.
     *
     * Monday 7 Sep sits in the Sat 5 - Fri 11 window, so Saturday 5th is included
     * where a Monday-start week would have excluded it.
     */
    @Test
    fun thisWeekFollowsTheConfiguredStartDay() {
        val saturday = monday.minusDays(2)
        val events = listOf(
            event(1, date = saturday, start = "09:00", end = "10:00", title = "Sat"),
            event(2, date = monday, start = "09:00", end = "10:00", title = "Mon"),
        )

        val mondayWeek = sections(events, tab = TimetableTab.THIS_WEEK)
            .flatMap { lessons(it) }.map { it.event.title }
        assertEquals(listOf("Mon"), mondayWeek)

        val saturdayWeek = sections(
            events,
            tab = TimetableTab.THIS_WEEK,
            weekStartDay = java.time.DayOfWeek.SATURDAY,
        ).flatMap { lessons(it) }.map { it.event.title }
        assertEquals(listOf("Sat", "Mon"), saturdayWeek)
    }

    /** The far edge moves too: a Saturday week ends on Friday, excluding the next Sat. */
    @Test
    fun saturdayWeekEndsOnFriday() {
        val events = listOf(
            event(1, date = monday.plusDays(4), start = "09:00", end = "10:00", title = "Fri"),
            event(2, date = monday.plusDays(5), start = "09:00", end = "10:00", title = "NextSat"),
        )
        val titles = sections(
            events,
            tab = TimetableTab.THIS_WEEK,
            weekStartDay = java.time.DayOfWeek.SATURDAY,
        ).flatMap { lessons(it) }.map { it.event.title }
        assertEquals(listOf("Fri"), titles)
    }

    /**
     * The heavier week heading is a calendar landmark and must stay on Monday however
     * the week window is configured.
     */
    @Test
    fun mondayMarkerIsIndependentOfTheWeekStartPreference() {
        assertTrue(isMondayMarker(monday))
        assertFalse(isMondayMarker(monday.minusDays(2)))   // Saturday
        assertFalse(isMondayMarker(monday.plusDays(1)))    // Tuesday
    }

    /** Chip counts follow the configured week, or they would disagree with the list. */
    @Test
    fun countsFollowTheConfiguredWeekStart() {
        val saturday = monday.minusDays(2)
        val events = listOf(
            event(1, date = saturday, start = "09:00", end = "10:00", feed = Feed.INSTRUMENTAL),
            event(2, date = monday, start = "09:00", end = "10:00", feed = Feed.INSTRUMENTAL),
        )

        val mondayWeek = countVisibleByFeed(
            events, TimetableTab.THIS_WEEK, false, monday, millisOn(monday, "00:00"),
            java.time.DayOfWeek.MONDAY,
        )
        assertEquals(1, mondayWeek[Feed.INSTRUMENTAL])

        val saturdayWeek = countVisibleByFeed(
            events, TimetableTab.THIS_WEEK, false, monday, millisOn(monday, "00:00"),
            java.time.DayOfWeek.SATURDAY,
        )
        assertEquals(2, saturdayWeek[Feed.INSTRUMENTAL])
    }

    /** Other tabs are unaffected by the week-start preference. */
    @Test
    fun weekStartDayDoesNotAffectOtherTabs() {
        val events = listOf(
            event(1, date = monday.minusDays(2), start = "09:00", end = "10:00", title = "Sat"),
            event(2, date = monday, start = "09:00", end = "10:00", title = "Mon"),
        )
        val all = sections(
            events,
            tab = TimetableTab.ALL,
            weekStartDay = java.time.DayOfWeek.SATURDAY,
        ).flatMap { lessons(it) }.map { it.event.title }
        assertEquals(listOf("Sat", "Mon"), all)
    }
}
