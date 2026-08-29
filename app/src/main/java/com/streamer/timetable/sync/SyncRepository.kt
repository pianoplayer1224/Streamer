package com.streamer.timetable.sync

import android.content.Context
import androidx.core.content.edit
import com.streamer.timetable.auth.CredentialStore
import com.streamer.timetable.data.AppDatabase
import com.streamer.timetable.data.Event
import com.streamer.timetable.data.Feed
import com.streamer.timetable.data.NotificationRule
import com.streamer.timetable.data.ParticipantDto
import com.streamer.timetable.data.SCHOOL_ZONE
import com.streamer.timetable.data.sampleEvents
import com.streamer.timetable.data.toEvent
import com.streamer.timetable.net.AuthenticationException
import com.streamer.timetable.net.StreamApi
import com.streamer.timetable.net.ntlm.NtlmAuthenticator
import com.streamer.timetable.notify.AlertScheduler
import com.streamer.timetable.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate

/** Default reach of the offline window, either side of today. */
const val DEFAULT_DAYS_BEHIND = 14
const val DEFAULT_DAYS_AHEAD = 42

/** Default gap between background syncs, in hours. */
const val DEFAULT_SYNC_INTERVAL_HOURS = 12

/** Choices offered in Options. */
val DAYS_BEHIND_CHOICES = listOf(7, 14, 30, 60)
val DAYS_AHEAD_CHOICES = listOf(14, 28, 42, 84)

/** 0 means background sync is off; the app still syncs when opened. */
val SYNC_INTERVAL_CHOICES = listOf(0, 3, 6, 12, 24)

/**
 * Running tally of sync attempts.
 *
 * Counters live in preferences rather than memory so background syncs are counted
 * too -- those happen with no UI alive, and an in-memory count would reset on every
 * launch and always read zero, which is precisely the guesswork this removes.
 */
data class SyncStats(
    val attempts: Int = 0,
    val successes: Int = 0,
    val failures: Int = 0,
    val authFailures: Int = 0,
    val lastResult: String = "none yet",
    val lastAttemptMillis: Long = 0L,
)

sealed interface SyncResult {
    data class Success(val eventCount: Int, val feedsWithData: Int) : SyncResult

    /** Credentials were refused. The caller must prompt; retrying would risk lockout. */
    data class AuthFailed(val message: String) : SyncResult

    /** Transport failure. Safe to retry later. */
    data class Failed(val message: String) : SyncResult
}

class SyncRepository(context: Context) {

    private val appContext = context.applicationContext
    private val database = AppDatabase.get(appContext)
    private val dao = database.eventDao()
    private val ruleDao = database.notificationRuleDao()
    private val credentialStore = CredentialStore(appContext)

    private val api = StreamApi {
        credentialStore.load()?.let {
            NtlmAuthenticator.Credentials(it.username, it.password, it.domain)
        }
    }

    private val prefs = appContext.getSharedPreferences("streamer_sync", Context.MODE_PRIVATE)

    /** Whether this process has established the server-side student selection. */
    @Volatile
    private var sessionPrimed = false

    /**
     * Every stored event.
     *
     * The database only ever holds the synced window, so this is already bounded --
     * and observing it wholesale keeps the query independent of "today", which
     * matters once a debug date override can move today around.
     */
    fun observeAll(): Flow<List<Event>> = dao.observeAll()

    suspend fun storedEventCount(): Int = dao.count()

    fun observeRules(): Flow<List<NotificationRule>> = ruleDao.observeAll()

    suspend fun saveRule(rule: NotificationRule) {
        if (rule.id == 0L) ruleDao.insert(rule) else ruleDao.update(rule)
    }

    suspend fun deleteRule(rule: NotificationRule) = ruleDao.delete(rule)

    /**
     * Debug affordance: injects fabricated events, chiefly prep.
     *
     * The prep feed returns nothing for this student, so the Prep tab and any prep
     * rule cannot otherwise be exercised. These rows are removed by the next sync of
     * the affected feeds, which is the correct reconciliation behaviour and stops
     * sample data lingering as though it were real.
     */
    suspend fun addSampleEvents(today: LocalDate) {
        dao.upsertAll(sampleEvents(today))
    }

    /** Debug affordance: forces the next sync to repopulate from scratch. */
    suspend fun clearStoredEvents() {
        dao.clear()
        prefs.edit { remove(KEY_LAST_SYNC) }
    }

    fun lastSyncedAtMillis(): Long = prefs.getLong(KEY_LAST_SYNC, 0L)

