package com.visionstudio.feature.imageedit.mask

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Path
import kotlin.math.hypot

/**
 * Mutable, reusable buffer for the stroke currently under the finger. Keeping one instance
 * and mutating it means a gesture allocates nothing per touch event beyond the platform's own
 * pointer events: the sample array grows by doubling and the [path] is extended in place.
 *
 * The only observable state is [version]; the canvas reads it inside its draw lambda, so every
 * new sample invalidates the draw pass without recomposing anything.
 */
internal class StrokeInk {

    /** Geometry of the in-progress stroke in canvas pixels. */
    val path: Path = Path()

    var version: Int by mutableIntStateOf(0)
        private set

    var active: Boolean = false
        private set

    /** Latest finger position in canvas pixels, for the brush cursor. */
    var cursorX: Float = 0f
        private set
    var cursorY: Float = 0f
        private set

    private var samples = FloatArray(INITIAL_CAPACITY)
    private var count = 0
    private var width = 1f
    private var height = 1f
    private var lastX = 0f
    private var lastY = 0f

    fun begin(x: Float, y: Float, canvasWidth: Float, canvasHeight: Float) {
        width = canvasWidth.coerceAtLeast(1f)
        height = canvasHeight.coerceAtLeast(1f)
        val px = x.coerceIn(0f, width)
        val py = y.coerceIn(0f, height)
        path.reset()
        count = 0
        active = true
        path.moveTo(px, py)
        // A round-capped, near-zero-length segment renders as a dot, so a tap shows immediately.
        path.lineTo(px + MaskStroke.DOT_EPSILON, py)
        push(px, py)
        lastX = px
        lastY = py
        moveCursor(px, py)
    }

    /** Adds a sample unless it is closer than [MIN_STEP_PX] to the previous one. */
    fun append(x: Float, y: Float) {
        if (!active) return
        val px = x.coerceIn(0f, width)
        val py = y.coerceIn(0f, height)
        moveCursor(px, py)
        if (hypot(px - lastX, py - lastY) >= MIN_STEP_PX) extend(px, py)
    }

    /**
     * Ends the gesture at ([x], [y]) and returns the finished stroke. For a drawn line the final
     * point is stored twice: the smoothed path ends at a midpoint, so repeating the last sample
     * makes it reach the real end of the stroke. A single-sample tap stays a single dot.
     */
    fun finish(x: Float, y: Float, brush: BrushSettings): MaskStroke {
        val px = x.coerceIn(0f, width)
        val py = y.coerceIn(0f, height)
        if (count > 1 || hypot(px - lastX, py - lastY) >= MIN_STEP_PX) {
            if (hypot(px - lastX, py - lastY) >= MIN_STEP_PX) extend(px, py)
            extend(lastX, lastY)
        }
        active = false
        val stroke = MaskStroke(samples.copyOf(count * 2), brush.diameterFraction, brush.erase)
        version++
        return stroke
    }

    fun cancel() {
        active = false
        reset()
    }

    /** Clears the on-screen ink once the committed stroke is part of the drawn history. */
    fun release() {
        if (!active) reset()
    }

    private fun reset() {
        path.reset()
        count = 0
        version++
    }

    private fun extend(x: Float, y: Float) {
        val previousX = samples[(count - 1) * 2] * width
        val previousY = samples[(count - 1) * 2 + 1] * height
        path.quadraticTo(previousX, previousY, (previousX + x) / 2f, (previousY + y) / 2f)
        push(x, y)
        lastX = x
        lastY = y
        version++
    }

    private fun push(x: Float, y: Float) {
        if ((count + 1) * 2 > samples.size) samples = samples.copyOf(samples.size * 2)
        samples[count * 2] = x / width
        samples[count * 2 + 1] = y / height
        count++
    }

    private fun moveCursor(x: Float, y: Float) {
        cursorX = x
        cursorY = y
        version++
    }

    private companion object {
        const val INITIAL_CAPACITY = 256
        const val MIN_STEP_PX = 2f
    }
}
