package com.visionstudio.feature.t2i

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.visionstudio.core.designsystem.component.AuroraBackground
import com.visionstudio.core.designsystem.component.BlurHashImage
import com.visionstudio.core.designsystem.component.EngineChip
import com.visionstudio.core.designsystem.component.ScreenTitle
import com.visionstudio.core.designsystem.component.SectionHeader
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.tactileClickable
import com.visionstudio.core.designsystem.modifier.frostedGlass
import com.visionstudio.core.designsystem.modifier.glowingBorder
import com.visionstudio.core.designsystem.modifier.staggeredEntrance
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioIndigoBright
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
import com.visionstudio.core.model.MediaItem
import com.visionstudio.feature.t2i.ui.ModernPromptDock
import com.visionstudio.feature.t2i.ui.RenderStage

@Composable
fun ImagineRoute(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: T2iViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ImagineScreen(state = state, onIntent = viewModel::onIntent, onOpenSettings = onOpenSettings, modifier = modifier)
}

@Composable
fun ImagineScreen(
    state: T2iUiState,
    onIntent: (T2iIntent) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AuroraBackground(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Header(engine = state.engine, onOpenSettings = onOpenSettings, modifier = Modifier.staggeredEntrance(0))

            RenderStage(
                phase = state.phase,
                ratio = state.stageRatio,
                engineConfigured = state.engine.configured,
                onOpenSettings = onOpenSettings,
                onRetry = { onIntent(T2iIntent.Generate) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .staggeredEntrance(1),
            )

            AnimatedVisibility(
                visible = state.recent.isNotEmpty() && !state.controls.advancedOpen,
                enter = fadeIn() + expandVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy)),
                exit = fadeOut() + shrinkVertically(),
            ) {
                RecentStrip(
                    items = state.recent,
                    selectedId = (state.phase as? StagePhase.Showing)?.item?.id,
                    enabled = !state.rendering,
                    onShow = { onIntent(T2iIntent.Show(it)) },
                    onRemix = { onIntent(T2iIntent.Remix(it)) },
                )
            }

            ModernPromptDock(
                state = state,
                onIntent = onIntent,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .staggeredEntrance(2),
            )
        }
    }
}

@Composable
private fun Header(engine: EngineBadge, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    ScreenTitle(
        eyebrow = stringResource(R.string.t2i_section),
        title = stringResource(R.string.t2i_eyebrow),
        modifier = modifier,
    ) {
        EngineChip(
            configured = engine.configured,
            label = engine.label,
            emptyLabel = stringResource(R.string.t2i_engine_none),
            onClick = onOpenSettings,
            onClickLabel = stringResource(R.string.t2i_open_settings),
        )
    }
}

@Composable
private fun RecentStrip(
    items: List<MediaItem>,
    selectedId: String?,
    enabled: Boolean,
    onShow: (MediaItem) -> Unit,
    onRemix: (MediaItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.t2i_recent))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)) {
            items(items, key = { it.id }) { item ->
                val selected = item.id == selectedId
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .glowingBorder(
                            brush = THUMB_GLOW,
                            shape = StudioShapes.Control,
                            width = if (selected) 1.5.dp else 0.dp,
                            glowRadius = 6.dp,
                            intensity = { if (selected) 1f else 0f },
                        )
                        .clip(StudioShapes.Control)
                        .tactileClickable(enabled = enabled, haptic = HapticPattern.Tick, onClickLabel = "Show", onClick = { onShow(item) })
                        .semantics { contentDescription = item.parameters.prompt },
                ) {
                    BlurHashImage(hash = item.blurHash, modifier = Modifier.fillMaxSize())
                    AsyncImage(
                        model = item.localUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (selected && enabled) {
                        Text(
                            text = "↺",
                            style = MaterialTheme.typography.labelLarge,
                            color = StudioCyan,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(3.dp)
                                .frostedGlass(shape = StudioShapes.Orb)
                                .tactileClickable(haptic = HapticPattern.Click, onClickLabel = "Remix") { onRemix(item) }
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

private val THUMB_GLOW = Brush.linearGradient(listOf(StudioIndigoBright, StudioCyan))

@Preview(showBackground = true, backgroundColor = 0xFF0A0B0E, heightDp = 860)
@Composable
private fun ImagineRenderingPreview() {
    VisionStudioTheme {
        ImagineScreen(
            state = T2iUiState(
                controls = PromptControls(prompt = "A glass koi drifting through neon rain, cinematic, 35mm", advancedOpen = true),
                engine = EngineBadge(configured = true, label = "flux/dev"),
                phase = StagePhase.Rendering(progress = 0.42f, message = "Rendering", queuePosition = null, preview = null),
            ),
            onIntent = {},
            onOpenSettings = {},
        )
    }
}
