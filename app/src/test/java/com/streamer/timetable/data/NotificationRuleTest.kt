package com.streamer.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class NotificationRuleTest {

    private val monday: LocalDate = LocalDate.of(2026, 9, 7)

    private fun millis(date: LocalDate, time: String) =
        LocalDateTime.of(date, LocalTime.parse(time))
            .atZone(SCHOOL_ZONE).toInstant().toEpochMilli()

    private fun event(
        id: Long = 1,
        date: LocalDate = monday,
        start: String = "15:30",
        end: String = "16:00",
        title: String = "KEYBOARD: Music Block Group 12",
        location: String? = "G.09",
        tutor: String? = "Miss L Yang",
        staff: String? = null,
        feed: Feed = Feed.INSTRUMENTAL,
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
        location = location,
        staff = staff,
        tutor = tutor,
        notes = null,
        clashCount = 0,
        isOnline = false,
        isAccompanied = false,
        syncedAtMillis = 0L,
    )

    // ---- matching --------------------------------------------------------

    @Test
    fun titleMatchIsCaseInsensitiveSubstring() {
        val rule = NotificationRule(titleContains = "keyboard")
        assertTrue(rule.matches(event()))
        assertFalse(rule.matches(event(title = "Maths")))
    }

    /** Criteria combine with AND: every field set has to hold. */
    @Test
    fun multipleCriteriaMustAllMatch() {
        val rule = NotificationRule(titleContains = "KEYBOARD", locationContains = "G.09")
        assertTrue(rule.matches(event()))
        assertFalse("wrong room should not match", rule.matches(event(location = "A.01")))
    }

    /**
     * The feeds disagree about where the teacher's name lives -- instrumental fills
     * InstTutorFullName, others fill EventStaff. A rule written against one must not
     * silently fail against the other.
     */
    @Test
    fun teacherMatchConsidersBothTutorAndStaffFields() {
        val rule = NotificationRule(tutorContains = "Yang")
        assertTrue(rule.matches(event(tutor = "Miss L Yang", staff = null)))
        assertTrue(rule.matches(event(tutor = null, staff = "Miss L Yang")))
        assertFalse(rule.matches(event(tutor = null, staff = "Mr B Smith")))
    }

    @Test
    fun categoryRestrictsToOneFeed() {
        val rule = NotificationRule(feedKey = Feed.INSTRUMENTAL.key)
        assertTrue(rule.matches(event(feed = Feed.INSTRUMENTAL)))
        assertFalse(rule.matches(event(feed = Feed.SIMS_ACADEMIC)))
    }

    /** An unconstrained rule would match the entire timetable, so it must not fire. */
    @Test
    fun ruleWithNoCriteriaNeverMatches() {
        val rule = NotificationRule()
        assertFalse(rule.isComplete)
        assertFalse(rule.matches(event()))
    }

    @Test
    fun blankCriteriaAreTreatedAsUnset() {
        val rule = NotificationRule(titleContains = "   ", locationContains = "")
        assertEquals(0, rule.criteriaCount)
        assertFalse(rule.matches(event()))
    }

    /** Matching on criteria rather than id is what carries a rule to future lessons. */
    @Test
    fun ruleAppliesToLaterOccurrencesWithDifferentIds() {
        val rule = NotificationRule(titleContains = "KEYBOARD")
        val nextTerm = event(id = 999999, date = monday.plusWeeks(9))
        assertTrue(rule.matches(nextTerm))
    }

    // ---- timing ----------------------------------------------------------

    @Test
    fun leadTimeSubtractsFromTheStart() {
        val rule = NotificationRule(titleContains = "x", minutesBefore = 15)
        val e = event(start = "15:30")
        assertEquals(e.startMillis - 15 * 60_000L, rule.triggerAtMillis(e))
    }

    @Test
    fun fixedTimeFiresOnTheEventsOwnDay() {
        val rule = NotificationRule(
            titleContains = "x",
            timingMode = TimingMode.FIXED_TIME,
            fixedTime = "07:30",
            fixedAnchor = FixedAnchor.EVENT_DAY,
        )
        assertEquals(millis(monday, "07:30"), rule.triggerAtMillis(event(date = monday)))
    }

    @Test
    fun fixedTimeCanFireTheDayBefore() {
        val rule = NotificationRule(
            titleContains = "x",
            timingMode = TimingMode.FIXED_TIME,
            fixedTime = "20:00",
            fixedAnchor = FixedAnchor.DAY_BEFORE,
        )
        assertEquals(
            millis(monday.minusDays(1), "20:00"),
            rule.triggerAtMillis(event(date = monday)),
        )
    }

    /** A malformed stored time yields no trigger rather than throwing during a sync. */
    @Test
    fun malformedFixedTimeYieldsNoTrigger() {
        val rule = NotificationRule(
            titleContains = "x",
            timingMode = TimingMode.FIXED_TIME,
            fixedTime = "nonsense",
        )
        assertEquals(null, rule.triggerAtMillis(event()))
    }

    // ---- scheduling ------------------------------------------------------

    /**
     * The stored window reaches two weeks into the past. Scheduling those would fire
     * a burst of notifications for lessons that already happened.
     */
    @Test
    fun pastAlertsAreNotScheduled() {
        val rule = NotificationRule(id = 1, titleContains = "KEYBOARD")
        val past = event(id = 1, date = monday.minusDays(3))
        val future = event(id = 2, date = monday.plusDays(3))
        val now = millis(monday, "12:00")

        val alerts = buildPendingAlerts(listOf(rule), listOf(past, future), now)
        assertEquals(1, alerts.size)
        assertEquals("instrumental:2", alerts.single().event.uid)
    }

    @Test
    fun disabledRulesAreSkipped() {
        val rule = NotificationRule(id = 1, titleContains = "KEYBOARD", enabled = false)
        val alerts = buildPendingAlerts(
            listOf(rule),
            listOf(event(date = monday.plusDays(3))),
            millis(monday, "12:00"),
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun alertsAreOrderedSoonestFirstAndCapped() {
        val rule = NotificationRule(id = 1, titleContains = "KEYBOARD")
        val events = (1..10).map { event(id = it.toLong(), date = monday.plusDays(it.toLong())) }
        val alerts = buildPendingAlerts(listOf(rule), events, millis(monday, "00:01"), limit = 4)

        assertEquals(4, alerts.size)
        assertEquals(alerts.map { it.triggerAtMillis }.sorted(), alerts.map { it.triggerAtMillis })
    }

    /** Two rules over one lesson must stay separate alarms, not overwrite each other. */
    @Test
    fun twoRulesMatchingOneEventGetDistinctSlots() {
        val a = NotificationRule(id = 1, titleContains = "KEYBOARD", minutesBefore = 10)
        val b = NotificationRule(id = 2, titleContains = "KEYBOARD", minutesBefore = 60)
        val alerts = buildPendingAlerts(
            listOf(a, b),
            listOf(event(date = monday.plusDays(1))),
            millis(monday, "00:01"),
        )
        assertEquals(2, alerts.size)
        assertEquals(2, alerts.map { it.requestCode }.distinct().size)
    }

    /** The same rule and event must map to the same slot, so a rebuild replaces it. */
    @Test
    fun requestCodeIsStableAcrossRebuilds() {
        val rule = NotificationRule(id = 1, titleContains = "KEYBOARD")
        val e = event(date = monday.plusDays(1))
        val first = buildPendingAlerts(listOf(rule), listOf(e), millis(monday, "00:01")).single()
        val second = buildPendingAlerts(listOf(rule), listOf(e), millis(monday, "00:01")).single()
        assertEquals(first.requestCode, second.requestCode)
    }

    /** "Start of week" resolves to Monday of the event's own week, not a day offset. */
    @Test
    fun weekStartAnchorFiresOnMondayOfThatWeek() {
        val rule = NotificationRule(
            id = 1,
            titleContains = "x",
            timingMode = TimingMode.FIXED_TIME,
            fixedTime = "07:30",
            fixedAnchor = FixedAnchor.WEEK_START,
        )
        // Thursday's lesson should still resolve back to that Monday.
        val thursday = event(date = monday.plusDays(3))
        assertEquals(millis(monday, "07:30"), rule.triggerAtMillis(thursday))
    }

    /**
     * A weekly rule matching several lessons must not fire once per lesson at the
     * same instant on Monday morning.
     */
    @Test
    fun weekStartRuleCollapsesToOneAlertPerWeek() {
        val rule = NotificationRule(
            id = 1,
            titleContains = "KEYBOARD",
            timingMode = TimingMode.FIXED_TIME,
            fixedTime = "07:30",
            fixedAnchor = FixedAnchor.WEEK_START,
        )
        val thisWeek = listOf(
            event(id = 1, date = monday.plusDays(1)),
            event(id = 2, date = monday.plusDays(2)),
            event(id = 3, date = monday.plusDays(3)),
        )
        val nextWeek = listOf(event(id = 4, date = monday.plusDays(8)))

        val alerts = buildPendingAlerts(
            listOf(rule),
            thisWeek + nextWeek,
            millis(monday.minusDays(1), "12:00"),
        )

        // One per week, not one per lesson.
        assertEquals(2, alerts.size)
        // And the one kept names the week's earliest matching lesson.
        assertEquals("instrumental:1", alerts.first().event.uid)
    }

    /** Lead-time rules are unaffected by the weekly collapse. */
    @Test
    fun leadTimeRulesStillFirePerLesson() {
        val rule = NotificationRule(id = 1, titleContains = "KEYBOARD", minutesBefore = 10)
        val events = listOf(
            event(id = 1, date = monday.plusDays(1)),
            event(id = 2, date = monday.plusDays(2)),
        )
        val alerts = buildPendingAlerts(listOf(rule), events, millis(monday, "00:01"))
        assertEquals(2, alerts.size)
    }
}
