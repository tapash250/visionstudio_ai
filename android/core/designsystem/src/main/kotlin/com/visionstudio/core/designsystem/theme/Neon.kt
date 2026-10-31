package com.visionstudio.core.designsystem.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Gradients of the neon HUD look. Brushes without explicit start/end span whatever they are
 * drawn into, so one instance fits any size.
 */
object NeonBrushes {
    /** Card and dock frames: cyan on the leading edge through blue to violet and magenta. */
    val Frame: Brush = Brush.linearGradient(listOf(StudioCyan, StudioBlue, StudioViolet, StudioMagenta))

    /** A quieter frame for nested controls and inputs. */
    val FrameSoft: Brush = Brush.linearGradient(
        listOf(StudioCyan.copy(alpha = 0.7f), StudioBlue.copy(alpha = 0.55f), StudioViolet.copy(alpha = 0.7f)),
    )

    /** Fill of a selected tab or primary button: violet into electric blue. */
    val Selected: Brush = Brush.horizontalGradient(listOf(Color(0xFF8A3BFF), Color(0xFF5A4DFF), Color(0xFF2F7BFF)))

    /** Oversized screen titles: white into lilac, violet and cyan. */
    val Title: Brush = Brush.linearGradient(listOf(Color.White, StudioLilac, StudioViolet, StudioCyan))

    /** Glyph gradient for highlighted icons. */
    val Glyph: Brush = Brush.linearGradient(listOf(StudioMagenta, StudioViolet, StudioCyan))

    /** Glyph gradient for resting navigation icons. */
    val GlyphCool: Brush = Brush.linearGradient(listOf(StudioCyan, StudioBlue))

    /** Panel fill: navy glass, a little lighter at the top. */
    val PanelFill: Brush = Brush.verticalGradient(listOf(Color(0xD0101A4A), Color(0xD8070C2C)))

    /** Brand word "Studio". */
    val Brand: Brush = Brush.horizontalGradient(listOf(StudioViolet, StudioMagenta))
}
