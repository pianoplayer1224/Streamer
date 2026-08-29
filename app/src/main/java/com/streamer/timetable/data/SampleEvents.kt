package com.streamer.timetable.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Marks a row as locally generated, so sample data is never mistaken for real. */
const val SAMPLE_PREFIX = "sample"

/**
 * Fabricated events for exercising features the live feed cannot currently show.
 *
 * Prep is the reason this exists: `get-prepevents.php` returns an empty array for
 * this student, so the Prep tab and any prep notification rule are untestable
 * against real data. These fill that gap.
 *
 * Two caveats are deliberate rather than oversights:
 *
 *  - The rows sit inside the sync window, so the next sync of those feeds will
 *    delete them. That is correct behaviour -- reconciliation removes anything the
 *    server did not return -- and it means sample data cannot quietly persist and be
 *    mistaken for a real lesson later.
 *  - Ids are offset far above real server ids so they cannot collide with anything
 *    the feed returns.
 */
fun sampleEvents(today: LocalDate, syncedAtMillis: Long = System.currentTimeMillis()): List<Event> {
    fun millis(date: LocalDate, time: String): Long =
        LocalDateTime.of(date, LocalTime.parse(time))
            .atZone(SCHOOL_ZONE).toInstant().toEpochMilli()

    fun make(
        n: Int,
        feed: Feed,
        date: LocalDate,
        start: String,
        end: String,
        title: String,
        location: String? = null,
        tutor: String? = null,
        notes: String? = null,
    ) = Event(
        uid = "$SAMPLE_PREFIX:${feed.key}:$n",
        serverId = 9_000_000L + n,
        feed = feed.key,
        filterId = -1,
        startDate = date.toString(),
        startMillis = millis(date, start),
        endMillis = millis(date, end),
        startTime = start,
        endTime = end,
        title = title,
        location = location,
        staff = null,
        tutor = tutor,
        notes = notes,
        clashCount = 0,
        isOnline = false,
        isAccompanied = false,
        syncedAtMillis = syncedAtMillis,
    )

    return listOf(
        // Prep, the feed with no real data. Start and end are the same instant
        // because prep is a deadline rather than a slot.
        make(
            1, Feed.PREP, today.plusDays(1), "16:00", "16:00",
            title = "Physics: chapter 4 questions",
            notes = "Sample prep - hand in via the portal",
        ),
        make(
            2, Feed.PREP, today.plusDays(3), "09:00", "09:00",
            title = "Maths: past paper section B",
            notes = "Sample prep",
        ),
        make(
            3, Feed.PREP, today.plusDays(7), "23:59", "23:59",
            title = "Music: harmony exercises",
            notes = "Sample prep",
        ),

        // A lesson soon enough to test a short-lead notification rule today.
        make(
            4, Feed.INSTRUMENTAL, today, "17:30", "18:00",
            title = "KEYBOARD: sample lesson",
            location = "G.09",
            tutor = "Miss L Yang",
        ),

        // A deliberate overlap, so the clash rail and the detail sheet's
        // "clashes with" list have something to show.
        make(
            5, Feed.SIMS_ACADEMIC, today.plusDays(1), "11:00", "12:00",
            title = "Sample Physics",
            notes = "12A/Ph2",
        ),
        make(
            6, Feed.OTHER_ACTIVITY, today.plusDays(1), "11:30", "12:30",
            title = "Sample clashing activity",
            location = "Hall",
        ),

        // Outside 08:30-16:30, to exercise the school-day edge dividers.
        make(
            7, Feed.OTHER_MUSICAL, today.plusDays(1), "07:45", "08:15",
            title = "Sample early practice",
            location = "Practice room 3",
        ),
        make(
            8, Feed.OTHER_MUSICAL, today.plusDays(1), "17:00", "18:30",
            title = "Sample orchestra",
            location = "Concert hall",
        ),
    )
}
