package com.github.damontecres.stashapp.data.room

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.github.damontecres.stashapp.data.DataType

val MIGRATION_4_TO_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create a new temporary table with the new primary key
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playback_effects_temp` " +
                    "(`serverUrl` TEXT NOT NULL, `id` TEXT NOT NULL, `dataType` INTEGER NOT NULL, " +
                    "`rotation` INTEGER NOT NULL, `brightness` INTEGER NOT NULL, `contrast` INTEGER NOT NULL, " +
                    "`saturation` INTEGER NOT NULL, `hue` INTEGER NOT NULL, " +
                    "`red` INTEGER NOT NULL, `green` INTEGER NOT NULL, `blue` INTEGER NOT NULL, " +
                    "`blur` INTEGER NOT NULL, PRIMARY KEY(`serverUrl`, `id`, `dataType`))",
            )
            // Insert all of the data into the new temp table, defaulting the dataType to SCENE
            db.execSQL(
                "INSERT INTO playback_effects_temp " +
                    "SELECT serverUrl, id, ${DataType.SCENE.ordinal}, rotation, brightness, contrast, " +
                    "saturation, hue, red, green, blue, blur " +
                    "FROM playback_effects",
            )
            // Drop original table and rename temporary one
            db.execSQL("DROP TABLE playback_effects")
            db.execSQL("ALTER TABLE playback_effects_temp RENAME TO playback_effects")
        }
    }

val MIGRATION_5_TO_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `grid_positions` " +
                    "(`serverUrl` TEXT NOT NULL, `filterKey` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`serverUrl`, `filterKey`))",
            )
        }
    }

val MIGRATION_6_TO_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `grid_sorts` " +
                    "(`serverUrl` TEXT NOT NULL, `filterKey` TEXT NOT NULL, `sort` TEXT NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`serverUrl`, `filterKey`))",
            )
        }
    }
