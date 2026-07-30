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
    val currentGroupIds = scene.groups.map { it.group.id }.toSet()
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
) {
    val groupIds = scene.groups.map { it.group.id } + groupId
    mutationEngine.setGroupsOnScene(scene.id, groupIds)
}
