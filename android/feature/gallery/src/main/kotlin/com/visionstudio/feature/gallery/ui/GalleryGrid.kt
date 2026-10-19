package com.visionstudio.feature.gallery.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.visionstudio.core.designsystem.component.BlurHashImage
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.rememberStudioHaptics
import com.visionstudio.core.designsystem.modifier.shimmer
import com.visionstudio.core.designsystem.theme.StudioIndigoBright
import com.visionstudio.core.designsystem.theme.StudioOnyx
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import com.visionstudio.core.player.PreviewPlayerHost
import com.visionstudio.core.player.PreviewSurface
import com.visionstudio.core.player.rememberPreviewPlayerHost
import com.visionstudio.feature.gallery.R
import com.visionstudio.feature.gallery.model.DayBucket
import com.visionstudio.feature.gallery.model.GridEntry
import com.visionstudio.feature.gallery.model.VisibleVideo
import com.visionstudio.feature.gallery.model.formatDuration
import com.visionstudio.feature.gallery.model.pickActiveVideo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The Vault grid: two staggered columns whose tile heights follow each asset's real aspect
 * ratio, with a full-width header per day.
 *
 * Inline video previews share a single player and surface ([PreviewPlayerHost]). At most one
 * tile plays at a time: the video nearest the middle of the screen, and only once scrolling has
 * settled, since a `SurfaceView` inside a moving list tears. [playbackEnabled] lets the host
 * screen suspend all previews (for example while the inspector sheet is open).
 *
 * Pagination: when the end comes within reach, [onLoadMore] is called. The scroll position is
 * read off the composition path ([LazyStaggeredGridPaginationEffect]), so scrolling doesn't
 * recompose this grid; stable keys and content types keep existing tiles when a page appends.
 */
@OptIn(ExperimentalCoroutinesApi::class) // mapLatest: a newer pick must cancel a pending settle-delay
@Composable
internal fun GalleryGrid(
    entries: List<GridEntry>,
    playbackEnabled: Boolean,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    canLoadMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    paginationKey: Any? = null,
) {
    LazyStaggeredGridPaginationEffect(
        state = state,
        onLoadMore = onLoadMore,
        enabled = canLoadMore,
        resetKey = paginationKey,
    )

    val context = LocalContext.current
    val imageLoader = remember(context) { buildImageLoader(context) }
    val cornerPx = with(LocalDensity.current) { TILE_CORNER.toPx() }
    val host = rememberPreviewPlayerHost(cornerRadiusPx = cornerPx)

    val videos = remember(entries) {
        entries.mapNotNull { (it as? GridEntry.Tile)?.item }.filter { it.type == MediaType.VIDEO }.associateBy { it.id }
    }
    var activeVideoId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state, playbackEnabled, videos) {
        snapshotFlow {
            if (!playbackEnabled || state.isScrollInProgress || videos.isEmpty()) {
                null
            } else {
                val info = state.layoutInfo
                pickActiveVideo(
                    visible = info.visibleItemsInfo.mapNotNull { tile ->
                        val key = tile.key as? String ?: return@mapNotNull null
                        if (!key.startsWith(MEDIA_KEY_PREFIX)) return@mapNotNull null
                        val id = key.removePrefix(MEDIA_KEY_PREFIX)
                        if (id in videos) VisibleVideo(id, tile.offset.y, tile.offset.y + tile.size.height) else null
                    },
                    viewportStart = info.viewportStartOffset,
                    viewportEnd = info.viewportEndOffset,
                )
            }
        }
            .distinctUntilChanged()
            // Stop instantly when scrolling starts; wait for the list to settle before starting.
            .mapLatest { id -> id.also { if (it != null) delay(SETTLE_MILLIS) } }
            .collect { activeVideoId = it }
    }

    val activeUri = activeVideoId?.let { videos[it]?.localUri }
    LaunchedEffect(activeUri) {
        if (activeUri != null) host.play(activeUri) else host.clear()
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(COLUMNS),
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalItemSpacing = 10.dp,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = entries,
            key = { it.key },
            contentType = { entry ->
                when (entry) {
                    is GridEntry.Header -> HEADER_TYPE
                    is GridEntry.Tile -> TILE_TYPE
                    GridEntry.More -> MORE_TYPE
                }
            },
            span = { if (it is GridEntry.Tile) StaggeredGridItemSpan.SingleLane else StaggeredGridItemSpan.FullLine },
        ) { entry ->
            when (entry) {
                is GridEntry.Header -> DayHeader(entry, Modifier.animateItem())
                GridEntry.More -> MoreFooter()
                is GridEntry.Tile -> MediaTile(
                    item = entry.item,
                    active = entry.item.id == activeVideoId,
                    host = host,
                    imageLoader = imageLoader,
                    onOpen = onOpen,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun DayHeader(header: GridEntry.Header, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val label = when (val bucket = header.bucket) {
        DayBucket.Today -> stringResource(R.string.day_today)
        DayBucket.Yesterday -> stringResource(R.string.day_yesterday)
        is DayBucket.On -> remember(bucket.date, locale) {
            bucket.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        }
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label.uppercase(), style = VisionStudioTheme.typography.eyebrow, color = StudioTextSecondary)
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .drawWithCache {
                    val rule = Brush.horizontalGradient(listOf(StudioIndigoBright.copy(alpha = 0.5f), Color.Transparent))
                    onDrawBehind { drawRect(rule) }
                },
        )
        header.count?.let { count ->
            Text(stringResource(R.string.day_items, count), style = VisionStudioTheme.typography.monoSmall, color = StudioTextSecondary)
        }
    }
}

/**
 * Sits at the end of a grid with more to load. Local pages arrive in milliseconds, so instead of
 * a spinner that would only flicker, two placeholder tiles shimmer where the next row will be.
 */
@Composable
private fun MoreFooter() {
    val description = stringResource(R.string.vault_loading_more)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(COLUMNS) {
            Box(
                Modifier
                    .weight(1f)
                    .height(MORE_PLACEHOLDER_HEIGHT)
                    .clip(StudioShapes.Card)
                    .drawWithCache {
                        val fill = Brush.verticalGradient(listOf(StudioOnyx.copy(alpha = 0.55f), StudioOnyx.copy(alpha = 0.25f)))
                        onDrawBehind { drawRect(fill) }
                    }
                    .shimmer(),
            )
        }
    }
}

