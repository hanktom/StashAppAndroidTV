package com.github.damontecres.stashapp.data.room

import androidx.room.Entity

/**
 * Stores the last-focused item index for a given filter/list so that
 * re-entering that list restores the grid's scroll position.
 */
@Entity(tableName = "grid_positions", primaryKeys = ["serverUrl", "filterKey"])
data class GridPosition(
    val serverUrl: String,
    val filterKey: String,
    val position: Int,
    val updatedAt: Long,
)
