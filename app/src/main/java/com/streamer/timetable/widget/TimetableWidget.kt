package com.streamer.timetable.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.ColorFilter
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.streamer.timetable.MainActivity
import com.streamer.timetable.R
import com.streamer.timetable.data.AppDatabase
import com.streamer.timetable.data.Event
import com.streamer.timetable.data.SCHOOL_ZONE
import com.streamer.timetable.ui.DayRow
import com.streamer.timetable.ui.effectiveNowMillis
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val HEADER_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMM")

/** The same red the app uses for genuine timetable conflicts. */
private val CLASH_COLOUR = Color(0xFFD32F2F)

/**
 * Rules between rows.
 *
 * A translucent mid grey rather than a theme role: Glance has no `outlineVariant`,
 * and plain `outline` is meant for borders and comes out heavier than these want to
 * be. Blending with whatever sits behind keeps it light in both themes.
 */
private val DIVIDER_COLOUR = Color(0x33808080)

/**
 * Home-screen widget listing what is left of today.
 *
 * Reads Room directly rather than going through a view model: `provideGlance` runs in
 * a broadcast receiver with no Activity alive, so there is no lifecycle to hang one
 * off. It is a suspend context, which makes the one-shot DAO read straightforward.
 */
class TimetableWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val database = AppDatabase.get(context)
        val prefs = context.getSharedPreferences("streamer_sync", Context.MODE_PRIVATE)

        // Honour the debug date so the widget can be checked out of term, and the
        // Mus Block preference so it agrees with the app rather than contradicting it.
        val debugDate = prefs.getString("debug_date_override", null)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val today = debugDate ?: LocalDate.now(SCHOOL_ZONE)

        // Time-of-day on whichever date the widget is showing. Shared with the app so
        // the two cannot disagree about what counts as already finished.
        val nowMillis = effectiveNowMillis(today)

        val syncing = WidgetSync.isRunning(context)
        val lastResult = WidgetSync.recentResult(context)

        val content = buildWidgetContent(
            events = database.eventDao().allOnce(),
            today = today,
            nowMillis = nowMillis,
            hideMusBlock = prefs.getBoolean("hide_mus_block", true),
            lastSyncedMillis = prefs.getLong("last_sync_millis", 0L),
        )

        // While a sync is claimed to be running, book a re-check so the label cannot
        // outlive the work that set it.
        if (syncing) {
            WidgetUpdater.scheduleSyncRecheck(context, System.currentTimeMillis() + 20_000)
        }

        // Book a refresh for the moment the next lesson ends, so it disappears then
        // rather than lingering until the following sync hours later.
        content.lessons
            .filterNot { it.isInstant }
            .minOfOrNull { it.endMillis }
            ?.takeIf { it > nowMillis }
            ?.let { WidgetUpdater.scheduleRefreshAt(context, it + 1_000) }

        provideContent { WidgetBody(content, syncing, lastResult) }
    }
}

