package com.visionstudio.feature.gallery

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visionstudio.core.designsystem.component.AuroraBackground
import com.visionstudio.core.designsystem.component.GlassPanel
import com.visionstudio.core.designsystem.component.ScreenTitle
import com.visionstudio.core.designsystem.component.PrismChip
import com.visionstudio.core.designsystem.modifier.staggeredEntrance
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
import com.visionstudio.feature.gallery.model.GalleryFilter
import com.visionstudio.feature.gallery.ui.GalleryGrid
import com.visionstudio.feature.gallery.ui.InspectorSheet

@Composable
fun GalleryRoute(
    modifier: Modifier = Modifier,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val unavailable = stringResource(R.string.sheet_share_unavailable)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.Share -> try {
                    context.startActivity(event.chooser)
                } catch (none: ActivityNotFoundException) {
                    Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Android 8-9 only: saving to the shared folders needs a runtime permission. Newer versions never get here.
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onIntent(GalleryIntent.StoragePermissionResult(granted))
    }
    val needed = (state.export as? ExportState.NeedsPermission)?.permission
    LaunchedEffect(needed) { if (needed != null) storagePermission.launch(needed) }

    GalleryScreen(state = state, onIntent = viewModel::onIntent, modifier = modifier)
}

@Composable
fun GalleryScreen(
    state: GalleryUiState,
    onIntent: (GalleryIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shareTitle = stringResource(R.string.sheet_share_title)
    val gridState = rememberLazyStaggeredGridState()
    // A new filter is a different list; start at its top rather than at a meaningless offset.
    LaunchedEffect(state.filter) { gridState.scrollToItem(0) }

    AuroraBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Spacer(Modifier.height(4.dp))
            Header(state, Modifier.padding(horizontal = 16.dp).staggeredEntrance(0))
            Spacer(Modifier.height(14.dp))
            FilterRow(state, onIntent, Modifier.padding(horizontal = 16.dp).staggeredEntrance(1))
            Spacer(Modifier.height(10.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = state.contentKind(),
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "vaultContent",
                ) { kind ->
                    when (kind) {
                        ContentKind.Loading -> Unit
                        ContentKind.LibraryEmpty -> EmptyCard(
                            title = stringResource(R.string.vault_empty_title),
                            body = stringResource(R.string.vault_empty_body),
                        )
                        ContentKind.FilterEmpty -> EmptyCard(
                            title = stringResource(
                                if (state.filter == GalleryFilter.VIDEOS) R.string.vault_filter_empty_videos else R.string.vault_filter_empty_images,
                            ),
                            body = stringResource(R.string.vault_filter_empty_hint),
                        )
                        ContentKind.Grid -> GalleryGrid(
                            entries = state.entries,
                            // Previews pause while the inspector is open; it has its own player.
                            playbackEnabled = state.selected == null,
                            onOpen = { onIntent(GalleryIntent.Open(it.id)) },
                            state = gridState,
                            canLoadMore = !state.endReached && !state.isLoadingMore,
                            onLoadMore = { onIntent(GalleryIntent.LoadMore) },
                            paginationKey = state.filter,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    state.selected?.let { item ->
        InspectorSheet(
            item = item,
            export = state.export,
            onSave = { onIntent(GalleryIntent.SaveToGallery) },
            onShare = { onIntent(GalleryIntent.Share(shareTitle)) },
            onDismiss = { onIntent(GalleryIntent.Close) },
            onDelete = { onIntent(GalleryIntent.Delete(item.id)) },
        )
    }
}

private enum class ContentKind { Loading, LibraryEmpty, FilterEmpty, Grid }

private fun GalleryUiState.contentKind(): ContentKind = when {
    !loaded -> ContentKind.Loading
    isLibraryEmpty -> ContentKind.LibraryEmpty
    isFilterEmpty -> ContentKind.FilterEmpty
    else -> ContentKind.Grid
}

@Composable
private fun Header(state: GalleryUiState, modifier: Modifier = Modifier) {
    ScreenTitle(
        eyebrow = stringResource(R.string.vault_title),
        title = stringResource(R.string.vault_eyebrow),
        modifier = modifier,
    ) {
        if (state.totalCount > 0) {
            Text(
                text = stringResource(R.string.vault_count, state.totalCount),
                style = VisionStudioTheme.typography.monoSmall,
                color = StudioCyan,
            )
        }
    }
}

@Composable
private fun FilterRow(state: GalleryUiState, onIntent: (GalleryIntent) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GalleryFilter.entries.forEach { filter ->
            val count = when (filter) {
                GalleryFilter.ALL -> state.totalCount
                GalleryFilter.IMAGES -> state.imageCount
                GalleryFilter.VIDEOS -> state.videoCount
            }
            val name = stringResource(
                when (filter) {
                    GalleryFilter.ALL -> R.string.filter_all
                    GalleryFilter.IMAGES -> R.string.filter_images
                    GalleryFilter.VIDEOS -> R.string.filter_videos
                },
            )
            PrismChip(
                text = if (state.loaded && count > 0) "$name · $count" else name,
                selected = filter == state.filter,
                onClick = { onIntent(GalleryIntent.SetFilter(filter)) },
            )
        }
    }
}

@Composable
private fun EmptyCard(title: String, body: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        GlassPanel(modifier = Modifier.fillMaxWidth(), shape = StudioShapes.Panel) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(40.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                Text(body, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary, textAlign = TextAlign.Center)
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0B0E, heightDp = 760)
@Composable
private fun GalleryEmptyPreview() {
    VisionStudioTheme { GalleryScreen(state = GalleryUiState(loaded = true), onIntent = {}) }
}
