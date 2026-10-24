package com.visionstudio.core.designsystem.haptics

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import com.visionstudio.core.designsystem.theme.StudioCyan

/**
 * Clickable with a spring "press-in" scale and a haptic pulse on activation.
 *
 * The scale is read inside [graphicsLayer], so the press animation runs entirely in the
 * draw/layer phase without recomposing the caller.
 */
fun Modifier.tactileClickable(
    enabled: Boolean = true,
    haptic: HapticPattern = HapticPattern.Click,
    pressedScale: Float = DEFAULT_PRESSED_SCALE,
    rippleColor: Color = StudioCyan,
    onClickLabel: String? = null,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier = composed {
    val haptics = rememberStudioHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "tactileScale",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = ripple(color = rippleColor),
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
        ) {
            haptics.perform(haptic)
            onClick()
        }
}

private const val DEFAULT_PRESSED_SCALE = 0.95f
