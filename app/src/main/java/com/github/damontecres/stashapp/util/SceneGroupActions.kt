package com.github.damontecres.stashapp.util

import com.apollographql.apollo.api.Optional
import com.github.damontecres.stashapp.api.fragment.GroupData
import com.github.damontecres.stashapp.api.fragment.SlimSceneData
import com.github.damontecres.stashapp.api.type.FindFilterType
import com.github.damontecres.stashapp.api.type.SortDirectionEnum

/**
 * Find all groups on the server that the scene is not already in, sorted by name
 */
suspend fun findGroupsNotInScene(
    queryEngine: QueryEngine,
    scene: SlimSceneData,
): List<GroupData> {
    val currentGroupIds = SceneGroupOverrides.effectiveGroupIds(scene).toSet()
    return queryEngine
        .findGroups(
            findFilter =
                FindFilterType(
                    sort = Optional.present("name"),
                    direction = Optional.present(SortDirectionEnum.ASC),
                    per_page = Optional.present(-1),
                ),
            useRandom = false,
        ).filter { it.id !in currentGroupIds }
}

/**
 * Add the scene to a group, keeping its existing groups
 */
suspend fun addSceneToGroup(
    mutationEngine: MutationEngine,
    scene: SlimSceneData,
    groupId: String,
    groupName: String,
) {
    // scene.groups can be stale (the grid is never refreshed after a mutation), so use the
    // effective groups; a map also guards against sending the same id twice
    val newGroups = SceneGroupOverrides.effectiveGroups(scene) + (groupId to groupName)
    mutationEngine.setGroupsOnScene(scene.id, newGroups.keys.toList())
    SceneGroupOverrides.set(scene.id, newGroups)
}

/**
 * Remove the scene from the given groups, keeping all of its other groups
 */
suspend fun removeSceneFromGroups(
    mutationEngine: MutationEngine,
    scene: SlimSceneData,
    groupIds: Collection<String>,
) {
    val remaining = SceneGroupOverrides.effectiveGroups(scene).filterKeys { it !in groupIds }
    mutationEngine.setGroupsOnScene(scene.id, remaining.keys.toList())
    SceneGroupOverrides.set(scene.id, remaining)
}
