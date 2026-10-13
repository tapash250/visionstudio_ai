package com.visionstudio.core.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.visionstudio.core.common.dispatchers.DefaultDispatcher
import com.visionstudio.core.common.dispatchers.IoDispatcher
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.network.engine.BinaryPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * A photo normalised for upload: EXIF rotation applied, long edge capped, re-encoded as PNG.
 * Masks are generated at exactly [width] × [height], so provider-side image and mask always agree.
 */
class NormalizedSource(val payload: BinaryPayload, val width: Int, val height: Int)

/**
 * Reads a user-picked image (Photo Picker or any content/file URI) into a [NormalizedSource].
 *
 * Bitmap policy: this is transient off-screen processing that never reaches the screen, so it
 * uses software ARGB_8888 and recycles every intermediate the moment its pixels have been
 * consumed. Anything displayed is decoded separately as a hardware bitmap.
 */
@Singleton
class SourceImageLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) {

    suspend fun load(uri: Uri, maxEdge: Int = DEFAULT_MAX_EDGE): NormalizedSource {
        require(maxEdge >= MIN_EDGE) { "maxEdge must be at least $MIN_EDGE" }
        val (decoded, orientation) = withContext(io) { decode(uri, maxEdge) }
        return withContext(default) { normalize(decoded, orientation, maxEdge) }
    }

    private fun decode(uri: Uri, maxEdge: Int): Pair<Bitmap, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw VisionStudioException.StorageException("That file isn't an image we can read")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = open(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw VisionStudioException.StorageException("Couldn't decode the selected image")
        val orientation = try {
            open(uri).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (unreadable: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return bitmap to orientation
    }

    private fun open(uri: Uri) = context.contentResolver.openInputStream(uri)
        ?: throw VisionStudioException.StorageException("Couldn't open the selected image")

    private fun normalize(source: Bitmap, orientation: Int, maxEdge: Int): NormalizedSource {
        var working = source
        try {
            val matrix = orientationMatrix(orientation)
            val rotatedLong = max(source.width, source.height)
            if (rotatedLong > maxEdge) {
                val scale = maxEdge.toFloat() / rotatedLong
                matrix.postScale(scale, scale)
            }
            if (!matrix.isIdentity) {
                val transformed = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
                if (transformed !== source) source.recycle()
                working = transformed
            }
            val out = ByteArrayOutputStream(working.byteCount / PNG_RATIO_GUESS)
            if (!working.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) {
                throw VisionStudioException.StorageException("Couldn't prepare the image for upload")
            }
            return NormalizedSource(BinaryPayload(out.toByteArray(), "image/png"), working.width, working.height)
        } finally {
            working.recycle()
            if (!source.isRecycled) source.recycle()
        }
    }

    companion object {
        const val DEFAULT_MAX_EDGE: Int = 2048
        private const val MIN_EDGE = 64
        private const val PNG_QUALITY = 100
        private const val PNG_RATIO_GUESS = 4

        /** Largest power-of-two subsample that keeps the long edge at or above [maxEdge]. */
        internal fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
            var sample = 1
            while (max(width, height) / (sample * 2) >= maxEdge) sample *= 2
            return sample
        }

        /** Matrix that turns a decoded bitmap upright for the given EXIF orientation. */
        internal fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
            }
        }
    }
}
