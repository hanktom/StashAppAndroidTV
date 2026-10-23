package com.github.damontecres.stashapp.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Store [GridPosition]s
 */
@Dao
interface GridPositionsDao {
    @Query("SELECT * FROM grid_positions WHERE serverUrl = :serverUrl AND filterKey = :filterKey")
    fun get(
        serverUrl: String,
        filterKey: String,
    ): GridPosition?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg items: GridPosition)

    @Query("DELETE FROM grid_positions")
    fun deleteAll()

    /**
     * Delete everything except the [keep] most recently updated rows.
     *
     * When there are fewer than [keep] rows the sub-select yields NULL and nothing is deleted.
     */
    @Query(
        "DELETE FROM grid_positions WHERE updatedAt < " +
            "(SELECT updatedAt FROM grid_positions ORDER BY updatedAt DESC LIMIT 1 OFFSET :keep)",
    )
    fun prune(keep: Int)
}
