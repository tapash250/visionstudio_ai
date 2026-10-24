package com.visionstudio.feature.imageedit.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.visionstudio.core.designsystem.haptics.rememberStudioHaptics
import com.visionstudio.core.designsystem.modifier.frostedGlass
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioIndigoBright
import com.visionstudio.core.designsystem.theme.StudioOnyx
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioViolet
import com.visionstudio.core.designsystem.theme.VisionStudioTheme

/**
 * Before/after reveal. On first show it wipes from the original to your edit on a spring, then
 * the divider can be dragged anywhere (or tapped to jump). The split position is a plain float
 * state read only in draw lambdas, so dragging never recomposes.
 */
@Composable
fun CompareSlider(
    before: ImageBitmap,
    afterModel: Any,
    beforeLabel: String,
    afterLabel: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberStudioHaptics()
    var split by remember { mutableFloatStateOf(1f) }
    var touched by remember { mutableStateOf(false) }

    LaunchedEffect(afterModel) {
        touched = false
        animate(
            initialValue = 1f,
            targetValue = REST_SPLIT,
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessVeryLow),
        ) { value, _ -> if (!touched) split = value }
    }

    Box(
        modifier = modifier
            .aspectRatio(before.width.toFloat() / before.height.toFloat())
            .clip(StudioShapes.Card)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(1f - split, 0f..1f)
                setProgress { target ->
                    touched = true
                    split = 1f - target.coerceIn(0f, 1f)
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    touched = true
                    haptics.gestureStart()
                    split = (down.position.x / size.width).coerceIn(0f, 1f)
                    drag(down.id) { change ->
                        split = (change.position.x / size.width).coerceIn(0f, 1f)
                        change.consume()
                    }
                    haptics.gestureEnd()
                }
            },
    ) {
        AsyncImage(
            model = afterModel,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Image(
            bitmap = before,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(left = 0f, top = 0f, right = size.width * split, bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                },
        )

        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val line = Brush.verticalGradient(listOf(Color.Transparent, Color.White, Color.White, Color.Transparent))
                    val lineWidth = 2.dp.toPx()
                    val handleRadius = 16.dp.toPx()
                    val haloRadius = handleRadius * 1.7f
                    val glow = Brush.radialGradient(listOf(StudioCyan.copy(alpha = 0.55f), Color.Transparent), radius = haloRadius)
                    val chevron = 5.dp.toPx()
                    val gap = 3.dp.toPx()
                    val strokeWidth = 2.dp.toPx()
                    val lineSize = Size(lineWidth, size.height)
                    onDrawBehind {
                        val x = size.width * split
                        val cy = size.height / 2f
                        drawRect(line, topLeft = Offset(x - lineWidth / 2f, 0f), size = lineSize)
                        drawCircle(glow, haloRadius, Offset(x, cy))
                        drawCircle(Color.White, handleRadius, Offset(x, cy))
                        drawCircle(StudioOnyx, handleRadius - strokeWidth, Offset(x, cy))
                        // Left chevron "<" and right chevron ">".
                        drawLine(StudioIndigoBright, Offset(x - gap, cy - chevron), Offset(x - gap - chevron, cy), strokeWidth, StrokeCap.Round)
                        drawLine(StudioIndigoBright, Offset(x - gap - chevron, cy), Offset(x - gap, cy + chevron), strokeWidth, StrokeCap.Round)
                        drawLine(StudioViolet, Offset(x + gap, cy - chevron), Offset(x + gap + chevron, cy), strokeWidth, StrokeCap.Round)
                        drawLine(StudioViolet, Offset(x + gap + chevron, cy), Offset(x + gap, cy + chevron), strokeWidth, StrokeCap.Round)
                    }
                },
        )

        CornerLabel(beforeLabel, Modifier.align(Alignment.TopStart))
        CornerLabel(afterLabel, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun CornerLabel(text: String, modifier: Modifier) {
    Text(
        text = text.uppercase(),
        style = VisionStudioTheme.typography.eyebrow,
        color = Color.White,
        modifier = modifier
            .padding(10.dp)
            .frostedGlass(shape = StudioShapes.Chip, fill = StudioOnyx.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private const val REST_SPLIT = 0.5f
