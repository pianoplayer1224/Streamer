package com.streamer.timetable.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Event::class, NotificationRule::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao

    abstract fun notificationRuleDao(): NotificationRuleDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "streamer.db",
            )
                // Real migrations, because notification rules are user-authored and
                // cannot be rebuilt by re-syncing the way cached events can.
                .addMigrations(*ALL_MIGRATIONS)
                .build()
                .also { instance = it }
        }
    }
}
