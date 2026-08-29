package com.streamer.timetable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.streamer.timetable.data.Event
import com.streamer.timetable.data.Feed
import com.streamer.timetable.data.FixedAnchor
import com.streamer.timetable.data.NotificationRule
import com.streamer.timetable.data.TimingMode
import com.streamer.timetable.data.matches

/** Lead times offered as one-tap choices; anything else goes in the custom field. */
private val LEAD_PRESETS = listOf(0, 5, 10, 15, 30, 60, 120)

/**
 * Creates or edits one notification rule.
 *
 * The flow starts from a real lesson: picking one fills in its name, room, teacher
 * and category, and you then delete whichever criteria are too narrow. Choosing from
 * what exists and removing is far easier than typing "12A/Ma4" into an empty box
 * while guessing what the feed calls things.
 *
 * A live match count sits at the bottom, so an over-narrow rule is visible before it
 * is saved rather than by its silence a week later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorScreen(
    initial: NotificationRule,
    events: List<Event>,
    onSave: (NotificationRule) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var rule by remember { mutableStateOf(initial) }
    var pickerOpen by remember { mutableStateOf(false) }
    var pickedLabel by remember { mutableStateOf("") }

    // Distinct lessons, grouped by category so the list reads in sections rather
    // than as one long alphabetical run of unrelated things.
    val grouped = remember(events) {
        events
            .distinctBy { it.feed + "|" + it.title.lowercase() }
            .sortedBy { it.title }
            .groupBy { it.feedType }
            .toSortedMap(compareBy { it?.ordinal ?: Int.MAX_VALUE })
    }

    val matchCount = remember(rule, events) { events.count { rule.matches(it) } }
    val useCustomLead = rule.timingMode == TimingMode.BEFORE_START &&
        rule.minutesBefore !in LEAD_PRESETS

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Start from a lesson", style = MaterialTheme.typography.titleSmall)
        Text(
            "Fills in that lesson's name, room, teacher and category. Clear whichever " +
                "you do not want to match on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ExposedDropdownMenuBox(
            expanded = pickerOpen,
            onExpandedChange = { pickerOpen = !pickerOpen },
        ) {
            OutlinedTextField(
                value = pickedLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Pick a lesson") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(pickerOpen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )

            ExposedDropdownMenu(
                expanded = pickerOpen,
                onDismissRequest = { pickerOpen = false },
            ) {
                grouped.forEach { (feed, lessons) ->
                    // Category heading, deliberately not selectable.
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(feed?.let { Color(it.colour) } ?: Color.Gray)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            feed?.label ?: "Other",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    lessons.forEach { event ->
                        DropdownMenuItem(
                            leadingIcon = {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(
                                            event.feedType?.let { Color(it.colour) }
                                                ?: Color.Gray
                                        )
                                )
                            },
                            text = {
                                Column {
                                    Text(event.title)
                                    val detail = listOfNotNull(
                                        event.location,
                                        event.tutor ?: event.staff,
                                    ).joinToString(" - ")
                                    if (detail.isNotBlank()) {
                                        Text(
                                            detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                // Fill everything the lesson knows, so narrowing is a
                                // matter of deleting rather than typing.
                                rule = rule.copy(
                                    label = rule.label.ifBlank { event.title },
                                    titleContains = event.title,
                                    locationContains = event.location,
                                    tutorContains = event.tutor ?: event.staff,
                                    feedKey = event.feed,
                                )
                                pickedLabel = event.title
                                pickerOpen = false
                            },
                        )
                    }

                    HorizontalDivider()
                }
            }
        }

        HorizontalDivider()

        OutlinedTextField(
            value = rule.label,
            onValueChange = { rule = rule.copy(label = it) },
            label = { Text("Rule name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Match lessons where", style = MaterialTheme.typography.titleSmall)
        Text(
            "Every field you fill in must match. Leave a field empty to ignore it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = rule.titleContains.orEmpty(),
            onValueChange = { rule = rule.copy(titleContains = it.ifBlank { null }) },
            label = { Text("Name contains") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = rule.locationContains.orEmpty(),
            onValueChange = { rule = rule.copy(locationContains = it.ifBlank { null }) },
            label = { Text("Room contains") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = rule.tutorContains.orEmpty(),
            onValueChange = { rule = rule.copy(tutorContains = it.ifBlank { null }) },
            label = { Text("Teacher contains") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        CategoryPicker(
            selected = rule.feed,
            onSelect = { rule = rule.copy(feedKey = it?.key) },
        )

        HorizontalDivider()

        Text("When to notify", style = MaterialTheme.typography.titleSmall)

        TimingOption(
            selected = rule.timingMode == TimingMode.BEFORE_START,
            label = "Before the lesson starts",
            onSelect = { rule = rule.copy(timingMode = TimingMode.BEFORE_START) },
        )

        if (rule.timingMode == TimingMode.BEFORE_START) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LEAD_PRESETS.forEach { minutes ->
                    FilterChip(
                        selected = !useCustomLead && rule.minutesBefore == minutes,
                        onClick = { rule = rule.copy(minutesBefore = minutes) },
                        label = { Text(leadLabel(minutes), style = MaterialTheme.typography.labelMedium) },
                    )
                }
                FilterChip(
                    selected = useCustomLead,
                    // Seeded with a value outside the presets so the custom field
                    // appears already selected rather than snapping to a preset.
                    onClick = { rule = rule.copy(minutesBefore = 45) },
                    label = { Text("Custom", style = MaterialTheme.typography.labelMedium) },
                )
            }

            if (useCustomLead) {
                OutlinedTextField(
                    value = rule.minutesBefore.toString(),
                    onValueChange = { text ->
                        val minutes = text.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
                        rule = rule.copy(minutesBefore = minutes)
                    },
                    label = { Text("Minutes before") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        TimingOption(
            selected = rule.timingMode == TimingMode.FIXED_TIME,
            label = "At a set time",
            onSelect = { rule = rule.copy(timingMode = TimingMode.FIXED_TIME) },
        )

        if (rule.timingMode == TimingMode.FIXED_TIME) {
            // Two fields rather than one "HH:mm" box: the separator in a single box
            // can be deleted with no way to type it back, leaving an unparseable
            // value that silently stops the rule firing.
            ClockFields(
                value = rule.fixedTime,
                onChange = { rule = rule.copy(fixedTime = it) },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FixedAnchor.entries.forEach { anchor ->
                    FilterChip(
                        selected = rule.fixedAnchor == anchor,
                        onClick = { rule = rule.copy(fixedAnchor = anchor) },
                        label = { Text(anchorLabel(anchor), style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }

        HorizontalDivider()

        MatchPreview(rule = rule, events = events, matchCount = matchCount)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(
                onClick = { onSave(rule) },
                enabled = rule.isComplete,
                modifier = Modifier.weight(1f),
            ) { Text("Save") }
        }

        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun CategoryPicker(selected: Feed?, onSelect: (Feed?) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Column {
        Text("Category", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.size(4.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(selected?.let { Color(it.colour) } ?: Color.Transparent)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                selected?.label ?: "Any category",
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        if (open) {
            Column {
                DropdownRow("Any category", null) { onSelect(null); open = false }
                Feed.entries.forEach { feed ->
                    DropdownRow(feed.label, feed) { onSelect(feed); open = false }
                }
            }
        }
    }
}

@Composable
private fun DropdownRow(label: String, feed: Feed?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(feed?.let { Color(it.colour) } ?: Color.Gray.copy(alpha = 0.3f))
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Hours and minutes as separate numeric fields, recombined into "HH:mm".
 *
 * The fields hold their own text so a box can be *empty* while being edited. Deriving
 * them straight from the stored value meant clearing one immediately refilled it with
 * "00", which read as the app fighting the keystroke.
 *
 * The stored value is only rewritten once both boxes parse, so a half-finished edit
 * cannot leave an unusable time behind; until then the rule keeps its previous time,
 * and the hint below says which one it will use.
 */
