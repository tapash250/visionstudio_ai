package com.visionstudio.core.designsystem.modifier

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * First-appearance entrance: the element rises 24 dp and fades in on a soft spring, delayed
 * by [index] × [stepMillis] so sibling cards cascade. Runs once per composition entry. The
 * progress is read in the layer phase only.
 */
fun Modifier.staggeredEntrance(index: Int, stepMillis: Long = STEP_MILLIS): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtLeast(0) * stepMillis)
        progress.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow))
    }
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * RISE.toPx()
    }
}

private const val STEP_MILLIS = 55L
private val RISE = 24.dp
