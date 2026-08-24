package com.github.damontecres.stashapp.presenters

import android.content.Context
import android.graphics.Typeface
import android.widget.Toast
import com.apollographql.apollo.api.Optional
import com.github.damontecres.stashapp.R
import com.github.damontecres.stashapp.StashApplication
import com.github.damontecres.stashapp.api.fragment.SlimSceneData
import com.github.damontecres.stashapp.api.type.FindFilterType
import com.github.damontecres.stashapp.api.type.TagCreateInput
import com.github.damontecres.stashapp.data.DataType
import com.github.damontecres.stashapp.navigation.Destination
import com.github.damontecres.stashapp.playback.PlaybackMode
import com.github.damontecres.stashapp.ui.showAddGroup
import com.github.damontecres.stashapp.ui.showShort
import com.github.damontecres.stashapp.util.MutationEngine
import com.github.damontecres.stashapp.util.QueryEngine
import com.github.damontecres.stashapp.util.StashCoroutineExceptionHandler
import com.github.damontecres.stashapp.util.StashServer
import com.github.damontecres.stashapp.util.SubsQueueOverrides
import com.github.damontecres.stashapp.util.addSceneToGroup
import com.github.damontecres.stashapp.util.concatIfNotBlank
import com.github.damontecres.stashapp.util.findGroupsNotInScene
import com.github.damontecres.stashapp.util.isNotNullOrBlank
import com.github.damontecres.stashapp.util.readOnlyModeDisabled
import com.github.damontecres.stashapp.util.resolutionName
import com.github.damontecres.stashapp.util.resume_position
import com.github.damontecres.stashapp.util.titleOrFilename
import com.github.damontecres.stashapp.views.durationToString
import java.util.EnumMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScenePresenter(
    callback: LongClickCallBack<SlimSceneData>? = null,
) : StashPresenter<SlimSceneData>(callback) {
    override fun doOnBindViewHolder(
        cardView: StashImageCardView,
        item: SlimSceneData,
    ) {
        cardView.blackImageBackground = true
        cardView.titleText = item.titleOrFilename

        val details = mutableListOf<String?>()
        details.add(item.date)
        cardView.contentText = concatIfNotBlank(" - ", details)

        val dataTypeMap = EnumMap<DataType, Int>(DataType::class.java)
        dataTypeMap[DataType.TAG] = item.tags.size
        dataTypeMap[DataType.PERFORMER] = item.performers.size
        dataTypeMap[DataType.GROUP] = item.groups.size
        dataTypeMap[DataType.MARKER] = item.scene_markers.size
        dataTypeMap[DataType.GALLERY] = item.galleries.size

        cardView.setUpExtraRow(dataTypeMap, item.o_counter)

        cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)

        val videoFile = item.files.firstOrNull()?.videoFile
        if (videoFile != null) {
            val duration = durationToString(videoFile.duration)
            cardView.setTextOverlayText(StashImageCardView.OverlayPosition.BOTTOM_RIGHT, duration)

            val resText = cardView.getTextOverlay(StashImageCardView.OverlayPosition.BOTTOM_LEFT)
            resText.setTypeface(null, Typeface.BOLD)
            resText.text = videoFile.resolutionName()

            if (item.resume_time != null) {
                val percentWatched = item.resume_time / videoFile.duration
                cardView.setProgress(percentWatched)
            }
        }

        cardView.setRating100(item.rating100)
        if (item.studio != null) {
            if (StashServer.requireCurrentServer().serverPreferences.showStudioAsText) {
                cardView.setTextOverlayText(
                    StashImageCardView.OverlayPosition.TOP_RIGHT,
                    item.studio.name,
                )
            } else {
                cardView.setTopRightImage(
                    item.studio.image_path,
                    item.studio.name,
                )
            }
        }

        loadImage(cardView, item.paths.screenshot, defaultDrawable = R.drawable.default_scene)
        if (item.paths.preview.isNotNullOrBlank()) {
            cardView.videoUrl = item.paths.preview
        }
    }

    override fun getDefaultLongClickCallBack(): LongClickCallBack<SlimSceneData> =
        LongClickCallBack<SlimSceneData>()
            .addAction(PopUpItem.DEFAULT) { cardView, _ -> cardView.performClick() }
            .addAction(
                PopUpItem(2, "Play Scene"),
                { it.resume_position == null || it.resume_position!! <= 0 },
            ) { _, item ->
                StashApplication.navigationManager.navigate(
                    Destination.Playback(
                        item.id,
                        0L,
                        PlaybackMode.Choose,
                    ),
                )
            }.addAction(
                PopUpItem(3, "Resume Scene"),
                { it.resume_position != null && it.resume_position!! > 0 },
            ) { _, item ->
                StashApplication.navigationManager.navigate(
                    Destination.Playback(
                        item.id,
                        item.resume_position ?: 0L,
                        PlaybackMode.Choose,
                    ),
                )
            }.addAction(
                PopUpItem(4, "Restart Scene"),
                { it.resume_position != null && it.resume_position!! > 0 },
            ) { _, item ->
                StashApplication.navigationManager.navigate(
                    Destination.Playback(
                        item.id,
                        0L,
                        PlaybackMode.Choose,
                    ),
                )
            }.addAction(
                PopUpItem(5, "加入字幕佇列 ($SUBS_PENDING_TAG)"),
                { item -> readOnlyModeDisabled() && subsQueueAction(item) == SubsQueueAction.ADD },
            ) { cardView, item ->
                var addedTagId: String? = null
                updateSubsPendingTag(
                    cardView.context,
                    item.id,
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
                    // item.tags can be stale (the grid is never refreshed after a mutation), so
                    // guard against sending the same id twice
                    (item.tags.map { it.slimTagData.id } + tagId).distinct()
                }
            }.addAction(
                PopUpItem(5, "移除字幕佇列 ($SUBS_PENDING_TAG)"),
                { item -> readOnlyModeDisabled() && subsQueueAction(item) == SubsQueueAction.REMOVE },
            ) { cardView, item ->
                val tagId = subsQueuePendingTagId(item)!!
                updateSubsPendingTag(
                    cardView.context,
                    item.id,
                    failureMessage = "移除字幕佇列失敗",
                    successMessage = "已移出字幕佇列：$SUBS_PENDING_TAG",
                    onSuccess = { SubsQueueOverrides.setRemoved(item.id) },
                ) { _, _ ->
                    item.tags.map { it.slimTagData.id }.filterNot { it == tagId }
                }
            }.addAction(
                PopUpItem(6L, R.string.add_group),
                { readOnlyModeDisabled() },
            ) { cardView, item ->
                val context = StashApplication.getApplication()
                val scope =
                    CoroutineScope(Dispatchers.Main + StashCoroutineExceptionHandler(autoToast = true))
                scope.launch {
                    val server = StashServer.requireCurrentServer()
                    val groups = findGroupsNotInScene(QueryEngine(server), item)
                    if (groups.isEmpty()) {
                        showShort(context.getString(R.string.no_groups_to_add))
                    } else {
                        PopupOnLongClickListener(
                            groups.map { it.name },
                        ) { _, _, position, _ ->
                            val group = groups[position]
                            scope.launch {
                                addSceneToGroup(MutationEngine(server), item, group.id)
                                showAddGroup(group)
                            }
                        }.onLongClick(cardView)
                    }
                }
            }

    companion object {
        private const val TAG = "ScenePresenter"

        /** 字幕佇列 tag（lada-batch file-monitor 會掃描並排入 SUBGEN 任務） */
        private const val SUBS_PENDING_TAG = "subs:pending"

        /** 已帶任何 subs:* tag（pending/done/failed）就不再顯示選項 */
        private const val SUBS_TAG_PREFIX = "subs:"

        const val CARD_WIDTH = 345
        const val CARD_HEIGHT = 194

        /** Which subs:pending action (if any) the long-click menu should offer for this scene. */
        private enum class SubsQueueAction { ADD, REMOVE, NONE }

        /**
         * The scene's subs:pending tag id, preferring any override recorded this session over the
         * (possibly stale) tag id found on [item].
         */
        private fun subsQueuePendingTagId(item: SlimSceneData): String? =
            SubsQueueOverrides.pendingTagId(
                item.id,
                item.tags
                    .firstOrNull { it.slimTagData.name.equals(SUBS_PENDING_TAG, ignoreCase = true) }
                    ?.slimTagData
                    ?.id,
            )

        /**
         * Decides whether to offer 加入/移除 for this scene: already in the queue shows remove; no
         * other subs:* tag yet shows add; already in another subs:* state (e.g. subs:done) shows
         * neither. Shared by both filters so they cannot drift apart.
         */
        private fun subsQueueAction(item: SlimSceneData): SubsQueueAction {
            val pendingTagId = subsQueuePendingTagId(item)
            val hasOtherSubsTag =
                item.tags.any {
                    it.slimTagData.name.startsWith(SUBS_TAG_PREFIX, ignoreCase = true) &&
                        !it.slimTagData.name.equals(SUBS_PENDING_TAG, ignoreCase = true)
                }
            return when {
                pendingTagId != null -> SubsQueueAction.REMOVE
                !hasOtherSubsTag -> SubsQueueAction.ADD
                else -> SubsQueueAction.NONE
            }
        }

        /** 更新 scene 的 tag 列表，並在成功/失敗時顯示對應的 toast。 */
        private fun updateSubsPendingTag(
            context: Context,
            sceneId: String,
            failureMessage: String,
            successMessage: String,
            onSuccess: () -> Unit = {},
            newTagIds: suspend (QueryEngine, MutationEngine) -> List<String>,
        ) {
            CoroutineScope(Dispatchers.IO).launch(
                StashCoroutineExceptionHandler(
                    Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT),
                ),
            ) {
                val server = StashServer.requireCurrentServer()
                val queryEngine = QueryEngine(server)
                val mutationEngine = MutationEngine(server)
                mutationEngine.setTagsOnScene(
                    sceneId,
                    newTagIds(queryEngine, mutationEngine),
                )
                onSuccess()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
