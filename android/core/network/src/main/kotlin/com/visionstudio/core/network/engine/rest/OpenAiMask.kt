package com.visionstudio.core.network.engine.rest

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.visionstudio.core.common.error.VisionStudioException
import java.io.ByteArrayOutputStream

/**
 * OpenAI's edit endpoints mark the region to repaint with *transparency*, whereas VisionStudio
 * (and Fal.ai, and most inpainting models) use a black/white mask where white means "repaint".
 * This converts the latter into the former: white becomes fully transparent, black becomes
 * opaque black.
 *
 * CPU-bound; call on the Default dispatcher. Works in row bands so peak memory stays near one
 * bitmap, and recycles everything it allocates before returning.
 */
internal object OpenAiMask {

    fun toAlphaMask(whiteIsEdit: ByteArray): ByteArray {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val bitmap = BitmapFactory.decodeByteArray(whiteIsEdit, 0, whiteIsEdit.size, options)
            ?: throw VisionStudioException.ValidationException("mask", "The mask image couldn't be decoded")
        try {
            val width = bitmap.width
            val band = IntArray(width * BAND_ROWS)
            var y = 0
            while (y < bitmap.height) {
                val rows = minOf(BAND_ROWS, bitmap.height - y)
                bitmap.getPixels(band, 0, width, 0, y, width, rows)
                for (i in 0 until width * rows) {
                    band[i] = if (isEdit(band[i])) TRANSPARENT else OPAQUE_BLACK
                }
                bitmap.setPixels(band, 0, width, 0, y, width, rows)
                y += rows
            }
            val out = ByteArrayOutputStream(whiteIsEdit.size)
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw VisionStudioException.StorageException("Couldn't encode the mask")
            }
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** A pixel is "edit" when it is at least mid-grey on its brightest channel and not transparent. */
    private fun isEdit(argb: Int): Boolean {
        val alpha = argb ushr 24
        val brightest = maxOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        return alpha >= HALF && brightest >= HALF
    }

    private const val BAND_ROWS = 64
    private const val HALF = 128
    private const val TRANSPARENT = 0x00000000
    private const val OPAQUE_BLACK = -0x1000000
}
