package com.visionstudio.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

val ObsidianColorScheme: ColorScheme = darkColorScheme(
    primary = StudioIndigo,
    onPrimary = StudioOnAccent,
    primaryContainer = StudioIndigoDeep,
    onPrimaryContainer = StudioTextPrimary,
    inversePrimary = StudioIndigoDeep,
    secondary = StudioCyan,
    onSecondary = StudioOnyx,
    secondaryContainer = StudioCyanDeep,
    onSecondaryContainer = StudioTextPrimary,
    tertiary = StudioViolet,
    onTertiary = StudioOnAccent,
    tertiaryContainer = Color(0xFF581C87),
    onTertiaryContainer = StudioTextPrimary,
    background = StudioOnyx,
    onBackground = StudioTextPrimary,
    surface = StudioOnyx,
    onSurface = StudioTextPrimary,
    surfaceVariant = StudioGraphite,
    onSurfaceVariant = StudioTextSecondary,
    surfaceTint = StudioIndigo,
    inverseSurface = StudioTextPrimary,
    inverseOnSurface = StudioOnyx,
    error = StudioError,
    onError = StudioOnAccent,
    errorContainer = StudioErrorContainer,
    onErrorContainer = Color(0xFFFFE4E6),
    outline = StudioSteel,
    outlineVariant = StudioSlate,
    scrim = StudioOnyx,
    surfaceBright = StudioSlate,
    surfaceDim = StudioOnyx,
    surfaceContainerLowest = StudioOnyx,
    surfaceContainerLow = Color(0xFF0F1115),
    surfaceContainer = StudioCharcoal,
    surfaceContainerHigh = StudioGraphite,
    surfaceContainerHighest = StudioSlate,
)

/**
 * VisionStudio's Obsidian Dark theme. The app is dark-only by design: generated media is
 * evaluated against a neutral near-black canvas.
 */
@Composable
fun VisionStudioTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalStudioColors provides ObsidianStudioColors,
        LocalStudioTypography provides ObsidianStudioTypography,
    ) {
        MaterialTheme(
            colorScheme = ObsidianColorScheme,
            typography = StudioMaterialTypography,
            shapes = StudioMaterialShapes,
            content = content,
        )
    }
}

/** Accessors for studio tokens, mirroring `MaterialTheme.colorScheme` ergonomics. */
object VisionStudioTheme {
    val colors: StudioColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioColors.current

    val typography: StudioTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioTypography.current
}