@Composable
private fun WidgetBody(content: WidgetContent, syncing: Boolean, lastResult: String?) {
    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = content.date.format(HEADER_FORMAT),
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.onSurface,
                    ),
                    modifier = GlanceModifier
                        .defaultWeight()
                        .padding(vertical = 12.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                )

                Text(
                    // While syncing the word replaces the staleness note, so the
                    // press has visible effect during a wait that may take seconds.
                    // Priority: in flight, then how the last attempt went, then how
                    // old the data is. A failure must not be hidden by the age note.
                    text = when {
                        syncing -> "syncing"
                        lastResult != null -> lastResult
                        else -> staleness(content.lastSyncedMillis).orEmpty()
                    },
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                )

                Spacer(GlanceModifier.width(4.dp))

                // The tap area is the box, not the glyph. A 20dp target -- which is
                // what this was -- is well under the 48dp minimum, and on a home
                // screen a near miss lands on nothing at all, since the header row
                // itself is no longer clickable. That is what made the button seem
                // to work only sometimes.
                Box(
                    modifier = GlanceModifier
                        .size(48.dp)
                        .clickable(actionRunCallback<RefreshAction>()),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_refresh),
                        contentDescription = "Refresh",
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                        modifier = GlanceModifier.size(20.dp),
                    )
                }
            }

            Spacer(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(DIVIDER_COLOUR)
            )
            Spacer(GlanceModifier.height(6.dp))

            if (content.isEmpty) {
                Text(
                    text = "Nothing left today",
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    // Every visible area opens the app, so a tap anywhere works
                    // rather than only landing on the header.
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .clickable(actionStartActivity<MainActivity>()),
                )
            } else {
                // A lazy list, so a widget too short for the day scrolls instead of
                // silently truncating. Backed by a RemoteViews collection, which is
                // the only scrollable construct a widget gets.
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    items(content.rows, itemId = { rowId(it) }) { row ->
                        when (row) {
                            // Unlabelled on the widget: at this size the rule alone
                            // reads as a break, and the words cost a whole line.
                            is DayRow.Interval -> IntervalRule()
                            is DayRow.Lesson -> LessonCard(row.event, row.clash.inClash)
                        }
                    }

                    // Only reachable if a day exceeds the memory cap, which scrolling
                    // does not remove -- so say so rather than truncating in silence.
                    if (content.hiddenCount > 0) {
                        item {
                            Text(
                                text = "+${content.hiddenCount} more in the app",
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    color = GlanceTheme.colors.onSurfaceVariant,
                                ),
                                modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable(actionStartActivity<MainActivity>()),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Stable ids for the lazy list; intervals have no event to key on. */
private fun rowId(row: DayRow): Long = when (row) {
    is DayRow.Lesson -> row.event.serverId
    is DayRow.Interval -> -row.label.hashCode().toLong()
}

/** The unlabelled break marker: a thin rule, inset so it reads as a pause. */
@Composable
private fun IntervalRule() {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 5.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        Spacer(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DIVIDER_COLOUR)
        )
    }
}

/**
 * One lesson, styled as a card to match the list in the app.
 *
 * Text runs a little larger than the app's: a widget is read at arm's length on a
 * cluttered home screen rather than held up close.
 */
@Composable
private fun LessonCard(event: Event, inClash: Boolean) {
    Box(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius(10.dp)
                .padding(horizontal = 8.dp, vertical = 7.dp)
                // Rows are tappable as well as scrollable: a RemoteViews collection
                // dispatches per-item clicks without swallowing the scroll gesture.
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Clash rail, to the left of the category colour exactly as in the app.
            // Unlike the app this cannot join across rows -- widget list items are
            // discrete views with no shared canvas -- so it reads per lesson.
            if (inClash) {
                Spacer(
                    modifier = GlanceModifier
                        .width(3.dp)
                        .height(30.dp)
                        .cornerRadius(2.dp)
                        .background(CLASH_COLOUR)
                )
                Spacer(GlanceModifier.width(4.dp))
            }

            // Category colour, matching the bars in the app's own rows.
            Spacer(
                modifier = GlanceModifier
                    .width(4.dp)
                    .height(30.dp)
                    .cornerRadius(2.dp)
                    .background(event.feedType?.let { Color(it.colour) } ?: Color.Gray)
            )
            Spacer(GlanceModifier.width(8.dp))

            Text(
                text = when {
                    event.feedType?.isPrep == true -> "due ${event.endTime}"
                    event.isInstant -> "all day"
                    else -> "${event.startTime}\n${event.endTime}"
                },
                // Stacked rather than "09:00 - 10:00" on one line: the widget is
                // narrow, and a single-line span squeezes the lesson name badly.
                maxLines = 2,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = GlanceTheme.colors.onSecondaryContainer,
                ),
            )
            Spacer(GlanceModifier.width(8.dp))

            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = event.title,
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = GlanceTheme.colors.onSecondaryContainer,
                    ),
                )

                // Room and teacher only. The class code that the feed puts in
                // StudentNotes ("12A/Ma4") is deliberately left out -- it is an
                // internal identifier, not something worth a line on a home screen.
                val detail = listOfNotNull(
                    event.location?.takeIf { it.isNotBlank() },
                    (event.tutor ?: event.staff)?.takeIf { it.isNotBlank() },
                ).joinToString(" - ")

                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        maxLines = 1,
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = GlanceTheme.colors.onSurfaceVariant,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * How stale the data is, or null when it is fresh.
 *
 * Shown because a widget is glanced at rather than opened, so there is no other cue
 * that syncing stopped days ago -- it would otherwise show a confidently wrong day.
 */
private fun staleness(lastSyncedMillis: Long): String? {
    if (lastSyncedMillis <= 0L) return "not synced"
    val hours = java.time.Duration
        .between(Instant.ofEpochMilli(lastSyncedMillis), Instant.now())
        .toHours()
    return when {
        hours < 1 -> null
        hours < 24 -> "${hours}h old"
        else -> "${hours / 24}d old"
    }
}
