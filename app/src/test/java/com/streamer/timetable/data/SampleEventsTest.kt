package com.streamer.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SampleEventsTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 7)
    private val samples = sampleEvents(today, syncedAtMillis = 0L)

    /** Prep is the whole reason these exist: the live feed returns none. */
    @Test
    fun includesPrepEvents() {
        val prep = samples.filter { it.feedType == Feed.PREP }
        assertTrue("expected several prep items, got ${prep.size}", prep.size >= 3)
        assertTrue("prep should be deadline shaped", prep.all { it.isInstant })
    }

    /** Ids must sit far above real server ids so they cannot collide with the feed. */
    @Test
    fun sampleIdsCannotCollideWithServerIds() {
        assertTrue(samples.all { it.serverId > 1_000_000 })
        assertTrue(samples.all { it.uid.startsWith(SAMPLE_PREFIX) })
    }

    /** Every row is uniquely keyed, or an upsert would silently drop some. */
    @Test
    fun uidsAreUnique() {
        assertEquals(samples.size, samples.map { it.uid }.distinct().size)
    }

    /** They must land near today, or they would not appear in the current view. */
    @Test
    fun eventsSitWithinTheVisibleWindow() {
        val dates = samples.map { LocalDate.parse(it.startDate) }
        assertTrue(dates.all { !it.isBefore(today) && it.isBefore(today.plusDays(14)) })
    }

    /** One pair overlaps deliberately, so the clash rail has something to render. */
    @Test
    fun includesADeliberateClash() {
        val byDay = samples.filterNot { it.isInstant }.groupBy { it.startDate }
        val hasOverlap = byDay.values.any { day ->
            day.any { a -> day.any { b -> a !== b && a.startMillis < b.endMillis && b.startMillis < a.endMillis } }
        }
        assertTrue("expected an overlapping pair", hasOverlap)
    }

    /** And something outside 08:30-16:30, to exercise the school-day dividers. */
    @Test
    fun includesEventsOutsideTheSchoolDay() {
        assertTrue(samples.any { it.startTime < "08:30" })
        assertTrue(samples.any { it.startTime >= "16:30" })
    }
}
