package com.visionstudio.feature.imageedit.mask

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import com.visionstudio.core.common.dispatchers.DefaultDispatcher
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.network.engine.BinaryPayload
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** A finished mask: a strictly black/white PNG plus how much of the image it selects. */
class RasterizedMask(
    val png: BinaryPayload,
    val whitePixels: Long,
    val width: Int,
    val height: Int,
) {
    /** Fraction of the image selected for repainting, 0..1. */
    val coverage: Float get() = whitePixels.toFloat() / (width.toLong() * height).toFloat()
    val isEmpty: Boolean get() = whitePixels == 0L
}

/**
 * Converts vector [MaskStroke]s into the binary mask providers consume: white (0xFFFFFF) where
 * the image should be repainted, black (0x000000) everywhere else.
 *
 * Runs on the Default dispatcher. The bitmap is a software ARGB_8888 surface, because Canvas
 * drawing needs one and the pixels must be read back, and it never leaves this function: it is
 * recycled in `finally` as soon as the PNG bytes exist. Strokes are drawn in order with
 * anti-aliasing off, so eraser strokes (black) correctly cut into earlier brush strokes (white),
 * and a final threshold pass guarantees every pixel is exactly 0x000000 or 0xFFFFFF.
 */
@Singleton
class MaskRasterizer @Inject constructor(
    @DefaultDispatcher private val default: CoroutineDispatcher,
) {

    suspend fun rasterize(strokes: List<MaskStroke>, width: Int, height: Int): RasterizedMask {
        require(width > 0 && height > 0) { "Mask size must be positive, was ${width}x$height" }
        return withContext(default) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLACK)
                draw(bitmap, strokes)
                val white = binarize(bitmap)
                val out = ByteArrayOutputStream(width * height / COMPRESSION_GUESS)
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) {
                    throw VisionStudioException.StorageException("Couldn't encode the mask image")
                }
                RasterizedMask(BinaryPayload(out.toByteArray(), "image/png"), white, width, height)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun draw(bitmap: Bitmap, strokes: List<MaskStroke>) {
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = false
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        for (stroke in strokes) {
            coroutineContext.ensureActive()
            path.reset()
            stroke.appendTo(path, width, height)
            paint.strokeWidth = stroke.diameterFraction * width
            paint.color = if (stroke.erase) Color.BLACK else Color.WHITE
            canvas.drawPath(path.asAndroidPath(), paint)
        }
    }

    /** Forces every pixel to pure black or white and returns the white count. Works in row bands. */
    private suspend fun binarize(bitmap: Bitmap): Long {
        val width = bitmap.width
        val band = IntArray(width * BAND_ROWS)
        var white = 0L
        var y = 0
        while (y < bitmap.height) {
            coroutineContext.ensureActive()
            val rows = minOf(BAND_ROWS, bitmap.height - y)
            bitmap.getPixels(band, 0, width, 0, y, width, rows)
            for (i in 0 until width * rows) {
                val on = (band[i] and 0xFF) >= HALF
                band[i] = if (on) Color.WHITE else Color.BLACK
                if (on) white++
            }
            bitmap.setPixels(band, 0, width, 0, y, width, rows)
            y += rows
        }
        return white
    }

    private companion object {
        const val BAND_ROWS = 64
        const val HALF = 128
        const val PNG_QUALITY = 100
        const val COMPRESSION_GUESS = 8
    }
}
