package com.streamer.timetable.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The school runs on UK time; the server sends wall-clock strings with no zone. */
val SCHOOL_ZONE: ZoneId = ZoneId.of("Europe/London")

/**
 * The ten calendar sources the site pulls from.
 *
 * FullCalendar appends `start` and `end` to each URL, so the query string here is
 * only the part that identifies the feed. Colours are lifted from the site's own
 * `fcSources` block so the app reads as a companion to it rather than a stranger.
 */
enum class Feed(
    val key: String,
    val path: String,
    val label: String,
    val colour: Long,
) {
    SIMS_ACADEMIC(
        "simsacademic",
        "ajax/get-simsevents-student-academic.php",
        "Timetable",
        0xFF336D85,
    ),
    INSTRUMENTAL(
        "instrumental",
        "ajax/get-events-student-filter-types.php?EventFilterID=1",
        "Instrumental lessons",
        0xFFFC7C00,
    ),
    PREP(
        "prep",
        "ajax/get-prepevents.php",
        "Prep",
        0xFF4C1C85,
    ),
    OTHER_MUSICAL(
        "othermusical",
        "ajax/get-events-student-filter-types.php?EventFilterID=2",
        "Other musical activity",
        0xFFFB6C00,
    ),
    ACADEMIC(
        "academic",
        "ajax/get-events-student-filter-types.php?EventFilterID=3",
        "Academic lessons",
        0xFF336D85,
    ),
    OTHER_ACADEMIC(
        "otheracademic",
        "ajax/get-events-other-academic-activity.php",
        "Other academic activity",
        0xFF295D75,
    ),
    MEDICAL(
        "medical",
        "ajax/get-events-student-filter-types.php?EventFilterID=5",
        "Medical",
        0xFF963821,
    ),
    OTHER_ACTIVITY(
        "otheractivity",
        "ajax/get-events-student-filter-types.php?EventFilterID=6",
        "Other activity",
        0xFF003926,
    ),
    TERM_DATES(
        "termdates",
        "ajax/get-events-term-dates.php",
        "Term dates",
        0xFF295D75,
    ),
    SIMS_OTHER_ACADEMIC(
        "simsotheracademic",
        "ajax/get-simsevents-student-other-academic.php",
        "Other timetabled",
        0xFF295D75,
    ),
    ;

    /** Prep is deadline-shaped rather than slot-shaped and renders differently. */
    val isPrep: Boolean get() = this == PREP

    /**
     * The everyday academic timetable -- Maths, Physics and the like.
     *
     * These are excluded from the Upcoming tab: they recur predictably, so listing
     * them there would bury the irregular events that actually need noticing.
     */
    val isRoutineAcademic: Boolean get() = this == SIMS_ACADEMIC || this == ACADEMIC

    companion object {
        fun fromKey(key: String): Feed? = entries.firstOrNull { it.key == key }
    }
}

/** Exactly the shape the server sends. Unknown keys are ignored by the parser. */
@Serializable
data class EventDto(
    val id: Long? = null,
    val start: String? = null,
    val end: String? = null,
    val title: String? = null,
    @SerialName("EventStaff") val eventStaff: String? = null,
    @SerialName("EventLocation") val eventLocation: String? = null,
    @SerialName("StudentNotes") val studentNotes: String? = null,
    @SerialName("EventFilterID") val eventFilterId: Int? = null,
    @SerialName("OnlineLesson") val onlineLesson: String? = null,
    @SerialName("AccompLesson") val accompLesson: String? = null,
    @SerialName("ClashCounter") val clashCounter: Int? = null,
    @SerialName("InstTutorFullName") val instTutorFullName: String? = null,
)

/**
 * A single timetable entry, as stored offline.
 *
 * The primary key combines the feed with the server id: ids are only unique within
 * a feed, and the observed ranges overlap between them (SIMS ids are five digits,
 * filter-type ids six), so keying on the raw id alone would let one feed silently
 * overwrite another's rows.
 */