@Composable
private fun MediaTile(
    item: MediaItem,
    active: Boolean,
    host: PreviewPlayerHost,
    imageLoader: ImageLoader,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberStudioHaptics()
    val isVideo = item.type == MediaType.VIDEO
    val context = LocalContext.current
    val request = remember(item.localUri) {
        ImageRequest.Builder(context)
            .data(item.localUri)
            .allowHardware(true) // decode straight to GPU memory; tiles are only ever drawn, never read back
            .crossfade(true)
            .build()
    }
    val description = if (isVideo) {
        stringResource(R.string.tile_video_description, formatDuration(item.durationMillis ?: 0), item.parameters.prompt)
    } else {
        stringResource(R.string.tile_image_description, item.parameters.prompt)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio((item.width.toFloat() / item.height).coerceIn(MIN_TILE_RATIO, MAX_TILE_RATIO))
            .clip(StudioShapes.Card)
            .semantics { contentDescription = description }
            .clickable(onClickLabel = null) {
                haptics.perform(HapticPattern.Click)
                onOpen(item)
            },
    ) {
        // Layer order matters for video: a SurfaceView punches through everything drawn *before* it.
        BlurHashImage(hash = item.blurHash, modifier = Modifier.fillMaxSize())
        AsyncImage(
            model = request,
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (isVideo && active) PreviewSurface(host = host, modifier = Modifier.fillMaxSize())

        if (isVideo) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val shade = Brush.verticalGradient(listOf(Color.Transparent, StudioOnyx.copy(alpha = 0.65f)), startY = size.height * 0.55f)
                        onDrawBehind { drawRect(shade) }
                    },
            )
            if (!active) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(34.dp),
                )
            }
            Text(
                text = formatDuration(item.durationMillis ?: 0),
                style = VisionStudioTheme.typography.monoSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
        }
    }
}

/** An image loader that can also pull a poster frame out of a video file. */
private fun buildImageLoader(context: Context): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(VideoFrameDecoder.Factory()) }
        .build()

internal const val MEDIA_KEY_PREFIX = "media-"
private const val HEADER_TYPE = "header"
private const val TILE_TYPE = "tile"
private const val MORE_TYPE = "more"
private val MORE_PLACEHOLDER_HEIGHT = 120.dp
private const val COLUMNS = 2
private const val SETTLE_MILLIS = 250L
private const val MIN_TILE_RATIO = 0.55f
private const val MAX_TILE_RATIO = 1.9f
private val TILE_CORNER = 16.dp
