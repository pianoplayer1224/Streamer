package com.streamer.timetable.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationRuleDao {

    @Query("SELECT * FROM notification_rules ORDER BY id ASC")
    fun observeAll(): Flow<List<NotificationRule>>

    @Query("SELECT * FROM notification_rules")
    suspend fun getAll(): List<NotificationRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: NotificationRule): Long

    @Update
    suspend fun update(rule: NotificationRule)

    @Delete
    suspend fun delete(rule: NotificationRule)
}
