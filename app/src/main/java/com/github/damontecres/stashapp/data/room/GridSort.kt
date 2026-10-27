package com.github.damontecres.stashapp.data.room

import androidx.room.Entity

/**
 * Stores the last-chosen sort (as JSON of a [com.github.damontecres.stashapp.data.SortAndDirection])
 * for a given filter/list so that re-entering that list re-applies it.
 */
@Entity(tableName = "grid_sorts", primaryKeys = ["serverUrl", "filterKey"])
data class GridSort(
    val serverUrl: String,
    val filterKey: String,
    val sort: String,
    val updatedAt: Long,
)
