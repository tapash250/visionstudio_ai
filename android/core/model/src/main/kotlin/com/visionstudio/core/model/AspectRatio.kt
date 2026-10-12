package com.visionstudio.core.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Output frame ratios supported across the creative studios.
 *
 * [widthUnits]/[heightUnits] are the reduced ratio terms; concrete pixel sizes are derived via
 * [dimensionsFor] so every provider receives latent-aligned dimensions.
 */
@Serializable
enum class AspectRatio(
    val widthUnits: Int,
    val heightUnits: Int,
    val label: String,
) {
    SQUARE(1, 1, "1:1"),
    PORTRAIT_4_5(4, 5, "4:5"),
    PORTRAIT_3_4(3, 4, "3:4"),
    LANDSCAPE_4_3(4, 3, "4:3"),
    PORTRAIT_9_16(9, 16, "9:16"),
    LANDSCAPE_16_9(16, 9, "16:9"),
    CINEMATIC_21_9(21, 9, "21:9"),
    ;

    /** Width divided by height. */
    val ratio: Float get() = widthUnits.toFloat() / heightUnits.toFloat()

    val isPortrait: Boolean get() = heightUnits > widthUnits
    val isLandscape: Boolean get() = widthUnits > heightUnits

    /**
     * Pixel dimensions whose longest edge is [longEdge], with both sides snapped to a multiple
     * of [alignment] (diffusion models operate on 8px latent blocks; many require 64).
     */
    fun dimensionsFor(longEdge: Int = DEFAULT_LONG_EDGE, alignment: Int = DEFAULT_ALIGNMENT): Dimensions {
        require(longEdge > 0) { "longEdge must be positive, was $longEdge" }
        require(alignment > 0) { "alignment must be positive, was $alignment" }

        val (rawWidth, rawHeight) = if (widthUnits >= heightUnits) {
            longEdge.toFloat() to longEdge / ratio
        } else {
            longEdge * ratio to longEdge.toFloat()
        }
        return Dimensions(
            width = rawWidth.snapTo(alignment),
            height = rawHeight.snapTo(alignment),
        )
    }

    private fun Float.snapTo(alignment: Int): Int =
        ((this / alignment).roundToInt() * alignment).coerceAtLeast(alignment)

    companion object {
        const val DEFAULT_LONG_EDGE: Int = 1024
        const val DEFAULT_ALIGNMENT: Int = 64

        fun fromLabel(label: String): AspectRatio? = entries.firstOrNull { it.label == label }
    }
}

@Serializable
data class Dimensions(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Dimensions must be positive, was ${width}x$height" }
    }

    override fun toString(): String = "${width}x$height"
}
