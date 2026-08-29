package com.streamer.timetable.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamer.timetable.data.Event
import com.streamer.timetable.data.Feed
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM")

/** Red, for genuine timetable conflicts. Kept distinct from any category colour. */
private val CLASH_COLOUR = Color(0xFFD32F2F)

/**
 * The day-heading ramp, in both themes.
 *
 * Fixed blues rather than theme roles, so the headings step in a single deliberate
 * ramp from most prominent (today) to least (an ordinary day). Because they do not
 * follow the theme automatically, each set carries its own foregrounds -- a
 * theme-derived text colour would disappear against one of the two.
 *
 * The light ramp runs dark to light; the dark ramp inverts that, so in both cases
 * today is the strongest contrast against the page behind it.
 */
private data class DayPalette(
    val today: Color,
    val weekStart: Color,
    /** A week marker outside the current week: still a separator, but stepped back. */
    val weekStartOther: Color,
    val onWeekStartOther: Color,
    val currentWeek: Color,
    val ordinary: Color,
    val onProminent: Color,
    val onSubtle: Color,
    val filterFill: Color,
    val filterOutline: Color,
    val filterLabel: Color,
)

private val LIGHT_DAYS = DayPalette(
    today = Color(0xFF10344E),
    weekStart = Color(0xFF1B63B0),
    weekStartOther = Color(0xFF7FAFDE),
    onWeekStartOther = Color(0xFF10344E),
    currentWeek = Color(0xFFC9E0F6),
    ordinary = Color(0xFFECF4FC),
    onProminent = Color.White,
    onSubtle = Color(0xFF10344E),
    filterFill = Color(0xFFD7E9FA),
    filterOutline = Color(0xFF1B63B0),
    filterLabel = Color(0xFF10344E),
)

private val DARK_DAYS = DayPalette(
    // Inverted emphasis: on a dark page the brighter blue is the one that stands out,
    // so today takes the brightest step and the ramp descends from there.
    today = Color(0xFF2E86DE),
    weekStart = Color(0xFF1B63B0),
    // Deliberately well clear of `ordinary` and `currentWeek`: at the previous value
    // this band was near-indistinguishable from the days beneath it.
    weekStartOther = Color(0xFF245A8A),
    onWeekStartOther = Color.White,
    currentWeek = Color(0xFF16324A),
    ordinary = Color(0xFF0F2233),
    onProminent = Color.White,
    onSubtle = Color(0xFFC9E0F6),
    filterFill = Color(0xFF14324B),
    filterOutline = Color(0xFF3D8FDB),
    filterLabel = Color(0xFFC9E0F6),
)

@Composable
private fun dayPalette(): DayPalette = if (isSystemInDarkTheme()) DARK_DAYS else LIGHT_DAYS

/**
 * The timetable as a scrolling list of days.
 *
 * A list rather than a week grid, matching what the site itself falls back to on
 * narrow screens. Days in the current week are tinted so the present stands out
 * from surrounding weeks without needing a separate screen.
 */
