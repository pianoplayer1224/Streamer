package com.streamer.timetable.data

import com.streamer.timetable.net.StreamApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

class EventParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun parse(body: String): List<Event> {
        val payload = StreamApi.extractJsonArray(body) ?: return emptyList()
        return json.decodeFromString<List<EventDto>>(payload)
            .mapNotNull { it.toEvent(Feed.SIMS_ACADEMIC, syncedAtMillis = 0L) }
    }

    /** A record in exactly the shape the server sends, escaped slashes and all. */
    private val realShapedRecord = """
        [{"id":65916,"start":"2026-09-07 08:30:00","end":"2026-09-07 09:30:00",
        "EventStaff":null,"title":"Tut - Ass","EventLocation":null,
        "StudentNotes":"12A\/At","EventFilterID":3,"ClashCounter":0,
        "InstTutorFullName":null}]
    """.trimIndent()

    @Test
    fun parsesServerRecordShape() {
        val events = parse(realShapedRecord)
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals("simsacademic:65916", event.uid)
        assertEquals("2026-09-07", event.startDate)
        assertEquals("08:30", event.startTime)
        assertEquals("09:30", event.endTime)
        assertEquals("Tut - Ass", event.title)
        // Escaped forward slashes must survive JSON decoding intact.
        assertEquals("12A/At", event.notes)
        assertNull(event.location)
    }

    /**
     * The server has been observed prefixing PHP notices to a JSON body. A strict
     * parser dies on the first character, so this is the regression guard for it.
     */
    @Test
    fun toleratesPhpNoticeBeforeJson() {
        val body = "<br />\n<b>Notice</b>:  Undefined index: username in " +
            "<b>C:\\inetpub\\wwwroot\\StREAM2\\www\\student\\ajax\\sys\\x.php</b> " +
            "on line <b>41</b><br />\n" + realShapedRecord
        val events = parse(body)
        assertEquals(1, events.size)
        assertEquals("Tut - Ass", events.single().title)
    }

    /** Empty feeds are the normal state out of term, not an error. */
    @Test
    fun emptyArrayYieldsNoEvents() {
        assertEquals(0, parse("[]").size)
    }

    @Test
    fun bodyWithNoArrayYieldsNoEvents() {
        assertEquals(0, parse("<html>401</html>").size)
        assertNull(StreamApi.extractJsonArray("no array here"))
    }

    /** A marker event with equal start and end should be kept, flagged as an instant. */
    @Test
    fun zeroLengthEventIsKeptAsInstant() {
        val body = """
            [{"id":810793,"start":"2026-09-07 08:30:00","end":"2026-09-07 08:30:00",
            "title":"Term Starts","EventFilterID":4,"ClashCounter":0}]
        """.trimIndent()
        val event = parse(body).single()
        assertTrue(event.isInstant)
        assertEquals("Term Starts", event.title)
    }

    /** One bad record must not take the whole feed down with it. */
    @Test
    fun malformedRecordIsSkippedNotFatal() {
        val body = """
            [{"id":1,"start":"not-a-date","title":"Broken"},
             {"id":2,"start":"2026-09-07 10:00:00","end":"2026-09-07 11:00:00","title":"Good"}]
        """.trimIndent()
        val events = parse(body)
        assertEquals(1, events.size)
        assertEquals("Good", events.single().title)
    }

    /** A record with no id cannot be keyed, so it is dropped rather than guessed at. */
    @Test
    fun recordWithoutIdIsSkipped() {
        val body = """[{"start":"2026-09-07 10:00:00","title":"No id"}]"""
        assertEquals(0, parse(body).size)
    }

    /** Feed keys namespace the primary key, so ids may repeat across feeds safely. */
    @Test
    fun sameIdInDifferentFeedsDoesNotCollide() {
        val dto = EventDto(id = 100, start = "2026-09-07 10:00:00", end = "2026-09-07 11:00:00", title = "X")
        val a = dto.toEvent(Feed.SIMS_ACADEMIC, 0L)!!
        val b = dto.toEvent(Feed.PREP, 0L)!!
        assertTrue("uids must differ across feeds", a.uid != b.uid)
    }

    @Test
    fun onlineAndAccompaniedFlagsParse() {
        val body = """
            [{"id":876132,"start":"2026-09-09 15:30:00","end":"2026-09-09 16:00:00",
            "title":"KEYBOARD","EventLocation":"G.09","EventFilterID":1,
            "OnlineLesson":"Y","AccompLesson":"N","ClashCounter":0,
            "InstTutorFullName":"Miss L Yang"}]
        """.trimIndent()
        val event = json.decodeFromString<List<EventDto>>(StreamApi.extractJsonArray(body)!!)
            .single().toEvent(Feed.INSTRUMENTAL, 0L)!!
        assertTrue(event.isOnline)
        assertTrue(!event.isAccompanied)
        assertEquals("Miss L Yang", event.tutor)
        assertEquals("G.09", event.location)
    }

    /**
     * Parses the real six-week capture when it is present locally.
     *
     * Skipped rather than failed when the file is absent, so the suite still runs on
     * a clean checkout -- the capture holds real student data and is not committed.
     */
    @Test
    fun parsesRealCaptureWhenAvailable() {
        val capture = File("../recon/range_test.json")
        Assume.assumeTrue("no local capture to check against", capture.exists())

        val raw = capture.readText()
        // The saved file contains a truncated copy followed by the complete one, so
        // decode incrementally and keep the largest valid array.
        val best = Regex("""\[\{"id":""").findAll(raw)
            .mapNotNull { m ->
                runCatching {
                    json.decodeFromString<List<EventDto>>(
                        StreamApi.extractJsonArray(raw.substring(m.range.first))!!
                    )
                }.getOrNull()
            }
            .maxByOrNull { it.size }

        assertNotNull("expected at least one parsable array in the capture", best)
        val events = best!!.mapNotNull { it.toEvent(Feed.SIMS_ACADEMIC, 0L) }

        assertTrue("expected a substantial number of events, got ${events.size}", events.size > 100)
        // Every record in a real feed should survive mapping.
        assertEquals(best.size, events.size)
        assertTrue(events.all { it.startDate.startsWith("2026-") })
        assertTrue(events.all { it.title.isNotBlank() })
    }
}