@Entity(
    tableName = "events",
    indices = [Index("startDate"), Index("startMillis"), Index("feed")],
)
data class Event(
    @PrimaryKey val uid: String,
    val serverId: Long,
    val feed: String,
    val filterId: Int,
    /** Wall-clock local date, "yyyy-MM-dd" -- what the list groups by. */
    val startDate: String,
    /** Epoch millis in the school's zone, for ordering and future reminders. */
    val startMillis: Long,
    val endMillis: Long,
    val startTime: String,
    val endTime: String,
    val title: String,
    val location: String?,
    val staff: String?,
    val tutor: String?,
    val notes: String?,
    val clashCount: Int,
    val isOnline: Boolean,
    val isAccompanied: Boolean,
    val syncedAtMillis: Long,
) {
    val feedType: Feed? get() = Feed.fromKey(feed)

    /** A zero-length entry is a marker, like "Term Starts", not a real slot. */
    val isInstant: Boolean get() = startMillis == endMillis
}

private val SERVER_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Maps a server record onto a stored row, or null if it is unusable.
 *
 * Returning null rather than throwing matters: one malformed record in a feed of a
 * hundred should cost that record, not the whole sync.
 */
fun EventDto.toEvent(feed: Feed, syncedAtMillis: Long): Event? {
    val id = id ?: return null
    val startRaw = start ?: return null
    val start = parseServerTime(startRaw) ?: return null
    // Some markers carry no end time; treat them as instants rather than dropping them.
    val end = end?.let { parseServerTime(it) } ?: start

    val zone = SCHOOL_ZONE
    return Event(
        uid = "${feed.key}:$id",
        serverId = id,
        feed = feed.key,
        filterId = eventFilterId ?: -1,
        startDate = start.toLocalDate().toString(),
        startMillis = start.atZone(zone).toInstant().toEpochMilli(),
        endMillis = end.atZone(zone).toInstant().toEpochMilli(),
        startTime = start.format(TIME_FORMAT),
        endTime = end.format(TIME_FORMAT),
        title = title?.trim().orEmpty().ifEmpty { "(untitled)" },
        location = eventLocation?.trim()?.ifEmpty { null },
        staff = eventStaff?.trim()?.ifEmpty { null },
        tutor = instTutorFullName?.trim()?.ifEmpty { null },
        notes = studentNotes?.trim()?.ifEmpty { null },
        clashCount = clashCounter ?: 0,
        isOnline = onlineLesson.equals("Y", ignoreCase = true),
        isAccompanied = accompLesson.equals("Y", ignoreCase = true),
        syncedAtMillis = syncedAtMillis,
    )
}

private fun parseServerTime(raw: String): LocalDateTime? = try {
    LocalDateTime.parse(raw.trim(), SERVER_FORMAT)
} catch (e: Exception) {
    // Fall back to a bare date, which some marker events use.
    try {
        LocalDate.parse(raw.trim()).atStartOfDay()
    } catch (e2: Exception) {
        null
    }
}

/**
 * One other student in a lesson, as returned by the participants endpoint.
 *
 * [eventDetails] is the server's own clash description and is usually null; when
 * present, the site marks that participant with a warning icon.
 */
@Serializable
data class ParticipantDto(
    @SerialName("Forename") val forename: String? = null,
    @SerialName("Surname") val surname: String? = null,
    @SerialName("EventDetails") val eventDetails: String? = null,
    @SerialName("EventStart") val eventStart: String? = null,
    @SerialName("EventFinish") val eventFinish: String? = null,
) {
    val fullName: String
        get() = listOfNotNull(forename?.trim(), surname?.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { "Unnamed" }
}

/** The participants endpoint wraps its array in an object, unlike the calendar feeds. */
@Serializable
data class ParticipantsResponse(
    val data: List<ParticipantDto> = emptyList(),
)
