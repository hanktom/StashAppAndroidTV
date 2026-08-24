package com.github.damontecres.stashapp.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks subs:pending changes made during this app session.
 *
 * The scene objects backing a grid ([com.github.damontecres.stashapp.api.fragment.SlimSceneData])
 * are whatever the list query returned and are never refreshed after a mutation, so right after
 * adding/removing the tag the card's own `tags` list is stale. The long-click menu consults these
 * overrides first so it offers the correct add/remove action instead of repeating the last one.
 *
 * Entries only live as long as the process; a fresh list query is authoritative again on restart.
 */
object SubsQueueOverrides {
    /** sceneId -> the subs:pending tag id, or [REMOVED] if the tag was taken off the scene. */
    private val overrides = ConcurrentHashMap<String, String>()

    private const val REMOVED = ""

    fun setAdded(
        sceneId: String,
        tagId: String,
    ) {
        overrides[sceneId] = tagId
    }

    fun setRemoved(sceneId: String) {
        overrides[sceneId] = REMOVED
    }

    /**
     * The scene's subs:pending tag id, preferring anything changed in this session over [fromTags].
     * Null means the scene is not in the queue.
     */
    fun pendingTagId(
        sceneId: String,
        fromTags: String?,
    ): String? =
        when (val override = overrides[sceneId]) {
            null -> fromTags
            REMOVED -> null
            else -> override
        }
}
