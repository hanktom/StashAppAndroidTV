package com.github.damontecres.stashapp.presenters

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
                { item ->
                    readOnlyModeDisabled() &&
                        item.tags.none {
                            it.slimTagData.name.startsWith(SUBS_TAG_PREFIX, ignoreCase = true)
                        }
                },
            ) { cardView, item ->
                val context = cardView.context
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
                            .makeText(
                                context,
                                "已加入字幕佇列：$SUBS_PENDING_TAG",
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
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
    }
}
