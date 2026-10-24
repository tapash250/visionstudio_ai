package com.visionstudio.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Tokens that Material 3's ColorScheme has no slot for: glass, glow and gradient accents. */
@Immutable
data class StudioColors(
    val background: Color,
    val glassFill: Color,
    val glassFillStrong: Color,
    val glassStroke: Color,
    val glassHighlight: Color,
    val accentGradient: Brush,
    val glowBorder: Brush,
    val subtleBorder: Brush,
    val shimmerBase: Color,
    val shimmerHighlight: Color,
    val success: Color,
    val warning: Color,
    val scrim: Color,
)

val ObsidianStudioColors = StudioColors(
    background = StudioOnyx,
    glassFill = StudioGlassFill,
    glassFillStrong = StudioGlassFillStrong,
    glassStroke = StudioGlassStroke,
    glassHighlight = StudioGlassHighlight,
    accentGradient = Brush.linearGradient(listOf(StudioIndigo, StudioViolet, StudioCyan)),
    glowBorder = Brush.linearGradient(listOf(StudioIndigoBright, StudioCyan)),
    subtleBorder = Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x0AFFFFFF))),
    shimmerBase = StudioGraphite,
    shimmerHighlight = StudioSteel,
    success = StudioSuccess,
    warning = StudioWarning,
    scrim = StudioScrim,
)

val LocalStudioColors = staticCompositionLocalOf { ObsidianStudioColors }