@Composable
private fun ClockFields(value: String, onChange: (String) -> Unit) {
    var hourText by remember { mutableStateOf(value.substringBefore(':', "")) }
    var minuteText by remember { mutableStateOf(value.substringAfter(':', "")) }

    fun emitIfComplete() {
        val hour = hourText.toIntOrNull()
        val minute = minuteText.toIntOrNull()
        if (hour != null && minute != null) {
            onChange("%02d:%02d".format(hour, minute))
        }
    }

    val incomplete = hourText.toIntOrNull() == null || minuteText.toIntOrNull() == null

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = hourText,
                onValueChange = { text ->
                    // Clamp digits as they are typed, but never substitute a value
                    // for an empty box -- emptiness is a legitimate editing state.
                    val digits = text.filter { it.isDigit() }.take(2)
                    hourText = digits.toIntOrNull()?.coerceIn(0, 23)?.toString() ?: digits
                    emitIfComplete()
                },
                label = { Text("Hour") },
                isError = hourText.isNotEmpty() && hourText.toIntOrNull() == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(110.dp),
            )
            OutlinedTextField(
                value = minuteText,
                onValueChange = { text ->
                    val digits = text.filter { it.isDigit() }.take(2)
                    minuteText = digits.toIntOrNull()?.coerceIn(0, 59)?.toString() ?: digits
                    emitIfComplete()
                },
                label = { Text("Minute") },
                isError = minuteText.isNotEmpty() && minuteText.toIntOrNull() == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(110.dp),
            )
        }

        if (incomplete) {
            Text(
                "Incomplete - will use $value",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun MatchPreview(rule: NotificationRule, events: List<Event>, matchCount: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = when {
                    !rule.isComplete -> "Set at least one criterion"
                    matchCount == 0 -> "Matches no stored lessons"
                    matchCount == 1 -> "Matches 1 stored lesson"
                    else -> "Matches $matchCount stored lessons"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (rule.isComplete && matchCount > 0) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            if (rule.isComplete && matchCount > 0) {
                Spacer(Modifier.size(4.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 140.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    events.filter { rule.matches(it) }.take(8).forEach {
                        Text(
                            "${it.startDate}  ${it.startTime}  ${it.title}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimingOption(selected: Boolean, label: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun leadLabel(minutes: Int): String = when {
    minutes == 0 -> "At start"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "$minutes min"
}

private fun anchorLabel(anchor: FixedAnchor): String = when (anchor) {
    FixedAnchor.EVENT_DAY -> "On the day"
    FixedAnchor.DAY_BEFORE -> "Day before"
    FixedAnchor.WEEK_START -> "Start of week"
}
