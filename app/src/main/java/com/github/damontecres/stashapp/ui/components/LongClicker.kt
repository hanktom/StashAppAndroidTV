package com.github.damontecres.stashapp.ui.components

import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import com.apollographql.apollo.api.Optional
import com.github.damontecres.stashapp.R
import com.github.damontecres.stashapp.StashApplication
import com.github.damontecres.stashapp.api.fragment.MarkerData
import com.github.damontecres.stashapp.api.fragment.SlimSceneData
import com.github.damontecres.stashapp.api.fragment.StashData
import com.github.damontecres.stashapp.api.type.CriterionModifier
import com.github.damontecres.stashapp.api.type.FindFilterType
import com.github.damontecres.stashapp.api.type.SceneFilterType
import com.github.damontecres.stashapp.api.type.StringCriterionInput
import com.github.damontecres.stashapp.api.type.TagCreateInput
import com.github.damontecres.stashapp.data.DataType
import com.github.damontecres.stashapp.filter.extractTitle
import com.github.damontecres.stashapp.navigation.Destination
import com.github.damontecres.stashapp.navigation.FilterAndPosition
import com.github.damontecres.stashapp.navigation.NavigationManager
import com.github.damontecres.stashapp.playback.PlaybackMode
import com.github.damontecres.stashapp.suppliers.FilterArgs
import com.github.damontecres.stashapp.ui.pages.DialogParams
import com.github.damontecres.stashapp.ui.pages.MAX_PLAYLIST_SIZE
import com.github.damontecres.stashapp.ui.showAddGroup
import com.github.damontecres.stashapp.ui.showShort
import com.github.damontecres.stashapp.util.DeletedScenes
import com.github.damontecres.stashapp.util.MutationEngine
import com.github.damontecres.stashapp.util.QueryEngine
import com.github.damontecres.stashapp.util.RecentSceneAction
import com.github.damontecres.stashapp.util.RecentSceneActionStore
import com.github.damontecres.stashapp.util.SameCodeCounts
import com.github.damontecres.stashapp.util.StashCoroutineExceptionHandler
import com.github.damontecres.stashapp.util.SceneGroupOverrides
import com.github.damontecres.stashapp.util.StashServer
import com.github.damontecres.stashapp.util.SubsQueueOverrides
import com.github.damontecres.stashapp.util.addSceneToGroup
import com.github.damontecres.stashapp.util.findGroupsNotInScene
import com.github.damontecres.stashapp.util.isNotNullOrBlank
import com.github.damontecres.stashapp.util.readOnlyModeDisabled
import com.github.damontecres.stashapp.util.removeSceneFromGroups
import com.github.damontecres.stashapp.util.resume_position
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun interface LongClicker<T> {
    fun longClick(
        item: T,
        filterAndPosition: FilterAndPosition?,
    )
}

