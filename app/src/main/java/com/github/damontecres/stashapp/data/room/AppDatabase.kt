package com.github.damontecres.stashapp.data.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [RecentSearchItem::class, PlaybackEffect::class, GridPosition::class, GridSort::class],
    version = 7,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recentSearchItemsDao(): RecentSearchItemsDao

    abstract fun playbackEffectsDao(): PlaybackEffectsDao

    abstract fun gridPositionsDao(): GridPositionsDao

    abstract fun gridSortsDao(): GridSortsDao
}
