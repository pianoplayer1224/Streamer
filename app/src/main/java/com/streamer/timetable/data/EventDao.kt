package com.streamer.timetable.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    @Query(
        """
        SELECT * FROM events
        WHERE startMillis >= :fromMillis AND startMillis < :toMillis
        ORDER BY startMillis ASC, title ASC
        """
    )
    fun observeRange(fromMillis: Long, toMillis: Long): Flow<List<Event>>

    @Query("SELECT * FROM events ORDER BY startMillis ASC")
    fun observeAll(): Flow<List<Event>>

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int

    /** One-shot read, for the alarm scheduler which runs outside any UI. */
    @Query("SELECT * FROM events ORDER BY startMillis ASC")
    suspend fun allOnce(): List<Event>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<Event>)

    @Query("SELECT uid FROM events WHERE feed = :feed AND startMillis >= :fromMillis AND startMillis < :toMillis")
    suspend fun uidsInWindow(feed: String, fromMillis: Long, toMillis: Long): List<String>

    @Query("DELETE FROM events WHERE uid IN (:uids)")
    suspend fun deleteByUids(uids: List<String>)

    @Query("DELETE FROM events")
    suspend fun clear()

    /**
     * Drops anything outside the configured window.
     *
     * Shrinking the range would otherwise strand rows that no sync ever revisits --
     * `replaceWindow` only reconciles inside the window, so those would linger and
     * keep displaying long after they stopped being refreshed.
     */
    @Query("DELETE FROM events WHERE startMillis < :fromMillis OR startMillis >= :toMillis")
    suspend fun deleteOutsideWindow(fromMillis: Long, toMillis: Long)

    /**
     * Replaces one feed's contents for a date window.
     *
     * The delete step is what makes cancellations disappear. A plain upsert would
     * leave a lesson that was removed from the timetable sitting in the database
     * forever, which is worse than showing nothing -- the student would turn up to
     * a lesson that is not happening. Only rows inside the synced window are
     * eligible for deletion, so data outside the window survives untouched.
     */
    @Transaction
    suspend fun replaceWindow(
        feed: String,
        fromMillis: Long,
        toMillis: Long,
        events: List<Event>,
    ) {
        val existing = uidsInWindow(feed, fromMillis, toMillis).toSet()
        val incoming = events.map { it.uid }.toSet()
        val stale = (existing - incoming).toList()
        if (stale.isNotEmpty()) {
            // Chunked because SQLite caps host parameters at 999 per statement.
            stale.chunked(500).forEach { deleteByUids(it) }
        }
        if (events.isNotEmpty()) {
            upsertAll(events)
        }
    }
}
