package com.github.damontecres.stashapp.ui.components

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import com.apollographql.apollo.api.Optional
import com.github.damontecres.stashapp.R
import com.github.damontecres.stashapp.StashApplication
import com.github.damontecres.stashapp.api.fragment.MarkerData
import com.github.damontecres.stashapp.api.fragment.SlimSceneData
import com.github.damontecres.stashapp.api.fragment.StashData
import com.github.damontecres.stashapp.api.type.FindFilterType
import com.github.damontecres.stashapp.api.type.TagCreateInput
import com.github.damontecres.stashapp.data.DataType
import com.github.damontecres.stashapp.filter.extractTitle
import com.github.damontecres.stashapp.navigation.Destination
import com.github.damontecres.stashapp.navigation.FilterAndPosition
import com.github.damontecres.stashapp.navigation.NavigationManager
import com.github.damontecres.stashapp.playback.PlaybackMode
import com.github.damontecres.stashapp.ui.pages.DialogParams
import com.github.damontecres.stashapp.ui.pages.MAX_PLAYLIST_SIZE
import com.github.damontecres.stashapp.ui.showAddGroup
import com.github.damontecres.stashapp.ui.showShort
import com.github.damontecres.stashapp.util.MutationEngine
import com.github.damontecres.stashapp.util.QueryEngine
import com.github.damontecres.stashapp.util.StashCoroutineExceptionHandler
import com.github.damontecres.stashapp.util.StashServer
import com.github.damontecres.stashapp.util.addSceneToGroup
import com.github.damontecres.stashapp.util.findGroupsNotInScene
import com.github.damontecres.stashapp.util.readOnlyModeDisabled
import com.github.damontecres.stashapp.util.resume_position
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
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
        val context = StashApplication.getApplication()
        val title = extractTitle(item) ?: ""
        val items =
            buildList {
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
                if (item is SlimSceneData &&
                    readOnlyModeDisabled() &&
                    item.tags.none {
                        it.slimTagData.name.startsWith(SUBS_TAG_PREFIX, ignoreCase = true)
                    }
                ) {
                    add(
                        DialogItem(
                            "加入字幕佇列 ($SUBS_PENDING_TAG)",
                            Icons.Default.Add,
                        ) {
                            addSubsPendingTag(item)
                        },
                    )
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


/** 字幕佇列 tag（lada-batch file-monitor 會掃描並排入 SUBGEN 字幕任務） */
private const val SUBS_PENDING_TAG = "subs:pending"

/** 已帶任何 subs:* tag（pending/done/failed）就不再顯示選項 */
private const val SUBS_TAG_PREFIX = "subs:"

/** 幫 scene 加上 subs:pending tag（tag 不存在會自動建立）。 */
private fun addSubsPendingTag(item: SlimSceneData) {
    val context = StashApplication.getApplication()
    CoroutineScope(Dispatchers.IO).launch(
        StashCoroutineExceptionHandler(
            Toast.makeText(context, "加入字幕佇列失敗", Toast.LENGTH_SHORT),
        ),
    ) {
        val server = StashServer.requireCurrentServer()
        val queryEngine = QueryEngine(server)
        val mutationEngine = MutationEngine(server)
        val tagId =
            queryEngine
                .findTags(FindFilterType(q = Optional.present(SUBS_PENDING_TAG)))
                .firstOrNull { it.name.equals(SUBS_PENDING_TAG, ignoreCase = true) }
                ?.id
                ?: mutationEngine.createTag(TagCreateInput(name = SUBS_PENDING_TAG))!!.id
        mutationEngine.setTagsOnScene(
            item.id,
            item.tags.map { it.slimTagData.id } + tagId,
        )
        withContext(Dispatchers.Main) {
            Toast
                .makeText(context, "已加入字幕佇列：$SUBS_PENDING_TAG", Toast.LENGTH_SHORT)
                .show()
        }
    }
}
