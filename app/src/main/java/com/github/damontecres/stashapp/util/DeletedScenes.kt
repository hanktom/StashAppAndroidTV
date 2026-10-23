package com.github.damontecres.stashapp.util

import androidx.compose.runtime.mutableStateMapOf

/**
 * In-memory record of scenes deleted this session; grids are not refreshed after a mutation, so
 * cards use this to show a "deleted" badge.
 */
object DeletedScenes {
    private val ids = mutableStateMapOf<String, Boolean>()

    fun markDeleted(id: String) {
        ids[id] = true
    }

    fun isDeleted(id: String): Boolean = ids[id] == true
}
