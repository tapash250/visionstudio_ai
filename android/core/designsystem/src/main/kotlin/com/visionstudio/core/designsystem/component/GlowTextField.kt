package com.visionstudio.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.tactileClickable
import com.visionstudio.core.designsystem.modifier.frostedGlass
import com.visionstudio.core.designsystem.modifier.glowingBorder
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioError
import com.visionstudio.core.designsystem.theme.StudioIndigoBright
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.designsystem.theme.StudioTextTertiary
import com.visionstudio.core.designsystem.theme.VisionStudioTheme

/**
 * Frosted input that lights up with an indigo→cyan glow on focus (rose on error).
 * [mono] switches to JetBrains Mono for URLs and IDs. [secret] masks the value, with an
 * eye toggle to reveal it.
 *
 * [wrap] lets a long value (a URL or a model ID) wrap onto up to [MAX_WRAPPED_LINES] lines, so
 * the whole value stays readable. A single-line field scrolls horizontally instead, which hides
 * the start of a value wider than the field (for example `black-forest-labs/…` showing as
 * `lack-forest-labs/…`). Line breaks are never accepted into a wrapped value.
 */
@Composable
fun GlowTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    supportingText: String? = null,
    isError: Boolean = false,
    mono: Boolean = false,
    secret: Boolean = false,
    wrap: Boolean = false,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    var revealed by rememberSaveable { mutableStateOf(false) }
    val glow = animateFloatAsState(if (focused || isError) 1f else 0f, label = "fieldGlow")
    val iconTint by animateColorAsState(
        targetValue = when {
            isError -> StudioError
            focused -> StudioCyan
            else -> StudioTextTertiary
        },
        label = "fieldIcon",
    )
    val glowBrush = remember(isError) {
        if (isError) SolidColor(StudioError) else Brush.horizontalGradient(listOf(StudioIndigoBright, StudioCyan))
    }
    val textStyle = (if (mono) VisionStudioTheme.typography.mono else MaterialTheme.typography.bodyLarge)
        .copy(color = if (enabled) MaterialTheme.colorScheme.onSurface else StudioTextTertiary)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label.uppercase(),
            style = VisionStudioTheme.typography.eyebrow,
            color = if (isError) StudioError else StudioTextSecondary,
            modifier = Modifier.padding(start = 4.dp),
        )
        val wraps = wrap && !secret
        BasicTextField(
            value = value,
            onValueChange = if (wraps) { next -> onValueChange(next.replace(LINE_BREAKS, "")) } else onValueChange,
            enabled = enabled,
            singleLine = !wraps,
            maxLines = if (wraps) MAX_WRAPPED_LINES else 1,
            textStyle = textStyle,
            cursorBrush = SolidColor(StudioCyan),
            interactionSource = interactionSource,
            visualTransformation = if (secret && !revealed) PasswordVisualTransformation('•') else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = false,
            ),
            keyboardActions = keyboardActions,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp)
                        .glowingBorder(
                            brush = glowBrush,
                            shape = StudioShapes.Control,
                            width = 1.dp,
                            glowRadius = 8.dp,
                            intensity = { glow.value },
                        )
                        .frostedGlass(shape = StudioShapes.Control)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (leadingIcon != null) {
                        Icon(leadingIcon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(placeholder, style = textStyle.copy(color = StudioTextTertiary), maxLines = 1)
                        }
                        innerField()
                    }
                    if (secret && value.isNotEmpty()) {
                        Icon(
                            imageVector = if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (revealed) "Hide value" else "Reveal value",
                            tint = iconTint,
                            modifier = Modifier
                                .size(22.dp)
                                .tactileClickable(haptic = HapticPattern.Tick, role = Role.Switch) { revealed = !revealed },
                        )
                    }
                }
            },
        )
        AnimatedVisibility(
            visible = supportingText != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Text(
                text = supportingText.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) StudioError else StudioTextTertiary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

private const val MAX_WRAPPED_LINES = 3
private val LINE_BREAKS = Regex("[\r\n]")
