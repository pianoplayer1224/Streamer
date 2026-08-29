package com.streamer.timetable.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.streamer.timetable.data.Event
import com.streamer.timetable.data.Feed
import com.streamer.timetable.data.NotificationRule
import com.streamer.timetable.data.ParticipantDto
import com.streamer.timetable.data.SCHOOL_ZONE
import com.streamer.timetable.debug.CrashReporter
import com.streamer.timetable.sync.SyncRepository
import com.streamer.timetable.sync.SyncResult
import com.streamer.timetable.sync.SyncStats
import com.streamer.timetable.notify.AlertScheduler
import com.streamer.timetable.widget.WidgetUpdater
import com.streamer.timetable.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** An event opened in the detail sheet, with the clash state the list computed. */
data class SelectedEvent(
    val event: Event,
    val clash: ClashState,
    val participants: List<ParticipantDto> = emptyList(),
    val participantsLoading: Boolean = false,
    val participantsUnavailable: Boolean = false,
)

data class UiState(
    val signedIn: Boolean = false,
    val syncing: Boolean = false,
    val lastSyncedMillis: Long = 0L,
    val message: String? = null,
    val needsReauth: Boolean = false,
    val storedEventCount: Int = 0,
)

class TimetableViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = SyncRepository(app)

    private val _uiState = MutableStateFlow(
        UiState(
            signedIn = repository.hasCredentials(),
            lastSyncedMillis = repository.lastSyncedAtMillis(),
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _enabledFeeds = MutableStateFlow(Feed.entries.toSet())
    val enabledFeeds: StateFlow<Set<Feed>> = _enabledFeeds.asStateFlow()

    private val _selectedTab = MutableStateFlow(TimetableTab.THIS_WEEK)
    val selectedTab: StateFlow<TimetableTab> = _selectedTab.asStateFlow()

    val rules: StateFlow<List<NotificationRule>> = repository.observeRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncStats = MutableStateFlow(repository.syncStats())
    val syncStats: StateFlow<SyncStats> = _syncStats.asStateFlow()

    private val _diagnostics = MutableStateFlow<AlertScheduler.Diagnostics?>(null)
    val diagnostics: StateFlow<AlertScheduler.Diagnostics?> = _diagnostics.asStateFlow()

    /** Bumped when the active tab is tapped again, asking the list to re-anchor. */
    private val _scrollToTodayRequests = MutableStateFlow(0)
    val scrollToTodayRequests: StateFlow<Int> = _scrollToTodayRequests.asStateFlow()

    private val _hideMusBlock = MutableStateFlow(repository.hideMusBlock())
    val hideMusBlock: StateFlow<Boolean> = _hideMusBlock.asStateFlow()

    private val _animateWeekScroll = MutableStateFlow(repository.animateWeekScroll())
    val animateWeekScroll: StateFlow<Boolean> = _animateWeekScroll.asStateFlow()

    private val _daysBehind = MutableStateFlow(repository.daysBehind())
    val daysBehind: StateFlow<Int> = _daysBehind.asStateFlow()

    private val _daysAhead = MutableStateFlow(repository.daysAhead())
    val daysAhead: StateFlow<Int> = _daysAhead.asStateFlow()

    private val _syncIntervalHours = MutableStateFlow(repository.syncIntervalHours())
    val syncIntervalHours: StateFlow<Int> = _syncIntervalHours.asStateFlow()

    /** Debug override for "today". Null means use the real date. */
    private val _debugDate = MutableStateFlow(
        repository.debugDateOverride()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    )
    val debugDate: StateFlow<LocalDate?> = _debugDate.asStateFlow()

    /** The date the whole UI treats as today: the override if set, else the real one. */
    val today: StateFlow<LocalDate> = _debugDate
        .map { it ?: LocalDate.now(SCHOOL_ZONE) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            _debugDate.value ?: LocalDate.now(SCHOOL_ZONE),
        )

    /** The event shown in the detail sheet, or null when the sheet is closed. */
    private val _selectedEvent = MutableStateFlow<SelectedEvent?>(null)
    val selectedEvent: StateFlow<SelectedEvent?> = _selectedEvent.asStateFlow()

    /**
     * Every stored event, with the day grouping left to the UI.
     *
     * Grouping used to happen here and arrive as a separate StateFlow, but that made
     * it lag a tab change by a frame: the tab updated immediately while the sections
     * were still the previous tab's, so the list drew the wrong content once before
     * correcting. Deriving the sections synchronously from this list removes that
     * frame entirely.
     */
    val events: StateFlow<List<Event>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val allEvents = events

    val musBlockCount: StateFlow<Int> =
        allEvents.map { events ->
            events.count { it.title.equals(MUS_BLOCK_TITLE, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        if (repository.hasCredentials()) {
            SyncWorker.schedule(app, repository.syncIntervalHours())
            refresh()
        }
        refreshStoredCount()

        // Opening the app is the natural recovery point if the widget's alarms were
        // lost -- a force-stop clears them, and nothing else would notice.
        viewModelScope.launch {
            WidgetUpdater.refresh(app)
            WidgetUpdater.scheduleMidnightRefresh(app)
        }
        // Re-arm the day-rollover alarm on every launch too, so a force-stop that
        // cleared it recovers without needing a reboot.
    }

    /** The timetable feed's state before Upcoming borrowed it, to restore on exit. */
    private var timetableFeedBeforeUpcoming: Boolean? = null

    /**
     * Switching tab adjusts which feeds are on.
     *
     * Upcoming exists to get the routine timetable out of the way, so entering it
     * always switches that feed off -- "default always to it off", even if it was
     * turned back on during a previous visit. Leaving Upcoming restores whatever the
     * feed's state was beforehand, so the tab borrows the filter rather than
     * permanently changing it. Any other feed is left exactly as the user left it.
     */
    fun selectTab(tab: TimetableTab) {
        val previous = _selectedTab.value

        // Re-tapping the active tab means "take me back to today". It must not run
        // the feed logic below, or re-tapping Upcoming would toggle the timetable
        // feed a second time and lose the state it is holding for the way out.
        if (tab == previous) {
            _scrollToTodayRequests.value++
            return
        }

        when {
            tab == TimetableTab.UPCOMING && previous != TimetableTab.UPCOMING -> {
                // Remember the state to hand back on the way out, so the tab borrows
                // the filter rather than permanently changing it.
                timetableFeedBeforeUpcoming = Feed.SIMS_ACADEMIC in _enabledFeeds.value
                _enabledFeeds.value = _enabledFeeds.value - Feed.SIMS_ACADEMIC
            }

            tab != TimetableTab.UPCOMING && previous == TimetableTab.UPCOMING -> {
                if (timetableFeedBeforeUpcoming == true) {
                    _enabledFeeds.value = _enabledFeeds.value + Feed.SIMS_ACADEMIC
                }
                timetableFeedBeforeUpcoming = null
            }
        }
        _selectedTab.value = tab
    }

    fun toggleFeed(feed: Feed) {
        _enabledFeeds.value = _enabledFeeds.value.toMutableSet().apply {
            if (!add(feed)) remove(feed)
        }
    }

    fun setHideMusBlock(hide: Boolean) {
        _hideMusBlock.value = hide
        repository.setHideMusBlock(hide)
        // The widget honours this preference too, so it must not disagree with the app.
        viewModelScope.launch { WidgetUpdater.refresh(getApplication()) }
    }

    /**
     * Saves a rule and rebuilds the alarm schedule.
     *
     * Rescheduling here rather than only after a sync is what makes an edit take
     * effect straight away; otherwise a new rule would sit inert until the next
     * background sync happened to run.
     */
    fun saveRule(rule: NotificationRule) {
        viewModelScope.launch {
            repository.saveRule(rule)
            AlertScheduler.reschedule(getApplication())
        }
    }

    fun deleteRule(rule: NotificationRule) {
        viewModelScope.launch {
            repository.deleteRule(rule)
            AlertScheduler.reschedule(getApplication())
        }
    }

    fun addSampleEvents() {
        viewModelScope.launch {
            repository.addSampleEvents(today.value)
            AlertScheduler.reschedule(getApplication())
            _uiState.value = _uiState.value.copy(
                message = "Sample events added - removed on next sync",
            )
            refreshStoredCount()
        }
    }

    fun refreshDiagnostics() {
        viewModelScope.launch {
            _diagnostics.value = AlertScheduler.diagnose(getApplication())
            // Re-read from preferences so background syncs, which run with no UI
            // alive, are reflected too.
            _syncStats.value = repository.syncStats()
        }
    }

    fun resetSyncStats() {
        repository.resetSyncStats()
        _syncStats.value = repository.syncStats()
    }

    /** Debug: pushes the widget and reports what the update path actually did. */
    fun crashLogCount(): Int = CrashReporter.count(getApplication())

    /** The most recent crash report, or null if the app has not crashed. */
    fun latestCrashText(): String? =
        CrashReporter.latest(getApplication())?.readText()

    fun clearCrashLogs() {
        CrashReporter.clear(getApplication())
        _uiState.value = _uiState.value.copy(message = "Crash logs cleared")
    }

    fun refreshWidget() {
        viewModelScope.launch {
            val outcome = WidgetUpdater.refresh(getApplication())
            _uiState.value = _uiState.value.copy(message = "Widget: $outcome")
        }
    }

    fun sendTestNotification() {
        AlertScheduler.showTestNotification(getApplication())
    }

    fun scheduleTestAlert() {
        AlertScheduler.scheduleTestAlert(getApplication(), delaySeconds = 60)
        _uiState.value = _uiState.value.copy(message = "Test alert booked for 1 minute")
    }

    /**
     * Changes how much timetable is kept offline, then re-syncs.
     *
     * The immediate sync matters: widening the range leaves the new days empty until
     * something fetches them, and waiting hours for a background pass would look like
     * the setting had not worked.
     */
    fun setWindow(behind: Int, ahead: Int) {
        _daysBehind.value = behind
        _daysAhead.value = ahead
        repository.setWindow(behind, ahead)
        refresh()
    }

    fun setSyncIntervalHours(hours: Int) {
        _syncIntervalHours.value = hours
        repository.setSyncIntervalHours(hours)
        SyncWorker.schedule(getApplication(), hours)
    }

    fun setAnimateWeekScroll(animate: Boolean) {
        _animateWeekScroll.value = animate
        repository.setAnimateWeekScroll(animate)
    }

    fun setDebugDate(date: LocalDate?) {
        _debugDate.value = date
        repository.setDebugDateOverride(date?.toString())
        // Keeps the widget testable out of term, alongside the app.
        viewModelScope.launch { WidgetUpdater.refresh(getApplication()) }
    }

    fun showDetails(event: Event, clash: ClashState) {
        _selectedEvent.value = SelectedEvent(event, clash, participantsLoading = true)

        viewModelScope.launch {
            val result = repository.fetchParticipants(event.serverId)
            // The sheet may have been dismissed, or another event opened, while the
            // request was in flight; only apply the result if it still belongs.
            val current = _selectedEvent.value ?: return@launch
            if (current.event.uid != event.uid) return@launch

            _selectedEvent.value = result.fold(
                onSuccess = { current.copy(participants = it, participantsLoading = false) },
                onFailure = {
                    current.copy(participantsLoading = false, participantsUnavailable = true)
                },
            )
        }
    }

    fun dismissDetails() {
        _selectedEvent.value = null
    }

    fun clearStoredEvents() {
        viewModelScope.launch {
            repository.clearStoredEvents()
            _uiState.value = _uiState.value.copy(
                lastSyncedMillis = 0L,
                message = "Local timetable cleared",
            )
            refreshStoredCount()
        }
    }

    private fun refreshStoredCount() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(storedEventCount = repository.storedEventCount())
        }
    }

    fun signIn(username: String, password: String, domain: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(syncing = true, message = null)
            val result = repository.testCredentials(username.trim(), password, domain.trim())
            result.fold(
                onSuccess = { serverUsername ->
                    repository.saveCredentials(username.trim(), password, domain.trim())
                    _uiState.value = _uiState.value.copy(
                        signedIn = true,
                        syncing = false,
                        needsReauth = false,
                    )
                    SyncWorker.schedule(getApplication(), repository.syncIntervalHours())
                    onResult(true, serverUsername)
                    refresh()
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(syncing = false)
                    onResult(false, error.message ?: "Could not sign in.")
                },
            )
        }
    }

    fun refresh() {
        if (_uiState.value.syncing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(syncing = true, message = null)
            // Syncs around the effective date, so a debug override also fetches the
            // window it is pretending to be in.
            when (val result = repository.sync(today.value)) {
                is SyncResult.Success -> _uiState.value = _uiState.value.copy(
                    syncing = false,
                    lastSyncedMillis = repository.lastSyncedAtMillis(),
                    message = if (result.eventCount == 0) "No events in range" else null,
                    needsReauth = false,
                )

                is SyncResult.AuthFailed -> _uiState.value = _uiState.value.copy(
                    syncing = false,
                    message = result.message,
                    needsReauth = true,
                )

                is SyncResult.Failed -> _uiState.value = _uiState.value.copy(
                    syncing = false,
                    // Offline is the expected state, not an error worth alarming about.
                    message = "Offline - showing saved timetable",
                )
            }
            refreshStoredCount()
            _syncStats.value = repository.syncStats()
        }
    }

    fun signOut() {
        repository.signOut()
        SyncWorker.cancel(getApplication())
        _uiState.value = UiState(signedIn = false)
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