    fun syncStats(): SyncStats = SyncStats(
        attempts = prefs.getInt(KEY_SYNC_ATTEMPTS, 0),
        successes = prefs.getInt(KEY_SYNC_SUCCESSES, 0),
        failures = prefs.getInt(KEY_SYNC_FAILURES, 0),
        authFailures = prefs.getInt(KEY_SYNC_AUTH_FAILURES, 0),
        lastResult = prefs.getString(KEY_SYNC_LAST_RESULT, null) ?: "none yet",
        lastAttemptMillis = prefs.getLong(KEY_SYNC_LAST_ATTEMPT, 0L),
    )

    fun resetSyncStats() {
        prefs.edit {
            remove(KEY_SYNC_ATTEMPTS)
            remove(KEY_SYNC_SUCCESSES)
            remove(KEY_SYNC_FAILURES)
            remove(KEY_SYNC_AUTH_FAILURES)
            remove(KEY_SYNC_LAST_RESULT)
            remove(KEY_SYNC_LAST_ATTEMPT)
        }
    }

    private fun recordAttempt() {
        prefs.edit {
            putInt(KEY_SYNC_ATTEMPTS, prefs.getInt(KEY_SYNC_ATTEMPTS, 0) + 1)
            putLong(KEY_SYNC_LAST_ATTEMPT, System.currentTimeMillis())
        }
    }

    private fun recordOutcome(key: String, description: String) {
        prefs.edit {
            putInt(key, prefs.getInt(key, 0) + 1)
            putString(KEY_SYNC_LAST_RESULT, description)
        }
    }

    /** Hidden by default: these sessions duplicate instrumental slots and add noise. */
    fun hideMusBlock(): Boolean = prefs.getBoolean(KEY_HIDE_MUS_BLOCK, true)

    fun setHideMusBlock(hide: Boolean) {
        prefs.edit { putBoolean(KEY_HIDE_MUS_BLOCK, hide) }
    }

    fun daysBehind(): Int = prefs.getInt(KEY_DAYS_BEHIND, DEFAULT_DAYS_BEHIND)

    fun daysAhead(): Int = prefs.getInt(KEY_DAYS_AHEAD, DEFAULT_DAYS_AHEAD)

    fun setWindow(behind: Int, ahead: Int) {
        prefs.edit {
            putInt(KEY_DAYS_BEHIND, behind)
            putInt(KEY_DAYS_AHEAD, ahead)
        }
    }

    fun syncIntervalHours(): Int =
        prefs.getInt(KEY_SYNC_INTERVAL, DEFAULT_SYNC_INTERVAL_HOURS)

    fun setSyncIntervalHours(hours: Int) {
        prefs.edit { putInt(KEY_SYNC_INTERVAL, hours) }
    }

    /** Whether opening a tab animates from the week's Monday down to today. */
    fun animateWeekScroll(): Boolean = prefs.getBoolean(KEY_ANIMATE_SCROLL, true)

    fun setAnimateWeekScroll(animate: Boolean) {
        prefs.edit { putBoolean(KEY_ANIMATE_SCROLL, animate) }
    }

    /**
     * Debug-only override for "today", as an ISO date, or null to use the real one.
     *
     * Persisted so it survives a relaunch -- otherwise it could not be used to test
     * the behaviour it exists to test, namely which week the list opens on.
     */
    fun debugDateOverride(): String? = prefs.getString(KEY_DEBUG_DATE, null)

    fun setDebugDateOverride(iso: String?) {
        prefs.edit { if (iso == null) remove(KEY_DEBUG_DATE) else putString(KEY_DEBUG_DATE, iso) }
    }

    fun hasCredentials(): Boolean = credentialStore.hasCredentials()

    fun saveCredentials(username: String, password: String, domain: String) {
        credentialStore.save(username, password, domain)
    }

    fun signOut() {
        credentialStore.clear()
        // Clear sync state but keep display preferences: signing out is about
        // credentials, and losing your filter choices with them would be surprising.
        prefs.edit { remove(KEY_LAST_SYNC) }
    }

