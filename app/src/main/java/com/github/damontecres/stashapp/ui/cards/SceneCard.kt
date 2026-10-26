package com.github.damontecres.stashapp.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.stashapp.R
import com.github.damontecres.stashapp.api.fragment.SlimSceneData
import com.github.damontecres.stashapp.data.DataType
import com.github.damontecres.stashapp.navigation.FilterAndPosition
import com.github.damontecres.stashapp.presenters.ScenePresenter
import com.github.damontecres.stashapp.ui.ComposeUiConfig
import com.github.damontecres.stashapp.ui.components.LongClicker
import com.github.damontecres.stashapp.ui.enableMarquee
import com.github.damontecres.stashapp.util.DeletedScenes
import com.github.damontecres.stashapp.util.SameCodeCounts
import com.github.damontecres.stashapp.util.isNotNullOrBlank
import com.github.damontecres.stashapp.util.resolutionName
import com.github.damontecres.stashapp.util.titleOrFilename
import com.github.damontecres.stashapp.views.durationToString
import java.util.EnumMap

@Composable
private fun CardBadge(
    text: String,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = textColor,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        modifier =
            modifier
                .background(backgroundColor, RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun SceneCard(
    uiConfig: ComposeUiConfig,
    item: SlimSceneData?,
    onClick: (() -> Unit),
    longClicker: LongClicker<Any>,
    getFilterAndPosition: ((item: Any) -> FilterAndPosition)?,
    modifier: Modifier = Modifier,
    cardContext: CardContext.SceneCardContext? = null,
) {
    val dataTypeMap = EnumMap<DataType, Int>(DataType::class.java)
    item?.let {
        dataTypeMap[DataType.TAG] = item.tags.size
        dataTypeMap[DataType.PERFORMER] = item.performers.size
        dataTypeMap[DataType.GROUP] = item.groups.size
        dataTypeMap[DataType.MARKER] = item.scene_markers.size
        dataTypeMap[DataType.GALLERY] = item.galleries.size
    }

    RootCard(
        item = item,
        modifier =
            modifier
                .padding(0.dp),
        contentPadding = PaddingValues(0.dp),
        onClick = onClick,
        longClicker = longClicker,
        getFilterAndPosition = getFilterAndPosition,
        uiConfig = uiConfig,
        imageWidth = ScenePresenter.CARD_WIDTH.dp / 2,
        imageHeight = ScenePresenter.CARD_HEIGHT.dp / 2,
        imageUrl = item?.paths?.screenshot,
        defaultImageDrawableRes = R.drawable.default_scene,
        videoUrl = item?.paths?.preview,
        title = AnnotatedString(item?.titleOrFilename ?: ""),
        subtitle = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(item?.date ?: "", maxLines = 1)
                    val code = item?.code
                    if (code.isNotNullOrBlank()) {
                        Text(
                            text = code,
                            style =
                                LocalTextStyle.current.let {
                                    it.copy(fontSize = it.fontSize * 1.15f, fontWeight = FontWeight.SemiBold)
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                cardContext?.let {
                    val index =
                        item?.groups?.firstOrNull { cardContext.sceneInGroupId == it.group.id }?.scene_index
                    if (index != null) {
                        Text(
                            text = stringResource(R.string.stashapp_scene) + " #$index",
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        description = {
            IconRowText(
                sfwMode = uiConfig.sfwMode,
                dataTypeMap,
                item?.o_counter ?: -1,
                Modifier
                    .enableMarquee(it)
                    .align(Alignment.Center),
            )
        },
        imageOverlay = {
            ImageOverlay(uiConfig.ratingAsStars, rating100 = item?.rating100) {
                val videoFile = item?.files?.firstOrNull()?.videoFile
                if (videoFile != null) {
                    val duration = durationToString(videoFile.duration)
                    Text(
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp),
                        text = duration,
                        style = MaterialTheme.typography.bodyMedium.copy(shadow = OVERLAY_TEXT_SHADOW),
                    )
                    Text(
                        modifier =
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(4.dp),
                        style =
                            MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                shadow = OVERLAY_TEXT_SHADOW,
                            ),
                        text = videoFile.resolutionName().toString(),
                    )
                    if (item.resume_time != null && uiConfig.showCardProgress) {
                        val percentWatched = item.resume_time / videoFile.duration
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .background(
                                        MaterialTheme.colorScheme.tertiary,
                                    ).clip(RectangleShape)
                                    .height(4.dp)
                                    .width((ScenePresenter.CARD_WIDTH * percentWatched).dp / 2),
                        )
                    }
                }
                val hasRmTag =
                    item?.tags?.any { it.slimTagData.name.equals("0 RM", ignoreCase = true) } == true
                val hasSubsDone =
                    item?.tags?.any { it.slimTagData.name.equals("subs:done", ignoreCase = true) } == true
                val hasSubsSource =
                    item?.tags?.any { it.slimTagData.name.equals("subs:source", ignoreCase = true) } == true
                val isDeleted = item != null && DeletedScenes.isDeleted(item.id)
                if (isDeleted || hasRmTag || hasSubsDone || hasSubsSource) {
                    Row(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (isDeleted) {
                            CardBadge(
                                text = "已刪除",
                                backgroundColor = Color(0xFF424242),
                                textColor = Color.White,
                            )
                        }
                        if (hasRmTag) {
                            CardBadge(
                                text = "RM",
                                backgroundColor = Color(0xFFD32F2F),
                                textColor = Color.White,
                            )
                        }
                        if (hasSubsDone) {
                            CardBadge(
                                text = "字幕",
                                backgroundColor = Color(0xFFFFC107),
                                textColor = Color.Black,
                            )
                        }
                        if (hasSubsSource) {
                            CardBadge(
                                text = "自帶字幕",
                                backgroundColor = Color(0xFF2E7D32),
                                textColor = Color.White,
                            )
                        }
                    }
                }
                // 同番號影片數量徽章（≥2 才顯示），位於縮圖右上角
                val code = item?.code
                if (code.isNotNullOrBlank()) {
                    LaunchedEffect(code) { SameCodeCounts.request(code) }
                }
                val sameCodeCount = if (code.isNotNullOrBlank()) SameCodeCounts.get(code) ?: 0 else 0
                val showCountBadge = sameCodeCount >= 2
                if (showCountBadge) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(22.dp)
                                .background(Color(0xFFE91E63), CircleShape)
                                .border(1.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = sameCodeCount.toString(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                }
                // 徽章顯示時，廠商 logo/文字往左移避免重疊
                val studioEndPadding = if (showCountBadge) 28.dp else 4.dp
                if (item?.studio != null) {
                    val imageUrl = item.studio.image_path
                    if (!uiConfig.showStudioAsText &&
                        imageUrl.isNotNullOrBlank() &&
                        !imageUrl.contains(
                            "default=true",
                        )
                    ) {
                        AsyncImage(
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 4.dp, bottom = 4.dp, start = 4.dp, end = studioEndPadding)
                                    .fillMaxWidth(.4f),
                            model = imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text(
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 4.dp, bottom = 4.dp, start = 4.dp, end = studioEndPadding),
                            style = MaterialTheme.typography.bodyMedium,
                            text = item.studio.name,
                        )
                    }
                }
            }
        },
    )
}

/**
 * 卡片縮圖上的白色文字（時長、解析度）在淺色背景會看不清楚，加一層不顯眼的深色陰影提升對比。
 */
private val OVERLAY_TEXT_SHADOW =
    Shadow(
        color = Color.Black.copy(alpha = 0.85f),
        offset = Offset(1f, 1f),
        blurRadius = 4f,
    )
