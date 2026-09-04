package com.streamer.timetable.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamer.timetable.sync.DAYS_AHEAD_CHOICES
import com.streamer.timetable.sync.DAYS_BEHIND_CHOICES
import com.streamer.timetable.sync.SYNC_INTERVAL_CHOICES
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Pages on the live site worth reaching quickly, taken from its own nav bar. */
enum class SiteLink(val label: String, val url: String) {
    TIMETABLE("Timetable", "https://stream.chethams.com/student/"),
    PREP("Prep", "https://stream.chethams.com/student/prep-events.html"),
    MUSIC_JOURNAL("Music Journal", "https://stream.chethams.com/student/practice-instruments.html"),
    ENSEMBLES("Ensembles", "https://stream.chethams.com/student/ensembles.html"),
    TUTORS("Tutors", "https://stream.chethams.com/student/tutors.html"),
}

/**
 * The hidden sidebar: links to the live site, display options, debug tools, sign out.
 *
 * Sync is deliberately absent -- it lives in the toolbar, where it is one tap away
 * rather than behind a drawer.
 */
@Composable
fun AppDrawer(
    lastSyncedLabel: String,
    storedEventCount: Int,
    hideMusBlock: Boolean,
    musBlockCount: Int,
    animateWeekScroll: Boolean,
    weekStartDay: DayOfWeek,
    daysBehind: Int,
    daysAhead: Int,
    syncIntervalHours: Int,
    debugDate: LocalDate?,
    realToday: LocalDate,
    onOpenLink: (SiteLink) -> Unit,
    onToggleMusBlock: (Boolean) -> Unit,
    onToggleAnimateWeekScroll: (Boolean) -> Unit,
    onSetWeekStartDay: (DayOfWeek) -> Unit,
    onSetWindow: (Int, Int) -> Unit,
    onSetSyncInterval: (Int) -> Unit,
    onOpenNotifications: () -> Unit,
    onSetDebugDate: (LocalDate?) -> Unit,
    onClearStored: () -> Unit,
    onAddSampleEvents: () -> Unit,
    onRefreshWidget: () -> Unit,
    crashLogCount: Int,
    onShareCrash: () -> Unit,
    onClearCrashes: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAlarmSettings: () -> Unit,
    diagnostics: com.streamer.timetable.notify.AlertScheduler.Diagnostics?,
    syncStats: com.streamer.timetable.sync.SyncStats,
    onResetSyncStats: () -> Unit,
    onRefreshDiagnostics: () -> Unit,
    onTestNotification: () -> Unit,
    onScheduleTestAlert: () -> Unit,
    onSignOut: () -> Unit,
) {
    var optionsOpen by remember { mutableStateOf(false) }
    var debugOpen by remember { mutableStateOf(false) }

    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "Streamer",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            )
            Text(
                lastSyncedLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp),
            )

            Spacer(Modifier.size(12.dp))
            HorizontalDivider()
            Spacer(Modifier.size(8.dp))

            SectionLabel("Open on the site")
            SiteLink.entries.forEach { link ->
                NavigationDrawerItem(
                    label = { Text(link.label) },
                    selected = false,
                    icon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                    onClick = { onOpenLink(link) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            Spacer(Modifier.size(8.dp))
            HorizontalDivider()
            Spacer(Modifier.size(8.dp))

            NavigationDrawerItem(
                label = { Text("Notifications") },
                selected = false,
                icon = { Icon(Icons.Default.NotificationsActive, contentDescription = null) },
                onClick = onOpenNotifications,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            Spacer(Modifier.size(8.dp))
            HorizontalDivider()
            Spacer(Modifier.size(8.dp))

            ExpanderItem(
                label = "Options",
                icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                expanded = optionsOpen,
                onToggle = { optionsOpen = !optionsOpen },
            )

            AnimatedVisibility(visible = optionsOpen) {
                Column {
                    SwitchRow(
                        label = "Hide Mus Block",
                        // Shows the count so it is never silently removing things.
                        supporting = if (musBlockCount > 0) {
                            "$musBlockCount stored - excluded from clashes when hidden"
                        } else {
                            "None currently stored"
                        },
                        checked = hideMusBlock,
                        onCheckedChange = onToggleMusBlock,
                    )
                    SwitchRow(
                        label = "Animate to today",
                        supporting = "Opens on Monday and scrolls down to today, " +
                            "so you see the week before it settles",
                        checked = animateWeekScroll,
                        onCheckedChange = onToggleAnimateWeekScroll,
                    )

                    Column(modifier = Modifier.padding(horizontal = 28.dp)) {
                        Spacer(Modifier.size(8.dp))
                        Text("Week starts on", style = MaterialTheme.typography.labelLarge)
                        Text(
                            // The Monday heading is a separate thing and is not
                            // affected, so say so rather than letting it look broken.
                            "Sets the seven days the This week tab covers. The bold " +
                                "Monday heading is unaffected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            DayOfWeek.entries.forEach { day ->
                                FilterChip(
                                    selected = day == weekStartDay,
                                    onClick = { onSetWeekStartDay(day) },
                                    label = {
                                        Text(
                                            day.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    },
                                )
                            }
                        }

                        Spacer(Modifier.size(12.dp))
                        Text("Offline range", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "How much timetable is kept on the device. Anything " +
                                "outside this is removed on the next sync.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        ChoiceRow(
                            label = "Days back",
                            options = DAYS_BEHIND_CHOICES,
                            selected = daysBehind,
                            format = { "$it d" },
                            onSelect = { onSetWindow(it, daysAhead) },
                        )
                        ChoiceRow(
                            label = "Days ahead",
                            options = DAYS_AHEAD_CHOICES,
                            selected = daysAhead,
                            format = { "$it d" },
                            onSelect = { onSetWindow(daysBehind, it) },
                        )

                        Spacer(Modifier.size(12.dp))
                        Text("Background sync", style = MaterialTheme.typography.labelLarge)
                        Text(
                            // The far future is the least reliable part of the feed,
                            // so how often this runs is what keeps it honest.
                            "The app also syncs every time you open it, so Off still " +
                                "refreshes on launch.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ChoiceRow(
                            label = "Every",
                            options = SYNC_INTERVAL_CHOICES,
                            selected = syncIntervalHours,
                            format = { if (it == 0) "Off" else "$it h" },
                            onSelect = onSetSyncInterval,
                        )
                    }
                }
            }

            Spacer(Modifier.size(8.dp))
            HorizontalDivider()
            Spacer(Modifier.size(8.dp))

            ExpanderItem(
                label = "Debug",
                icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                expanded = debugOpen,
                onToggle = { debugOpen = !debugOpen },
            )

            AnimatedVisibility(visible = debugOpen) {
                Column(modifier = Modifier.padding(horizontal = 28.dp)) {
                    Text(
                        "Pretend today is",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        // The whole point of the override: term has not started yet,
                        // so there is otherwise no way to see a populated week.
                        "Moves the highlighted week and what a sync fetches.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        text = debugDate?.toString() ?: "$realToday (real date)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            onSetDebugDate((debugDate ?: realToday).minusWeeks(1))
                        }) { Text("-1 wk") }
                        TextButton(onClick = {
                            onSetDebugDate((debugDate ?: realToday).plusWeeks(1))
                        }) { Text("+1 wk") }
                        TextButton(onClick = {
                            onSetDebugDate((debugDate ?: realToday).minusDays(1))
                        }) { Text("-1 day") }
                        TextButton(onClick = {
                            onSetDebugDate((debugDate ?: realToday).plusDays(1))
                        }) { Text("+1 day") }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onSetDebugDate(null) }) { Text("Reset") }
                    }

                    Spacer(Modifier.size(8.dp))
                    Text(
                        "$storedEventCount events stored offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.size(12.dp))
                    Text("Syncs", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = buildString {
                            appendLine("Attempts: ${syncStats.attempts}")
                            appendLine("Succeeded: ${syncStats.successes}")
                            appendLine("Failed: ${syncStats.failures}")
                            appendLine("Rejected: ${syncStats.authFailures}")
                            appendLine("Last: ${syncStats.lastResult}")
                            append(
                                if (syncStats.lastAttemptMillis == 0L) {
                                    "Last tried: never"
                                } else {
                                    val at = java.time.Instant
                                        .ofEpochMilli(syncStats.lastAttemptMillis)
                                        .atZone(java.time.ZoneId.systemDefault())
                                    "Last tried: ${at.toLocalDate()} ${at.toLocalTime()
                                        .withNano(0)}"
                                }
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onResetSyncStats) { Text("Reset counts") }
                    }

                    Spacer(Modifier.size(12.dp))
                    Text("Notifications", style = MaterialTheme.typography.labelLarge)

                    // A reminder that never arrives gives no clue why. These three
                    // lines separate the causes: blocked outright, downgraded to
                    // inexact, or simply not due yet.
                    if (diagnostics == null) {
                        Text(
                            "Tap Check to inspect",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = buildString {
                                appendLine(
                                    if (diagnostics.notificationsAllowed) {
                                        "Permission: granted"
                                    } else {
                                        "Permission: BLOCKED"
                                    }
                                )
                                appendLine(
                                    if (diagnostics.exactAlarmsAllowed) {
                                        "Exact alarms: allowed"
                                    } else {
                                        "Exact alarms: DENIED - reminders may drift"
                                    }
                                )
                                appendLine("Rules: ${diagnostics.ruleCount}")
                                appendLine("Scheduled: ${diagnostics.scheduledCount}")
                                append(
                                    diagnostics.nextAlertAtMillis?.let { millis ->
                                        val at = java.time.Instant.ofEpochMilli(millis)
                                            .atZone(java.time.ZoneId.systemDefault())
                                        "Next: ${diagnostics.nextAlertLabel} on " +
                                            "${at.toLocalDate()} at ${at.toLocalTime()}"
                                    } ?: "Next: none pending"
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Background work is the first thing an aggressive battery
                    // policy kills, and the app cannot grant itself an exemption --
                    // it can only take you to the screen where you can.
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onOpenBatterySettings) { Text("Battery") }
                        TextButton(onClick = onOpenAlarmSettings) { Text("Alarms") }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onRefreshDiagnostics) { Text("Check") }
                        TextButton(onClick = onTestNotification) { Text("Test now") }
                        TextButton(onClick = onScheduleTestAlert) { Text("In 1 min") }
                    }

                    Spacer(Modifier.size(12.dp))
                    Text("Sample data", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Adds prep and clashing lessons around today, since the prep " +
                            "feed returns nothing. Removed by the next sync.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onAddSampleEvents) { Text("Add sample events") }

                    Spacer(Modifier.size(12.dp))
                    Text("Widget", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Reports whether the update reached a placed widget.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRefreshWidget) { Text("Refresh widget") }

                    Spacer(Modifier.size(12.dp))
                    Text("Crash logs", style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (crashLogCount == 0) {
                            "None recorded"
                        } else {
                            "$crashLogCount recorded - share the latest so it can be fixed"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = onShareCrash,
                            enabled = crashLogCount > 0,
                        ) { Text("Share latest") }
                        TextButton(
                            onClick = onClearCrashes,
                            enabled = crashLogCount > 0,
                        ) { Text("Clear") }
                    }
                }
            }

            AnimatedVisibility(visible = debugOpen) {
                NavigationDrawerItem(
                    label = { Text("Clear stored timetable") },
                    selected = false,
                    icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                    onClick = onClearStored,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            Spacer(Modifier.size(8.dp))
            HorizontalDivider()
            Spacer(Modifier.size(8.dp))

            NavigationDrawerItem(
                label = { Text("Sign out") },
                selected = false,
                icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                onClick = onSignOut,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            Text(
                "Signing out clears your saved password but keeps your filter choices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 8.dp),
            )
        }
    }
}

/** A labelled row of mutually exclusive values. */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<Int>,
    selected: Int,
    format: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = {
                        Text(format(option), style = MaterialTheme.typography.labelMedium)
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
    )
}

@Composable
private fun ExpanderItem(
    label: String,
    icon: @Composable () -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = false,
        icon = icon,
        badge = {
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
        },
        onClick = onToggle,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
private fun SwitchRow(
    label: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // weight(1f) is what keeps the switches in a column. Without it each text
        // block sizes to its own content, so every switch lands at a different x
        // depending on how long its label happens to be.
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
