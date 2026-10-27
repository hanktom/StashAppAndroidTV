package com.github.damontecres.stashapp.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Store [GridSort]s
 */
@Dao
interface GridSortsDao {
    @Query("SELECT * FROM grid_sorts WHERE serverUrl = :serverUrl AND filterKey = :filterKey")
    fun get(
        serverUrl: String,
        filterKey: String,
    ): GridSort?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg items: GridSort)

    @Query("DELETE FROM grid_sorts")
    fun deleteAll()

    /**
     * Delete everything except the [keep] most recently updated rows.
     *
     * When there are fewer than [keep] rows the sub-select yields NULL and nothing is deleted.
     */
    @Query(
        "DELETE FROM grid_sorts WHERE updatedAt < " +
            "(SELECT updatedAt FROM grid_sorts ORDER BY updatedAt DESC LIMIT 1 OFFSET :keep)",
    )
    fun prune(keep: Int)
}