@Composable
fun TimetableScreen(
    events: List<Event>,
    enabledFeeds: Set<Feed>,
    hideMusBlock: Boolean,
    today: LocalDate,
    tab: TimetableTab,
    animateFromWeekStart: Boolean,
    scrollToTodayRequests: Int,
    onToggleFeed: (Feed) -> Unit,
    onEventClick: (Event, ClashState) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Derived here rather than in the view model so a tab change produces its new
    // sections in the same composition, never a frame of the previous tab's list.
    // Recomputed whenever the inputs change rather than on a timer: the clock only
    // matters for Upcoming, and re-reading it on tab switch and after a sync is
    // frequent enough without keeping a ticker alive behind the list.
    val nowMillis = remember(events, tab, today) { effectiveNowMillis(today) }

    val sections = remember(events, tab, enabledFeeds, hideMusBlock, today, nowMillis) {
        buildSections(events, tab, enabledFeeds, hideMusBlock, today, nowMillis)
    }

    // Scoped to the tab, from the same filters the list uses, so the number on a chip
    // is exactly what that chip contributes here.
    val feedCounts = remember(events, tab, hideMusBlock, today, nowMillis) {
        countVisibleByFeed(events, tab, hideMusBlock, today, nowMillis)
    }

    val todaySection = indexOfFirstDayFrom(sections, today)
    val weekSection = indexOfFirstDayFrom(sections, weekStart(today))
    val willGlide = animateFromWeekStart && weekSection in 0 until todaySection

    // Seeded at its resting place for this tab, so the first frame after a tab
    // change is already in position. Previously the list drew at the old scroll
    // offset and was corrected a frame later, which read as a flash.
    val listState = remember(tab) {
        val seed = when {
            willGlide -> weekSection
            todaySection >= 0 -> todaySection
            else -> -1
        }
        LazyListState(if (seed >= 0) flatIndexOfSection(sections, seed) else 0)
    }

    // Open on today in every tab, falling through to the next day that has events
    // when today is empty. Earlier days stay reachable by scrolling up.
    //
    // The section index must be converted to a flat list index first: each day emits
    // a header plus one item per row, so the two numbering schemes diverge as soon as
    // there is more than one day on screen.
    //
    // Keyed on the tab as well, so switching tabs re-anchors on today, and on `today`
    // so the debug date changer takes effect immediately.
    LaunchedEffect(tab, today, sections.size, animateFromWeekStart) {
        if (todaySection < 0) return@LaunchedEffect
        val todayItem = flatIndexOfSection(sections, todaySection)

        // Optionally land on the week's Monday and glide down to today, so the days
        // already gone past register before the list settles. Skipped when there is
        // nothing above today to scroll through, since animating zero distance just
        // delays the content.
        // Wait until the list has actually measured something before animating.
        // On a cold start the effect fires while the first frames are still being
        // composed, so the glide competes with layout work and its distance estimate
        // is drawn from almost no measured rows -- both of which read as stutter.
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }

        if (willGlide) {
            listState.scrollToItem(flatIndexOfSection(sections, weekSection))
            listState.timedScrollToItem(
                target = todayItem,
                durationMillis = WEEK_SCROLL_DURATION_MS,
            )
        } else {
            listState.scrollToItem(todayItem)
        }
    }

    // Tapping the tab you are already on glides back to today. Separate from the
    // effect above so it fires on a re-tap, which changes no other state, and so it
    // starts from the current position rather than jumping to Monday first.
    LaunchedEffect(scrollToTodayRequests) {
        if (scrollToTodayRequests == 0) return@LaunchedEffect
        if (todaySection < 0) return@LaunchedEffect
        listState.timedScrollToItem(
            target = flatIndexOfSection(sections, todaySection),
            durationMillis = WEEK_SCROLL_DURATION_MS,
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        FilterRow(
            enabledFeeds = enabledFeeds,
            feedCounts = feedCounts,
            onToggleFeed = onToggleFeed,
        )
        HorizontalDivider()

        if (sections.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                sections.forEach { section ->
                    val current = isCurrentWeek(section.date, today)

                    item(key = "header-${section.date}") {
                        DayHeader(
                            date = section.date,
                            isToday = section.date == today,
                            isCurrentWeek = current,
                            isWeekStart = weekStart(section.date) == section.date,
                        )
                    }

                    section.rows.forEachIndexed { index, row ->
                        when (row) {
                            is DayRow.Interval -> item(key = "gap-${section.date}-$index") {
                                IntervalDivider(row.label)
                            }

                            is DayRow.Lesson -> item(key = row.event.uid) {
                                EventRow(
                                    event = row.event,
                                    clash = row.clash,
                                    isCurrentWeek = current,
                                    onClick = { onEventClick(row.event, row.clash) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How long the glide to today takes. */
private const val WEEK_SCROLL_DURATION_MS = 1500f

/**
 * Scrolls to [target] over a fixed duration, landing exactly on it.
 *
 * `animateScrollToItem` exposes no duration, so the scroll is driven frame by frame
 * in pixel space via `scrollBy`. Two earlier approaches failed in instructive ways:
 *
 *  - Repeatedly calling `scrollToItem` with a fractional index rubber-banded, because
 *    each frame re-derived an absolute position from a row-height average that itself
 *    shifted as the list moved.
 *  - Committing to one distance estimate up front left a jump at the end. The target
 *    is off screen and rows vary in height, so the estimate is always somewhat wrong,
 *    and the whole error had to be paid at once when the animation finished. Scrolling
 *    upward showed this worst: the target only becomes measurable near the end, giving
 *    the correction no time to be absorbed.
 *
 * So each frame measures the distance *still remaining* from wherever the list
 * actually is, then consumes the fraction of it that the easing curve calls for. Any
 * error in the estimate is absorbed continuously as a small change in speed rather
 * than saved up for a visible jump, and once the target is on screen its real offset
 * takes over, so the animation converges precisely.
 */
private suspend fun LazyListState.timedScrollToItem(target: Int, durationMillis: Float) {
    if (firstVisibleItemIndex == target && firstVisibleItemScrollOffset == 0) return

    scroll {
        val startedAt = withFrameMillis { it }
        var easedSoFar = 0f

        while (true) {
            val now = withFrameMillis { it }
            val elapsed = ((now - startedAt) / durationMillis).coerceIn(0f, 1f)
            val eased = FastOutSlowInEasing.transform(elapsed)

            val remaining = remainingDistanceTo(target)

            // Portion of what is left that this frame should cover for the curve to
            // reach the target exactly as time runs out.
            val share = if (eased >= 1f || easedSoFar >= 1f) {
                1f
            } else {
                ((eased - easedSoFar) / (1f - easedSoFar)).coerceIn(0f, 1f)
            }

            scrollBy(remaining * share)
            easedSoFar = eased

            if (elapsed >= 1f) break
        }
    }
}

/**
 * Pixels between the current position and [target] sitting at the top of the viewport.
 *
 * Measured from the layout when the target is on screen; estimated from the average
 * visible row height when it is not. The estimate only needs to be roughly right,
 * because it is recomputed every frame from the current position.
 */
private fun LazyListState.remainingDistanceTo(target: Int): Float {
    val visible = layoutInfo.visibleItemsInfo
    visible.firstOrNull { it.index == target }?.let { info ->
        return (info.offset - layoutInfo.viewportStartOffset).toFloat()
    }

    val averageRowHeight = visible.takeIf { it.isNotEmpty() }
        ?.map { it.size }
        ?.average()
        ?.toFloat()
        ?: return 0f

    return (target - firstVisibleItemIndex) * averageRowHeight - firstVisibleItemScrollOffset
}

@Composable
private fun FilterRow(
    enabledFeeds: Set<Feed>,
    feedCounts: Map<Feed, Int>,
    onToggleFeed: (Feed) -> Unit,
) {
    val palette = dayPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Declaration order, not count order. Sorting by count meant chips changed
        // places whenever the numbers did -- so the same category sat somewhere
        // different in each tab, and you had to re-find it every time. A fixed order
        // is worth more than keeping the busiest ones leftmost.
        Feed.entries.forEach { feed ->
            val count = feedCounts[feed] ?: 0
            val selected = feed in enabledFeeds && count > 0
            FilterChip(
                selected = selected,
                enabled = count > 0,
                onClick = { onToggleFeed(feed) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = palette.filterFill,
                    selectedLabelColor = palette.filterLabel,
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (selected) {
                        palette.filterOutline
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                ),
                label = {
                    Text(
                        if (count > 0) "${feed.label} ($count)" else feed.label,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                leadingIcon = {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(feed.colour))
                    )
                },
            )
        }
    }
}

@Composable
private fun DayHeader(
    date: LocalDate,
    isToday: Boolean,
    isCurrentWeek: Boolean,
    isWeekStart: Boolean,
) {
    // Monday doubles as the week separator, so it alone gets the darker, taller
    // band. Every other day keeps the ordinary header styling. Today still wins on
    // colour wherever it falls, so it is never lost to the week marker.
    val palette = dayPalette()
    val background = when {
        isToday -> palette.today
        isWeekStart && isCurrentWeek -> palette.weekStart
        isWeekStart -> palette.weekStartOther
        isCurrentWeek -> palette.currentWeek
        else -> palette.ordinary
    }
    val foreground = when {
        isToday -> palette.onProminent
        isWeekStart && isCurrentWeek -> palette.onProminent
        isWeekStart -> palette.onWeekStartOther
        else -> palette.onSubtle
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp)
            .background(background)
            .padding(horizontal = 16.dp, vertical = if (isWeekStart) 14.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = date.format(DAY_FORMAT),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
        )
        if (isToday) {
            Text("Today", style = MaterialTheme.typography.labelMedium, color = foreground)
        }
    }
}

/** A thin grey rule marking break, lunch, or the edges of the school day. */
@Composable
private fun IntervalDivider(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.width(24.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider()
    }
}

@Composable
private fun EventRow(
    event: Event,
    clash: ClashState,
    isCurrentWeek: Boolean,
    onClick: () -> Unit,
) {
    val accent = event.feedType?.colour?.let { Color(it) } ?: MaterialTheme.colorScheme.outline

    // The row is measured to its own minimum height so the clash rail can fill it
    // edge to edge. The rail sits outside the card and takes no vertical padding
    // where a clash continues, which is what lets consecutive clashing events join
    // into one unbroken line instead of showing a gap at every card boundary.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = 12.dp),
    ) {
        // Rounding only the ends of a run is what removes the seam between cards;
        // rounding every segment made one clash look like several.
        val railEnd = 2.dp
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .padding(
                    top = if (clash.continuesAbove) 0.dp else 4.dp,
                    bottom = if (clash.continuesBelow) 0.dp else 4.dp,
                )
                .clip(
                    RoundedCornerShape(
                        topStart = if (clash.continuesAbove) 0.dp else railEnd,
                        topEnd = if (clash.continuesAbove) 0.dp else railEnd,
                        bottomStart = if (clash.continuesBelow) 0.dp else railEnd,
                        bottomEnd = if (clash.continuesBelow) 0.dp else railEnd,
                    )
                )
                .background(if (clash.inClash) CLASH_COLOUR else Color.Transparent)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(
                // Both tiers must differ from the page background, or the card
                // outline disappears -- which is what happened in This week, where
                // every day is current-week and the card matched the surface behind.
                containerColor = if (isCurrentWeek) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    // Past and future weeks sit back visually from the current one.
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            ),
        ) {
            // Intrinsic height again, one level down, so the category bar can grow
            // with the text rather than stopping short when a title wraps.
            Row(
                modifier = Modifier
                    .padding(12.dp)
                    .height(IntrinsicSize.Min)
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent)
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        // Prep is a deadline, not a slot, so a zero-length prep row
                        // reads as a due time rather than as an all-day event.
                        text = when {
                            event.feedType?.isPrep == true -> "Due ${event.endTime}"
                            event.isInstant -> "All day"
                            else -> "${event.startTime} - ${event.endTime}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )

                    val details = listOfNotNull(
                        event.location,
                        event.tutor ?: event.staff,
                        event.notes,
                    ).filter { it.isNotBlank() }

                    if (details.isNotEmpty()) {
                        Text(
                            text = details.joinToString("  -  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val flags = buildList {
                        if (clash.inClash) add("Clash")
                        if (event.isOnline) add("Online")
                        if (event.isAccompanied) add("Accompanied")
                    }
                    if (flags.isNotEmpty()) {
                        Text(
                            text = flags.joinToString(" - "),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (clash.inClash) {
                                CLASH_COLOUR
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text("Nothing to show here", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(4.dp))
            Text(
                "Try another tab, or sync from the toolbar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
