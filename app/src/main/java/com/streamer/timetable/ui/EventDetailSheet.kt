package com.streamer.timetable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamer.timetable.data.Event
import com.streamer.timetable.data.ParticipantDto
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val FULL_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")

/**
 * Everything known about one event.
 *
 * Most of it comes from the stored record and is available offline. The participant
 * list does not: it is a separate per-event request, so it loads when the sheet
 * opens and degrades to a note when there is no connection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailSheet(
    event: Event,
    clash: ClashState,
    participants: List<ParticipantDto>,
    participantsLoading: Boolean,
    participantsUnavailable: Boolean,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accent = event.feedType?.colour?.let { Color(it) } ?: MaterialTheme.colorScheme.outline

    // A full sheet should stop below the top of the screen rather than swallowing it,
    // leaving the page behind visible as a cue that this is a layer you can dismiss.
    // Short sheets are unaffected: this is a maximum, not a fixed height.
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                // The sheet can outgrow the screen: a lesson with many participants,
                // or long notes, needs to scroll rather than be clipped.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = event.feedType?.label ?: "Event",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.size(4.dp))

            Text(
                text = event.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            if (clash.inClash) {
                Text(
                    text = if (clash.clashesWith.size > 1) "Clashes with other events"
                    else "Clashes with another event",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFD32F2F),
                )
                // Naming the conflict is the point: "clashes" alone leaves you
                // scrolling the list to work out what it collided with.
                clash.clashesWith.forEach { peer ->
                    Text(
                        text = "${peer.title}  ${peer.startTime} - ${peer.endTime}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.size(12.dp))
            HorizontalDivider()
            Spacer(Modifier.size(12.dp))

            DetailRow("Date", LocalDate.parse(event.startDate).format(FULL_DATE))
            DetailRow(
                "Time",
                if (event.isInstant) "All day" else "${event.startTime} - ${event.endTime}",
            )
            if (!event.isInstant) {
                DetailRow("Length", formatDuration(event.endMillis - event.startMillis))
            }
            DetailRow("Location", event.location)
            DetailRow("Tutor", event.tutor)
            DetailRow("Staff", event.staff)
            DetailRow("Notes", event.notes)

            if (event.isOnline) DetailRow("Online", "Yes")
            if (event.isAccompanied) DetailRow("Accompanist", "Yes")

            Spacer(Modifier.size(12.dp))
            HorizontalDivider()
            Spacer(Modifier.size(12.dp))

            Text(
                "Other participants",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(4.dp))

            when {
                participantsLoading -> Text(
                    "Loading...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Participants are the one thing here that needs the network, so say
                // so plainly rather than showing an empty list that looks like a fact.
                participantsUnavailable -> Text(
                    "Not available offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                participants.isEmpty() -> Text(
                    "No other participants listed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> participants.forEach { ParticipantRow(it) }
            }
        }
    }
}

/**
 * One other student, with the server's own clash note when it supplies one.
 *
 * `EventDetails` is only populated when that participant has a conflicting
 * commitment -- the site flags exactly this with a warning icon.
 */
@Composable
private fun ParticipantRow(participant: ParticipantDto) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = participant.fullName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f, fill = false),
            )
            val window = listOfNotNull(participant.eventStart, participant.eventFinish)
                .map { it.takeLast(8).take(5) }
            if (window.size == 2) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = window.joinToString(" - "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        participant.eventDetails?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD32F2F),
            )
        }
    }
}

/** Renders nothing when the field is absent, rather than showing an empty row. */
@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatDuration(millis: Long): String {
    val minutes = millis / 60_000
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours == 0L -> "$remainder min"
        remainder == 0L -> if (hours == 1L) "1 hour" else "$hours hours"
        else -> "${hours}h ${remainder}m"
    }
}