class DefaultLongClicker(
    private val nav: NavigationManager,
    private val itemOnClick: ItemOnClicker<Any>,
    private val alwaysStartFromBeginning: Boolean,
    private val scope: CoroutineScope,
    private val markerPlayAllOnClick: (FilterAndPosition) -> Unit,
    private val onLongClick: (DialogParams) -> Unit,
) : LongClicker<Any> {
    override fun longClick(
        item: Any,
        filterAndPosition: FilterAndPosition?,
    ) {
        item as StashData
        if (item is SlimSceneData) {
            if (DeletedScenes.isDeleted(item.id)) {
                showShort("此影片已刪除")
                return
            }
            // Grid items are never refreshed (e.g. a tag removed on the detail page), so re-fetch
            // the scene before building the menu; fall back to the grid item if the fetch fails.
            scope.launch(StashCoroutineExceptionHandler(autoToast = false)) {
                val fresh =
                    runCatching {
                        QueryEngine(StashServer.requireCurrentServer())
                            .findScenes(ids = listOf(item.id), useRandom = false)
                            .firstOrNull()
                    }.getOrNull()
                if (fresh != null) {
                    SubsQueueOverrides.clear(item.id)
                    SceneGroupOverrides.clear(item.id)
                }
                withContext(Dispatchers.Main) {
                    showMenu(fresh ?: item, filterAndPosition)
                }
            }
        } else {
            showMenu(item, filterAndPosition)
        }
    }

    private fun showMenu(
        item: StashData,
        filterAndPosition: FilterAndPosition?,
    ) {
        val context = StashApplication.getApplication()
        val title = extractTitle(item) ?: ""

        // The last-used action is surfaced as the first entry of the menu. For the subs queue the
        // existing entry is *moved* to the top rather than duplicated; "add to group X" has no
        // equivalent top-level entry, so a dedicated shortcut is inserted.
        val recentAction = RecentSceneActionStore.current
        val subsItem = buildSubsQueueItem(item)
        val subsSourceItem = buildSubsSourceItem(item)
        val addTagItems = QUICK_ADD_TAGS.associateWith { buildAddTagItem(item, it) }
        val generateIdentifyItem = buildGenerateIdentifyItem(item)
        val removeRmGroupsItem = buildRemoveFromRmGroupsItem(item)
        val recentGroupItem =
            if (item is SlimSceneData &&
                readOnlyModeDisabled() &&
                recentAction is RecentSceneAction.AddToGroup &&
                recentAction.groupId !in SceneGroupOverrides.effectiveGroupIds(item)
            ) {
                DialogItem(
                    "加入群組：${recentAction.groupName}",
                    DataType.GROUP.iconStringId,
                ) {
                    scope.launch(StashCoroutineExceptionHandler(autoToast = true)) {
                        val server = StashServer.requireCurrentServer()
                        addSceneToGroup(
                            MutationEngine(server),
                            item,
                            recentAction.groupId,
                            recentAction.groupName,
                        )
                        RecentSceneActionStore.record(recentAction)
                        showShort("Added group '${recentAction.groupName}'")
                    }
                }
            } else {
                null
            }

        val items =
            buildList {
                recentGroupItem?.let { add(it) }
                if (subsItem != null && recentAction is RecentSceneAction.SubsTag) {
                    add(subsItem)
                }
                if (subsSourceItem != null && recentAction is RecentSceneAction.SubsSourceTag) {
                    add(subsSourceItem)
                }
                if (recentAction is RecentSceneAction.AddTag) {
                    addTagItems[recentAction.tagName]?.let { add(it) }
                }
                if (generateIdentifyItem != null && recentAction is RecentSceneAction.GenerateAndIdentify) {
                    add(generateIdentifyItem)
                }
                if (removeRmGroupsItem != null && recentAction is RecentSceneAction.RemoveFromRmGroups) {
                    add(removeRmGroupsItem)
                }
                if (item is MarkerData) {
                    add(
                        DialogItem(
                            context.getString(R.string.play_scene),
                            Icons.Default.PlayArrow,
                        ) {
                            itemOnClick.onClick(item, filterAndPosition)
                        },
                    )
                    add(
                        DialogItem(
                            context.getString(R.string.go_to_scene),
                            Icons.AutoMirrored.Default.ArrowForward,
                        ) {
                            nav.navigate(
                                Destination.Item(DataType.SCENE, item.scene.minimalSceneData.id),
                            )
                        },
                    )
                    add(
                        DialogItem(
                            context.getString(R.string.stashapp_details),
                            Icons.Default.Info,
                        ) {
                            nav.navigate(
                                Destination.MarkerDetails(item.id),
                            )
                        },
                    )
                } else {
                    add(
                        DialogItem(context.getString(R.string.go_to), Icons.Default.Info) {
                            itemOnClick.onClick(
                                item,
                                filterAndPosition,
                            )
                        },
                    )
                }
                if (item is SlimSceneData) {
                    if (item.resume_time != null && item.resume_time > 0 && !alwaysStartFromBeginning) {
                        add(
                            DialogItem(
                                context.getString(R.string.resume),
                                Icons.Default.PlayArrow,
                            ) {
                                nav.navigate(
                                    Destination.Playback(
                                        item.id,
                                        item.resume_position!!,
                                        PlaybackMode.Choose,
                                    ),
                                )
                            },
                        )
                        add(
                            DialogItem(
                                context.getString(R.string.restart),
                                Icons.Default.Refresh,
                            ) {
                                nav.navigate(
                                    Destination.Playback(
                                        item.id,
                                        0L,
                                        PlaybackMode.Choose,
                                    ),
                                )
                            },
                        )
                    } else {
                        add(
                            DialogItem(
                                context.getString(R.string.play_scene),
                                Icons.Default.PlayArrow,
                            ) {
                                nav.navigate(
                                    Destination.Playback(
                                        item.id,
                                        0L,
                                        PlaybackMode.Choose,
                                    ),
                                )
                            },
                        )
                    }
                }
                if (item is SlimSceneData && item.code.isNotNullOrBlank()) {
                    val sameCount = SameCodeCounts.get(item.code) ?: 0
                    if (sameCount >= 2) {
                        val code = item.code.trim()
                        add(
                            DialogItem("瀏覽同番號影片 ($sameCount)", Icons.Default.Search) {
                                nav.navigate(
                                    Destination.Filter(
                                        FilterArgs(
                                            dataType = DataType.SCENE,
                                            name = "番號：$code",
                                            objectFilter =
                                                SceneFilterType(
                                                    code =
                                                        Optional.present(
                                                            StringCriterionInput(
                                                                value = code,
                                                                modifier = CriterionModifier.EQUALS,
                                                            ),
                                                        ),
                                                ),
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                }
                if (subsItem != null && recentAction !is RecentSceneAction.SubsTag) {
                    add(subsItem)
                }
                if (subsSourceItem != null && recentAction !is RecentSceneAction.SubsSourceTag) {
                    add(subsSourceItem)
                }
                addTagItems.forEach { (tagName, dialogItem) ->
                    val isRecent =
                        recentAction is RecentSceneAction.AddTag && recentAction.tagName == tagName
                    if (dialogItem != null && !isRecent) {
                        add(dialogItem)
                    }
                }
                if (generateIdentifyItem != null && recentAction !is RecentSceneAction.GenerateAndIdentify) {
                    add(generateIdentifyItem)
                }
                if (item is SlimSceneData && readOnlyModeDisabled()) {
                    add(
                        DialogItem(
                            context.getString(R.string.add_group),
                            DataType.GROUP.iconStringId,
                        ) {
                            scope.launch(StashCoroutineExceptionHandler(autoToast = true)) {
                                val server = StashServer.requireCurrentServer()
                                val groups = findGroupsNotInScene(QueryEngine(server), item)
                                if (groups.isEmpty()) {
                                    showShort(context.getString(R.string.no_groups_to_add))
                                } else {
                                    onLongClick.invoke(
                                        DialogParams(
                                            fromLongClick = false,
                                            title = context.getString(R.string.add_group),
                                            items =
                                                groups.map { group ->
                                                    DialogItem(group.name) {
                                                        scope.launch(
                                                            StashCoroutineExceptionHandler(autoToast = true),
                                                        ) {
                                                            addSceneToGroup(
                                                                MutationEngine(server),
                                                                item,
                                                                group.id,
                                                                group.name,
                                                            )
                                                            RecentSceneActionStore.record(
                                                                RecentSceneAction.AddToGroup(
                                                                    group.id,
                                                                    group.name,
                                                                ),
                                                            )
                                                            showAddGroup(group)
                                                        }
                                                    }
                                                },
                                        ),
                                    )
                                }
                            }
                        },
                    )
                }
                if (removeRmGroupsItem != null && recentAction !is RecentSceneAction.RemoveFromRmGroups) {
                    add(removeRmGroupsItem)
                }
                if ((item is SlimSceneData || item is MarkerData) &&
                    filterAndPosition != null &&
                    filterAndPosition.position < MAX_PLAYLIST_SIZE // TODO
                ) {
                    add(
                        DialogItem(
                            context.getString(R.string.play_from_here),
                            Icons.Default.PlayArrow,
                        ) {
                            if (item is MarkerData) {
                                markerPlayAllOnClick.invoke(filterAndPosition)
                            } else {
                                nav.navigate(
                                    Destination.Playlist(
                                        filterAndPosition.filter,
                                        filterAndPosition.position,
                                        30.seconds.inWholeMilliseconds,
                                    ),
                                )
                            }
                        },
                    )
                }
                if (item is SlimSceneData && readOnlyModeDisabled()) {
                    add(
                        DialogItem("刪除影片", Icons.Default.Delete) {
                            onLongClick.invoke(
                                DialogParams(
                                    fromLongClick = false,
                                    title = "確定刪除「$title」？",
                                    // 預設焦點落在「刪除影片與檔案」
                                    initialFocusIndex = 1,
                                    items =
                                        listOf(
                                            DialogItem("取消") {},
                                            DialogItem("刪除影片與檔案", Icons.Default.Delete) {
                                                scope.launch(StashCoroutineExceptionHandler(autoToast = true)) {
                                                    val ok =
                                                        MutationEngine(StashServer.requireCurrentServer())
                                                            .deleteScene(
                                                                item.id,
                                                                deleteFiles = true,
                                                                deleteGenerated = true,
                                                            )
                                                    if (ok) {
                                                        DeletedScenes.markDeleted(item.id)
                                                        showShort("已刪除：$title")
                                                    } else {
                                                        showShort("刪除失敗")
                                                    }
                                                }
                                            },
                                            DialogItem("只從 Stash 移除（保留檔案）", Icons.Default.Delete) {
                                                scope.launch(StashCoroutineExceptionHandler(autoToast = true)) {
                                                    val ok =
                                                        MutationEngine(StashServer.requireCurrentServer())
                                                            .deleteScene(
                                                                item.id,
                                                                deleteFiles = false,
                                                                deleteGenerated = true,
                                                            )
                                                    if (ok) {
                                                        DeletedScenes.markDeleted(item.id)
                                                        showShort("已刪除：$title")
                                                    } else {
                                                        showShort("刪除失敗")
                                                    }
                                                }
                                            },
                                        ),
                                ),
                            )
                        },
                    )
                }
            }
        onLongClick.invoke(DialogParams(true, title, items))
    }
}

data class LongClickerAction<T>(
    @StringRes val title: Int,
    val filter: (T) -> Boolean,
    val action: (T, FilterAndPosition?) -> Unit,
) {
    val id: Int = idCounter.getAndIncrement()

    companion object {
        private val idCounter = AtomicInteger(0)
    }
}

data class LongClickPopup(
    val item: Any,
    val filterAndPosition: FilterAndPosition?,
    val actions: List<LongClickerAction<Any>>,
)

fun buildLongClickActionList(
    nav: NavigationManager,
    itemOnClicker: ItemOnClicker<Any>,
): List<LongClickerAction<Any>> =
    listOf(
        LongClickerAction<Any>(
            R.string.go_to,
            { true },
            { item, fp -> itemOnClicker.onClick(item, fp!!) },
        ),
        LongClickerAction<Any>(
            R.string.play_scene,
            { it is SlimSceneData && (it.resume_position == null || it.resume_position!! <= 0) },
            { item, _ ->
                item as SlimSceneData
                nav.navigate(Destination.Playback(item.id, 0L, PlaybackMode.Choose))
            },
        ),
        LongClickerAction<Any>(
            R.string.resume,
            { it is SlimSceneData && (it.resume_position != null && it.resume_position!! > 0) },
            { item, _ ->
                item as SlimSceneData
                nav.navigate(
                    Destination.Playback(
                        item.id,
                        item.resume_position ?: 0,
                        PlaybackMode.Choose,
                    ),
                )
            },
        ),
        LongClickerAction<Any>(
            R.string.restart,
            { it is SlimSceneData && (it.resume_position != null && it.resume_position!! > 0) },
            { item, _ ->
                item as SlimSceneData
                nav.navigate(Destination.Playback(item.id, 0L, PlaybackMode.Choose))
            },
        ),
    )


/** RM 標記 tag（卡片上會顯示紅色 RM 徽章） */
private const val RM_TAG = "0 RM"

/** 比對「RM」系列群組：待RM / RM / RM2 / RM3 等（不分大小寫） */
private val RM_GROUP_REGEX = Regex("^待?RM\\d*$", RegexOption.IGNORE_CASE)

private fun isRmGroupName(name: String) = RM_GROUP_REGEX.matches(name.trim())

/** Censored 標記 tag */
private const val CENSORED_TAG = "Censored"

/** 長按選單提供快速加入的 tag 清單；要再加新選項只要在這裡加名字 */
private val QUICK_ADD_TAGS = listOf(RM_TAG, CENSORED_TAG)

/** 字幕佇列 tag（lada-batch file-monitor 會掃描並排入 SUBGEN 字幕任務） */
private const val SUBS_PENDING_TAG = "subs:pending"

/** 影片自帶字幕 tag（有這個 tag 就不需要排入字幕佇列） */
private const val SUBS_SOURCE_TAG = "subs:source"

/** 已帶任何 subs:* tag（pending/done/failed）就不再顯示選項 */
private const val SUBS_TAG_PREFIX = "subs:"

/** 建立「加入 tag」的選單項目；scene 已經有這個 tag 就不顯示。 */
private fun buildAddTagItem(
    item: Any,
    tagName: String,
): DialogItem? {
    if (item !is SlimSceneData || !readOnlyModeDisabled()) return null
    if (item.tags.any { it.slimTagData.name.equals(tagName, ignoreCase = true) }) return null
    return DialogItem("加入 Tag ($tagName)", Icons.Default.Add) {
        addTagByName(item, tagName)
        RecentSceneActionStore.record(RecentSceneAction.AddTag(tagName))
    }
}

/** 幫 scene 加上指定名稱的 tag（tag 不存在會自動建立）。 */
private fun addTagByName(
    item: SlimSceneData,
    tagName: String,
) {
    updateSceneTags(
        item,
        failureMessage = "加入 $tagName 失敗",
        successMessage = "已加入 Tag：$tagName",
    ) { queryEngine, mutationEngine ->
        val tagId =
            queryEngine
                .findTags(FindFilterType(q = Optional.present(tagName)))
                .firstOrNull { it.name.equals(tagName, ignoreCase = true) }
                ?.id
                ?: mutationEngine.createTag(TagCreateInput(name = tagName))!!.id
        // item.tags can be stale (the grid is never refreshed after a mutation), so guard
        // against sending the same id twice
        (item.tags.map { it.slimTagData.id } + tagId).distinct()
    }
}

/**
 * 建立字幕佇列的選單項目：已在佇列中就顯示「移除」，還沒有任何 subs:* tag 才顯示「加入」，
 * 已經是其他 subs:* 狀態（例如 subs:done）則不顯示。回傳 null 代表不顯示這個選項。
 */
private fun buildSubsQueueItem(item: Any): DialogItem? {
    if (item !is SlimSceneData || !readOnlyModeDisabled()) return null
    val pendingTagId =
        SubsQueueOverrides.pendingTagId(
            item.id,
            item.tags
                .firstOrNull { it.slimTagData.name.equals(SUBS_PENDING_TAG, ignoreCase = true) }
                ?.slimTagData
                ?.id,
        )
    val hasOtherSubsTag =
        item.tags.any {
            it.slimTagData.name.startsWith(SUBS_TAG_PREFIX, ignoreCase = true) &&
                !it.slimTagData.name.equals(SUBS_PENDING_TAG, ignoreCase = true)
        }
    return when {
        pendingTagId != null ->
            DialogItem("移除字幕佇列 ($SUBS_PENDING_TAG)", Icons.Default.Delete) {
                removeSubsPendingTag(item, pendingTagId)
                RecentSceneActionStore.record(RecentSceneAction.SubsTag)
            }

        !hasOtherSubsTag ->
            DialogItem("加入字幕佇列 ($SUBS_PENDING_TAG)", Icons.Default.Add) {
                addSubsPendingTag(item)
                RecentSceneActionStore.record(RecentSceneAction.SubsTag)
            }

        else -> null
    }
}

/**
 * 建立「自帶字幕標記」的切換選單項目：scene 已有 subs:source 就顯示「移除」，否則顯示「加入」
 * （tag 不存在會自動建立）。
 */
private fun buildSubsSourceItem(item: Any): DialogItem? {
    if (item !is SlimSceneData || !readOnlyModeDisabled()) return null
    val existingTagId =
        item.tags
            .firstOrNull { it.slimTagData.name.equals(SUBS_SOURCE_TAG, ignoreCase = true) }
            ?.slimTagData
            ?.id
    return if (existingTagId != null) {
        DialogItem("移除自帶字幕標記 ($SUBS_SOURCE_TAG)", Icons.Default.Delete) {
            updateSceneTags(
                item,
                failureMessage = "移除 $SUBS_SOURCE_TAG 失敗",
                successMessage = "已移除 Tag：$SUBS_SOURCE_TAG",
                onSuccess = { RecentSceneActionStore.record(RecentSceneAction.SubsSourceTag) },
            ) { _, _ ->
                item.tags.map { it.slimTagData.id }.filterNot { it == existingTagId }
            }
        }
    } else {
        DialogItem("加入自帶字幕標記 ($SUBS_SOURCE_TAG)", Icons.Default.Add) {
            updateSceneTags(
                item,
                failureMessage = "加入 $SUBS_SOURCE_TAG 失敗",
                successMessage = "已加入 Tag：$SUBS_SOURCE_TAG",
                onSuccess = { RecentSceneActionStore.record(RecentSceneAction.SubsSourceTag) },
            ) { queryEngine, mutationEngine ->
                val tagId =
                    queryEngine
                        .findTags(FindFilterType(q = Optional.present(SUBS_SOURCE_TAG)))
                        .firstOrNull { it.name.equals(SUBS_SOURCE_TAG, ignoreCase = true) }
                        ?.id
                        ?: mutationEngine.createTag(TagCreateInput(name = SUBS_SOURCE_TAG))!!.id
                (item.tags.map { it.slimTagData.id } + tagId).distinct()
            }
        }
    }
}

/**
 * 建立「從 RM 群組移除」的選單項目：只有 scene 目前（含本次 session 的變更）屬於 待RM / RM / RM2 / RM3
 * 等 RM 系列群組時才顯示。群組 id 與名稱皆取自 [SceneGroupOverrides.effectiveGroups]，
 * 因此本次 session 新加入的群組也能正確辨識。
 */
private fun buildRemoveFromRmGroupsItem(item: Any): DialogItem? {
    if (item !is SlimSceneData || !readOnlyModeDisabled()) return null
    val rmGroups =
        SceneGroupOverrides
            .effectiveGroups(item)
            .filter { (_, name) -> isRmGroupName(name) }
            .entries
            .sortedBy { it.value }
    if (rmGroups.isEmpty()) return null
    val names = rmGroups.joinToString(", ") { it.value }
    return DialogItem("將此影片從已加入的群組 ($names) 中移除", Icons.Default.Delete) {
        removeFromRmGroups(item, rmGroups.map { it.key }, names)
    }
}

/** 將 scene 從指定的 RM 群組移除（保留其他群組），並顯示對應的 toast。 */
private fun removeFromRmGroups(
    item: SlimSceneData,
    groupIds: List<String>,
    names: String,
) {
    val context = StashApplication.getApplication()
    CoroutineScope(Dispatchers.IO).launch(
        StashCoroutineExceptionHandler(
            Toast.makeText(context, "從群組移除失敗", Toast.LENGTH_SHORT),
        ),
    ) {
        removeSceneFromGroups(MutationEngine(StashServer.requireCurrentServer()), item, groupIds)
        RecentSceneActionStore.record(RecentSceneAction.RemoveFromRmGroups)
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "已從群組移除：$names", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * 建立「產生預覽 + Scrape」的選單項目：對單一 scene 立即觸發伺服器的 Generate 與 Identify
 * （批次 scrape）工作，取代原本要先加入群組再手動於網頁版執行的流程。
 */
private fun buildGenerateIdentifyItem(item: Any): DialogItem? {
    if (item !is SlimSceneData || !readOnlyModeDisabled()) return null
    return DialogItem("產生預覽 + Scrape", Icons.Default.Refresh) {
        triggerGenerateAndIdentify(item)
    }
}

/** 對單一 scene 觸發 Generate 與 Identify 工作，並顯示對應的 toast。 */
private fun triggerGenerateAndIdentify(item: SlimSceneData) {
    val context = StashApplication.getApplication()
    CoroutineScope(Dispatchers.IO).launch(
        StashCoroutineExceptionHandler(
            Toast.makeText(context, "送出工作失敗", Toast.LENGTH_SHORT),
        ),
    ) {
        val server = StashServer.requireCurrentServer()
        val mutationEngine = MutationEngine(server)
        val queryEngine = QueryEngine(server)
        val isTs =
            item.files
                .firstOrNull()
                ?.videoFile
                ?.path
                ?.endsWith(".ts", ignoreCase = true) == true
        // .ts 檔伺服器無法產生可用的預覽：交給 remux_ts 外掛轉成 mp4（外掛轉完會自己重新產生預覽）。
        // 外掛不存在或呼叫失敗時退回一般的產生預覽流程，且不中斷後面的 Identify。
        var remuxed = false
        if (isTs) {
            remuxed =
                try {
                    mutationEngine.triggerRemuxTs(item.id).isNotNullOrBlank()
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Log.w("LongClicker", "remux_ts plugin task failed", ex)
                    false
                }
            if (!remuxed) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "找不到 remux 外掛，改為一般產生預覽", Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (!remuxed) {
            mutationEngine.triggerGenerate(listOf(item.id), overwrite = true)
        }
        val (sources, options) = queryEngine.getIdentifyDefaults()
        if (sources.isEmpty()) {
            withContext(Dispatchers.Main) {
                val message =
                    if (remuxed) {
                        "已送出 .ts 轉檔工作；Scrape 略過：伺服器未設定 identify 來源/stash-box"
                    } else {
                        "已送出產生預覽工作；Scrape 略過：伺服器未設定 identify 來源/stash-box"
                    }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
            return@launch
        }
        mutationEngine.triggerIdentify(listOf(item.id), sources, options)
        RecentSceneActionStore.record(RecentSceneAction.GenerateAndIdentify)
        withContext(Dispatchers.Main) {
            val message =
                if (remuxed) {
                    "已送出 .ts 轉檔 + Scrape；轉檔完成後會自動重新產生預覽"
                } else {
                    "已送出產生預覽與 Scrape 工作"
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}

/** 更新 scene 的 tag 列表，並在成功/失敗時顯示對應的 toast。 */
private fun updateSceneTags(
    item: SlimSceneData,
    failureMessage: String,
    successMessage: String,
    onSuccess: () -> Unit = {},
    newTagIds: suspend (QueryEngine, MutationEngine) -> List<String>,
) {
    val context = StashApplication.getApplication()
    CoroutineScope(Dispatchers.IO).launch(
        StashCoroutineExceptionHandler(
            Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT),
        ),
    ) {
        val server = StashServer.requireCurrentServer()
        val queryEngine = QueryEngine(server)
        val mutationEngine = MutationEngine(server)
        mutationEngine.setTagsOnScene(
            item.id,
            newTagIds(queryEngine, mutationEngine),
        )
        onSuccess()
        withContext(Dispatchers.Main) {
            Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
        }
    }
}

/** 幫 scene 加上 subs:pending tag（tag 不存在會自動建立）。 */
private fun addSubsPendingTag(item: SlimSceneData) {
    var addedTagId: String? = null
    updateSceneTags(
        item,
        failureMessage = "加入字幕佇列失敗",
        successMessage = "已加入字幕佇列：$SUBS_PENDING_TAG",
        onSuccess = { addedTagId?.let { SubsQueueOverrides.setAdded(item.id, it) } },
    ) { queryEngine, mutationEngine ->
        val tagId =
            queryEngine
                .findTags(FindFilterType(q = Optional.present(SUBS_PENDING_TAG)))
                .firstOrNull { it.name.equals(SUBS_PENDING_TAG, ignoreCase = true) }
                ?.id
                ?: mutationEngine.createTag(TagCreateInput(name = SUBS_PENDING_TAG))!!.id
        addedTagId = tagId
        // item.tags can be stale (the grid is never refreshed after a mutation), so guard
        // against sending the same id twice
        (item.tags.map { it.slimTagData.id } + tagId).distinct()
    }
}

/** 移除 scene 的 subs:pending tag。 */
private fun removeSubsPendingTag(
    item: SlimSceneData,
    tagId: String,
) {
    updateSceneTags(
        item,
        failureMessage = "移除字幕佇列失敗",
        successMessage = "已移出字幕佇列：$SUBS_PENDING_TAG",
        onSuccess = { SubsQueueOverrides.setRemoved(item.id) },
    ) { _, _ ->
        item.tags.map { it.slimTagData.id }.filterNot { it == tagId }
    }
}
