package com.github.damontecres.stashapp.util

import com.github.damontecres.stashapp.api.fragment.SlimSceneData
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks group membership changes made during this app session.
 *
 * Like [SubsQueueOverrides], this exists because the scene objects backing a grid
 * ([SlimSceneData]) are never refreshed after a mutation, so right after adding/removing a group
 * the card's own `groups` list is stale. Menus and mutations should use [effectiveGroups] /
 * [effectiveGroupIds] instead of reading `scene.groups` directly.
 *
 * Entries only live as long as the process; a fresh list query is authoritative again on restart.
 */
object SceneGroupOverrides {
    /** sceneId -> (groupId -> groupName) for the full set of groups the scene belongs to after the last change. */
    private val overrides = ConcurrentHashMap<String, Map<String, String>>()

    /** Record the scene's effective groups (id -> name) after a successful mutation. */
    fun set(
        sceneId: String,
        groups: Map<String, String>,
    ) {
        overrides[sceneId] = groups.toMap()
    }

    /** Forget any session override for [sceneId] (e.g. after re-fetching fresh data). */
    fun clear(sceneId: String) {
        overrides.remove(sceneId)
    }

    /** The scene's groups (id -> name), preferring anything changed in this session over [SlimSceneData.groups]. */
    fun effectiveGroups(scene: SlimSceneData): Map<String, String> =
        overrides[scene.id] ?: scene.groups.associate { it.group.id to it.group.name }

    /** The scene's group ids, preferring anything changed in this session over [SlimSceneData.groups]. */
    fun effectiveGroupIds(scene: SlimSceneData): List<String> = effectiveGroups(scene).keys.toList()
}