    /**
     * Other participants in a lesson, fetched on demand.
     *
     * Primes the session first if this process has not synced yet: the participants
     * endpoint is student-scoped like the feeds, so without priming it would return
     * an empty list rather than an error.
     */
    suspend fun fetchParticipants(eventId: Long): Result<List<ParticipantDto>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!sessionPrimed) {
                    api.primeSession()
                    sessionPrimed = true
                }
                api.fetchParticipants(eventId)
            }
        }

    /** Verifies credentials without writing anything. Used by the login screen. */
    suspend fun testCredentials(
        username: String,
        password: String,
        domain: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        val probe = StreamApi {
            NtlmAuthenticator.Credentials(username, password, domain)
        }
        runCatching { probe.primeSession() }
    }

    /**
     * Pulls every feed for the offline window and reconciles it into the database.
     *
     * Feeds are fetched **sequentially, not in parallel**. NTLM ties its challenge to
     * a single TCP connection, and firing ten concurrent requests would let one
     * request pick up a connection that another is midway through authenticating,
     * producing sporadic and hard-to-diagnose 401s. Sequential fetching costs a few
     * seconds and is also gentler on the school's server.
     */
    suspend fun sync(today: LocalDate = LocalDate.now(SCHOOL_ZONE)): SyncResult =
        withContext(Dispatchers.IO) {
        recordAttempt()

        if (!credentialStore.hasCredentials()) {
            recordOutcome(KEY_SYNC_AUTH_FAILURES, "No credentials saved")
            return@withContext SyncResult.AuthFailed("No credentials saved.")
        }

        val from = today.minusDays(daysBehind().toLong())
        val to = today.plusDays(daysAhead().toLong())
        val fromMillis = from.toEpochMillis()
        val toMillis = to.toEpochMillis()
        val syncedAt = System.currentTimeMillis()

        // Establishes the server-side student selection AND confirms the password.
        // Without this the feeds authenticate fine but return empty arrays.
        try {
            api.primeSession()
            sessionPrimed = true
        } catch (e: AuthenticationException) {
            recordOutcome(KEY_SYNC_AUTH_FAILURES, "Rejected at sign-in")
            return@withContext SyncResult.AuthFailed(e.message ?: "Authentication failed.")
        } catch (e: IOException) {
            recordOutcome(KEY_SYNC_FAILURES, "Could not reach server")
            return@withContext SyncResult.Failed(e.message ?: "Could not reach the server.")
        }

        var total = 0
        var feedsWithData = 0

        for (feed in Feed.entries) {
            try {
                val dtos = api.fetchFeed(feed, from, to)
                val events = dtos.mapNotNull { it.toEvent(feed, syncedAt) }
                dao.replaceWindow(feed.key, fromMillis, toMillis, events)
                total += events.size
                if (events.isNotEmpty()) feedsWithData++
            } catch (e: AuthenticationException) {
                // Credentials went bad mid-sync. Stop immediately rather than
                // repeating a rejected password across the remaining feeds.
                recordOutcome(KEY_SYNC_AUTH_FAILURES, "Rejected mid-sync")
                return@withContext SyncResult.AuthFailed(e.message ?: "Authentication failed.")
            } catch (e: IOException) {
                // One feed failing should not discard the others. Leave this feed's
                // existing rows untouched and carry on.
                continue
            }
        }

        // Keep the database to exactly the configured window, so shrinking the
        // range actually frees the rows rather than leaving unrefreshed strays.
        dao.deleteOutsideWindow(fromMillis, toMillis)

        prefs.edit { putLong(KEY_LAST_SYNC, syncedAt) }
        recordOutcome(KEY_SYNC_SUCCESSES, "$total events from $feedsWithData feeds")

        // Lessons may have moved or been cancelled, so the booked alarms are now
        // stale. Rebuilding here keeps reminders tied to the current timetable.
        AlertScheduler.reschedule(appContext)
        // The widget reads the same table, so it is stale the moment a sync lands.
        WidgetUpdater.refresh(appContext)
        SyncResult.Success(total, feedsWithData)
    }

    private fun LocalDate.toEpochMillis(): Long =
        atStartOfDay(SCHOOL_ZONE).toInstant().toEpochMilli()

    private companion object {
        const val KEY_LAST_SYNC = "last_sync_millis"
        const val KEY_HIDE_MUS_BLOCK = "hide_mus_block"
        const val KEY_DEBUG_DATE = "debug_date_override"
        const val KEY_ANIMATE_SCROLL = "animate_week_scroll"
        const val KEY_DAYS_BEHIND = "days_behind"
        const val KEY_DAYS_AHEAD = "days_ahead"
        const val KEY_SYNC_INTERVAL = "sync_interval_hours"
        const val KEY_SYNC_ATTEMPTS = "sync_attempts"
        const val KEY_SYNC_SUCCESSES = "sync_successes"
        const val KEY_SYNC_FAILURES = "sync_failures"
        const val KEY_SYNC_AUTH_FAILURES = "sync_auth_failures"
        const val KEY_SYNC_LAST_RESULT = "sync_last_result"
        const val KEY_SYNC_LAST_ATTEMPT = "sync_last_attempt"
    }
}
