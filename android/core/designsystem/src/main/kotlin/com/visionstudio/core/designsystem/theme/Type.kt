package com.visionstudio.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.visionstudio.core.designsystem.R

/*
 * Both families ship as single variable fonts (OFL, see /licenses), so every weight comes from
 * one file and stays sharp at any size. Space Grotesk's quirky geometric terminals give the
 * studio its voice. JetBrains Mono keeps seeds, model IDs and URLs scannable.
 */
@OptIn(ExperimentalTextApi::class)
private fun variable(resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val SpaceGrotesk = FontFamily(
    variable(R.font.space_grotesk, FontWeight.Light),
    variable(R.font.space_grotesk, FontWeight.Normal),
    variable(R.font.space_grotesk, FontWeight.Medium),
    variable(R.font.space_grotesk, FontWeight.SemiBold),
    variable(R.font.space_grotesk, FontWeight.Bold),
)

val JetBrainsMono = FontFamily(
    variable(R.font.jetbrains_mono, FontWeight.Normal),
    variable(R.font.jetbrains_mono, FontWeight.Medium),
    variable(R.font.jetbrains_mono, FontWeight.SemiBold),
)

private val Display = SpaceGrotesk
private val Body = SpaceGrotesk
private val Mono = JetBrainsMono

val StudioMaterialTypography = Typography(
    displayLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-0.035).em),
    displayMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.03).em),
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.025).em),
    headlineLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.02).em),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.015).em),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em),
    titleLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp, letterSpacing = (-0.005).em),
    titleMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.01.em),
    labelMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.02.em),
    labelSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.04.em),
)

/** Studio-specific type tokens beyond the Material 3 scale. */
@Immutable
data class StudioTypography(
    /** Oversized screen titles; pair with the prism brush. */
    val hero: TextStyle,
    /** Prompt input text: slightly larger and airier than body. */
    val prompt: TextStyle,
    /** Seeds, model IDs, URLs, job IDs and other technical values. */
    val mono: TextStyle,
    val monoSmall: TextStyle,
    /** Uppercase section eyebrows ("ENGINE", "ASPECT RATIO"). */
    val eyebrow: TextStyle,
    /** Neon section headers ("PROTOCOL", "CONNECTION"). */
    val section: TextStyle,
    /** The "Vision Studio" word mark. */
    val brand: TextStyle,
    /** Large progress percentage readout during generation. */
    val progressNumeral: TextStyle,
)

val ObsidianStudioTypography = StudioTypography(
    hero = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic,
        fontSize = 54.sp,
        lineHeight = 60.sp,
        letterSpacing = (-0.03).em,
    ),
    prompt = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 26.sp),
    mono = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-0.01).em),
    monoSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
    eyebrow = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.12.em),
    section = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 18.sp, letterSpacing = 0.06.em),
    brand = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em),
    progressNumeral = TextStyle(fontFamily = Display, fontWeight = FontWeight.Light, fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-0.04).em),
)

val LocalStudioTypography = staticCompositionLocalOf { ObsidianStudioTypography }
