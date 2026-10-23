package com.github.damontecres.stashapp.util

import android.content.Context
import android.util.Log
import com.github.damontecres.stashapp.StashApplication
import com.github.damontecres.stashapp.proto.RecentActionPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * The most recently used long-click action on a scene, used to surface a "repeat last action"
 * shortcut in the scene's long-click menu.
 */
sealed interface RecentSceneAction {
    data class AddToGroup(
        val groupId: String,
        val groupName: String,
    ) : RecentSceneAction

    data object SubsTag : RecentSceneAction

    data object SubsSourceTag : RecentSceneAction

    data class AddTag(
        val tagName: String,
    ) : RecentSceneAction

    data object GenerateAndIdentify : RecentSceneAction

    data object RemoveFromRmGroups : RecentSceneAction
}

private const val KIND_ADD_TO_GROUP = "ADD_TO_GROUP"
private const val KIND_SUBS_TAG = "SUBS_TAG"
private const val KIND_SUBS_SOURCE_TAG = "SUBS_SOURCE_TAG"
private const val KIND_ADD_TAG = "ADD_TAG"
private const val KIND_GENERATE_IDENTIFY = "GENERATE_IDENTIFY"
private const val KIND_REMOVE_FROM_RM_GROUPS = "REMOVE_FROM_RM_GROUPS"

// Legacy kind from when the "0 RM" action was hardcoded; decoded as AddTag("0 RM")
private const val KIND_RM_TAG = "RM_TAG"

/**
 * Persists (via [Context.preferences]) and caches the most recently used scene long-click action.
 *
 * [current] is safe to read synchronously/cheaply from non-suspend code (e.g. while building a
 * long-click menu). It reflects the last value loaded by [load] or set by [record].
 */
object RecentSceneActionStore {
    private const val TAG = "RecentSceneActionStore"

    @Volatile
    private var cached: RecentSceneAction? = null

    /** Synchronously readable cached value; null if none recorded/loaded yet. */
    val current: RecentSceneAction?
        get() = cached

    /** Update the cache immediately and persist asynchronously. */
    fun record(action: RecentSceneAction) {
        cached = action
        val context = StashApplication.getApplication()
        CoroutineScope(Dispatchers.IO + StashCoroutineExceptionHandler()).launch {
            context.preferences.updateData {
                it.updateRecentActionPreferences {
                    when (action) {
                        is RecentSceneAction.AddToGroup -> {
                            sceneActionKind = KIND_ADD_TO_GROUP
                            sceneActionTargetId = action.groupId
                            sceneActionTargetName = action.groupName
                        }

                        is RecentSceneAction.SubsTag -> {
                            sceneActionKind = KIND_SUBS_TAG
                            sceneActionTargetId = ""
                            sceneActionTargetName = ""
                        }

                        is RecentSceneAction.SubsSourceTag -> {
                            sceneActionKind = KIND_SUBS_SOURCE_TAG
                            sceneActionTargetId = ""
                            sceneActionTargetName = ""
                        }

                        is RecentSceneAction.AddTag -> {
                            sceneActionKind = KIND_ADD_TAG
                            sceneActionTargetId = ""
                            sceneActionTargetName = action.tagName
                        }

                        is RecentSceneAction.GenerateAndIdentify -> {
                            sceneActionKind = KIND_GENERATE_IDENTIFY
                            sceneActionTargetId = ""
                            sceneActionTargetName = ""
                        }

                        is RecentSceneAction.RemoveFromRmGroups -> {
                            sceneActionKind = KIND_REMOVE_FROM_RM_GROUPS
                            sceneActionTargetId = ""
                            sceneActionTargetName = ""
                        }
                    }
                }
            }
        }
    }

    /** Load the persisted value into the cache. Call once at app start. */
    suspend fun load(context: Context) {
        try {
            val prefs = context.preferences.data.firstOrNull()?.recentActionPreferences
            cached = prefs?.toRecentSceneActionOrNull()
        } catch (ex: Exception) {
            // Never let a bad/corrupt preferences read block or crash app start
            Log.e(TAG, "Failed to load recent scene action preferences", ex)
        }
    }
}

/**
 * Decodes proto preferences into a [RecentSceneAction], returning null for unknown/empty/garbage
 * data. Never throws.
 */
private fun RecentActionPreferences.toRecentSceneActionOrNull(): RecentSceneAction? =
    when (sceneActionKind) {
        KIND_ADD_TO_GROUP ->
            if (sceneActionTargetId.isNotBlank()) {
                RecentSceneAction.AddToGroup(sceneActionTargetId, sceneActionTargetName)
            } else {
                null
            }

        KIND_SUBS_TAG -> RecentSceneAction.SubsTag

        KIND_SUBS_SOURCE_TAG -> RecentSceneAction.SubsSourceTag

        KIND_GENERATE_IDENTIFY -> RecentSceneAction.GenerateAndIdentify

        KIND_REMOVE_FROM_RM_GROUPS -> RecentSceneAction.RemoveFromRmGroups

        KIND_ADD_TAG ->
            if (sceneActionTargetName.isNotBlank()) {
                RecentSceneAction.AddTag(sceneActionTargetName)
            } else {
                null
            }

        KIND_RM_TAG -> RecentSceneAction.AddTag("0 RM")

        else -> null
    }
