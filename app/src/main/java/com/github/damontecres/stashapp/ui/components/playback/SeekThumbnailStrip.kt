package com.github.damontecres.stashapp.ui.components.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.transformations
import com.github.damontecres.stashapp.ui.util.CoilPreviewTransformation
import com.github.damontecres.stashapp.ui.util.handleDPadKeyEvents
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A horizontal, focusable strip of sprite thumbnails shown while the seek bar is focused.
 * Left/right browses segments; center jumps playback to the selected segment.
 */
@Composable
fun SeekThumbnailStrip(
    imageLoader: ImageLoader,
    seekProgress: Float,
    duration: Long,
    currentPosition: Long,
    spriteData: List<SpriteData>,
    onJump: (positionMs: Long) -> Unit,
    onFocusChanged: (hasFocus: Boolean) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (spriteData.isEmpty()) {
        return
    }
    val listState = rememberLazyListState()
    var stripHasFocus by remember { mutableStateOf(false) }
    val positionMs =
        if (seekProgress >= 0 && duration > 0) {
            (seekProgress * duration).toLong()
        } else {
            currentPosition
        }
    // Follow the seek bar position unless the user is browsing the strip itself
    LaunchedEffect(positionMs, stripHasFocus) {
        if (!stripHasFocus) {
            val position = positionMs.milliseconds
            val index =
                spriteData
                    .indexOfFirst { position >= it.start && position < it.end }
                    .coerceAtLeast(0)
            listState.scrollToItem(index)
        }
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier =
            modifier.onFocusChanged {
                stripHasFocus = it.hasFocus
                onFocusChanged(it.hasFocus)
            },
    ) {
        items(spriteData.size, key = { it }) { index ->
            SeekThumbnailItem(
                imageLoader = imageLoader,
                sprite = spriteData[index],
                onJump = onJump,
                onInteraction = onInteraction,
            )
        }
    }
}

@Composable
private fun SeekThumbnailItem(
    imageLoader: ImageLoader,
    sprite: SpriteData,
    onJump: (positionMs: Long) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var isFocused by remember { mutableStateOf(false) }
    val height = 90.dp
    val width = height * (sprite.w.toFloat() / sprite.h)
    val heightPx = with(LocalDensity.current) { height.toPx().toInt() }
    val widthPx = with(LocalDensity.current) { width.toPx().toInt() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        AsyncImage(
            modifier =
                Modifier
                    .width(width)
                    .height(height)
                    .background(Color.Black)
                    .border(
                        width = if (isFocused) 3.dp else 1.dp,
                        color =
                            if (isFocused) {
                                MaterialTheme.colorScheme.border
                            } else {
                                MaterialTheme.colorScheme.border.copy(alpha = 0.4f)
                            },
                    ).onFocusChanged {
                        isFocused = it.isFocused
                        if (it.isFocused) {
                            onInteraction()
                        }
                    }.handleDPadKeyEvents(
                        onCenter = { onJump(sprite.start.inWholeMilliseconds) },
                    ).focusable(),
            model =
                ImageRequest
                    .Builder(context)
                    .data(sprite.url)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .transformations(
                        CoilPreviewTransformation(sprite, widthPx, heightPx),
                    ).build(),
            contentScale = ContentScale.None,
            imageLoader = imageLoader,
            contentDescription = null,
        )
        Text(
            text = sprite.start.inWholeSeconds.seconds.toString(),
            color =
                MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (isFocused) 1f else 0.6f,
                ),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
