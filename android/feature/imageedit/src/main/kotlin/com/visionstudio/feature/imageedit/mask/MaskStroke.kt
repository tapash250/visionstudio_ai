package com.visionstudio.feature.imageedit.mask

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Path

/**
 * One continuous brush or eraser gesture, stored in resolution-independent form: points are
 * normalised to 0..1 of the image's width/height and [diameterFraction] is a fraction of the
 * image width. The same stroke therefore renders identically on the screen canvas and in the
 * full-resolution mask, whatever size either one is.
 *
 * Immutable: the point data is copied on construction and never exposed.
 */
@Immutable
class MaskStroke(
    points: FloatArray,
    val diameterFraction: Float,
    val erase: Boolean,
) {
    private val xy: FloatArray = points.copyOf()

    init {
        require(xy.isNotEmpty() && xy.size % 2 == 0) { "points must hold at least one x,y pair" }
        require(diameterFraction > 0f && diameterFraction <= 1f) { "diameterFraction must be in (0, 1], was $diameterFraction" }
    }

    val pointCount: Int get() = xy.size / 2

    fun x(index: Int): Float = xy[index * 2]
    fun y(index: Int): Float = xy[index * 2 + 1]

    /**
     * Appends this stroke's outline to [path], scaled to [width] × [height]. Consecutive samples
     * are joined with quadratic curves through their midpoints, which turns jittery touch
     * samples into smooth ink. [StrokeInk] builds the same geometry incrementally while the
     * finger is down, so what you see while painting is exactly what is committed.
     */
    fun appendTo(path: Path, width: Float, height: Float) {
        val startX = x(0) * width
        val startY = y(0) * height
        path.moveTo(startX, startY)
        if (pointCount == 1) {
            // A round-capped, near-zero-length segment renders as a dot.
            path.lineTo(startX + DOT_EPSILON, startY)
            return
        }
        var previousX = startX
        var previousY = startY
        for (i in 1 until pointCount) {
            val currentX = x(i) * width
            val currentY = y(i) * height
            path.quadraticTo(previousX, previousY, (previousX + currentX) / 2f, (previousY + currentY) / 2f)
            previousX = currentX
            previousY = currentY
        }
    }

    internal companion object {
        const val DOT_EPSILON: Float = 0.01f
    }
}

/**
 * Immutable stroke history with redo. Committing a new stroke after undoing discards the redo
 * branch, as in every editor.
 */
@Immutable
class MaskHistory private constructor(
    val strokes: List<MaskStroke>,
    private val undone: List<MaskStroke>,
) {
    val canUndo: Boolean get() = strokes.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()
    val isEmpty: Boolean get() = strokes.isEmpty()

    fun commit(stroke: MaskStroke): MaskHistory = MaskHistory(strokes + stroke, emptyList())

    fun undo(): MaskHistory =
        if (strokes.isEmpty()) this else MaskHistory(strokes.dropLast(1), undone + strokes.last())

    fun redo(): MaskHistory =
        if (undone.isEmpty()) this else MaskHistory(strokes + undone.last(), undone.dropLast(1))

    /** Wipes the mask. Not undoable, because the UI asks the user to confirm intent by tapping Clear. */
    fun clear(): MaskHistory = Empty

    companion object {
        val Empty = MaskHistory(emptyList(), emptyList())
    }
}

/** Brush configuration, shared by the live canvas and the committed strokes. */
@Immutable
data class BrushSettings(
    val diameterFraction: Float = DEFAULT_DIAMETER,
    val erase: Boolean = false,
) {
    companion object {
        const val MIN_DIAMETER: Float = 0.015f
        const val MAX_DIAMETER: Float = 0.2f
        const val DEFAULT_DIAMETER: Float = 0.07f
    }
}
