package com.streamer.timetable.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations.
 *
 * Events are a disposable cache and could be dropped freely, but notification rules
 * are user-authored and cannot be recovered by re-syncing. That is what these exist
 * to protect: destructive fallback would silently delete someone's reminders on any
 * future schema change.
 *
 * The CREATE TABLE statements below are copied from Room's own exported schema in
 * `app/schemas`, not written from memory. Room validates the live database against
 * that schema on open and throws if a single column type or nullability differs, so
 * an approximation here would be a crash on launch rather than a subtle bug.
 */

/** v1 had events only. v2 introduced notification rules. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `notification_rules` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `label` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `titleContains` TEXT,
                `locationContains` TEXT,
                `tutorContains` TEXT,
                `feedKey` TEXT,
                `timingMode` TEXT NOT NULL,
                `minutesBefore` INTEGER NOT NULL,
                `fixedTime` TEXT NOT NULL,
                `dayOffset` INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

/**
 * v3 replaces the numeric `dayOffset` with a named `fixedAnchor`.
 *
 * The integer could express "same day" and "day before" but had no sensible value
 * for "start of the week", which is not a fixed offset from the event at all. SQLite
 * cannot drop a column in older versions, so the table is rebuilt and the existing
 * offsets are mapped onto the new names -- no rule loses its timing.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `notification_rules_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `label` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `titleContains` TEXT,
                `locationContains` TEXT,
                `tutorContains` TEXT,
                `feedKey` TEXT,
                `timingMode` TEXT NOT NULL,
                `minutesBefore` INTEGER NOT NULL,
                `fixedTime` TEXT NOT NULL,
                `fixedAnchor` TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            INSERT INTO `notification_rules_new` (
                `id`, `label`, `enabled`, `titleContains`, `locationContains`,
                `tutorContains`, `feedKey`, `timingMode`, `minutesBefore`,
                `fixedTime`, `fixedAnchor`
            )
            SELECT
                `id`, `label`, `enabled`, `titleContains`, `locationContains`,
                `tutorContains`, `feedKey`, `timingMode`, `minutesBefore`,
                `fixedTime`,
                CASE WHEN `dayOffset` = -1 THEN 'DAY_BEFORE' ELSE 'EVENT_DAY' END
            FROM `notification_rules`
            """.trimIndent()
        )

        db.execSQL("DROP TABLE `notification_rules`")
        db.execSQL("ALTER TABLE `notification_rules_new` RENAME TO `notification_rules`")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
