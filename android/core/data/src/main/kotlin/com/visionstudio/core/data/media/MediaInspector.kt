package com.visionstudio.core.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import com.visionstudio.core.common.dispatchers.DefaultDispatcher
import com.visionstudio.core.common.dispatchers.IoDispatcher
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.common.image.BlurHash
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Facts about a stored asset needed for its MediaItem row. */
data class MediaFacts(
    val width: Int,
    val height: Int,
    val durationMillis: Long?,
    val blurHash: String?,
)

/**
 * Inspects saved media and decodes previews.
 *
 * Bitmap policy: anything that is rendered (previews) is decoded straight into
 * `Bitmap.Config.HARDWARE`, so pixels live only in GPU memory. The one place pixels must be
 * read on the CPU, BlurHash encoding, uses a tiny subsampled software ARGB_8888 bitmap
 * that is recycled as soon as its pixels are copied out.
 */
@Singleton
class MediaInspector @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) {

    suspend fun inspectImage(file: File): MediaFacts {
        val bounds = withContext(io) {
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeFile(file.path, this)
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw VisionStudioException.StorageException("Provider returned an undecodable image")
        }
        val hash = withContext(default) { blurHashOf(file, bounds.outWidth, bounds.outHeight) }
        return MediaFacts(bounds.outWidth, bounds.outHeight, null, hash)
    }

    suspend fun inspectVideo(file: File, fallbackWidth: Int, fallbackHeight: Int, fallbackDurationMillis: Long): MediaFacts =
        withContext(io) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                fun meta(key: Int) = retriever.extractMetadata(key)?.toLongOrNull()
                val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0L
                var width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: fallbackWidth
                var height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: fallbackHeight
                if (rotation == ROTATED_90 || rotation == ROTATED_270) width = height.also { height = width }
                val duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: fallbackDurationMillis
                val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, HASH_EDGE, HASH_EDGE)
                } else {
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                        Bitmap.createScaledBitmap(full, HASH_EDGE, HASH_EDGE, true).also { if (it !== full) full.recycle() }
                    }
                }
                val hash = frame?.let { withContext(default) { hashAndRecycle(it) } }
                MediaFacts(width.coerceAtLeast(1), height.coerceAtLeast(1), duration, hash)
            } catch (failure: RuntimeException) {
                MediaFacts(fallbackWidth, fallbackHeight, fallbackDurationMillis, null)
            } finally {
                retriever.release()
            }
        }

    /** Decodes a streamed preview into a GPU-only hardware bitmap, or null if undecodable. */
    suspend fun decodePreview(bytes: ByteArray): Bitmap? = withContext(default) {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.HARDWARE
            inMutable = false
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun blurHashOf(file: File, width: Int, height: Int): String? {
        var sample = 1
        while (width / (sample * 2) >= HASH_EDGE && height / (sample * 2) >= HASH_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
        val scaled = if (bitmap.width > HASH_EDGE * 2 || bitmap.height > HASH_EDGE * 2) {
            Bitmap.createScaledBitmap(bitmap, HASH_EDGE, (HASH_EDGE * bitmap.height / bitmap.width).coerceAtLeast(1), true)
                .also { if (it !== bitmap) bitmap.recycle() }
        } else {
            bitmap
        }
        return hashAndRecycle(scaled)
    }

    private fun hashAndRecycle(bitmap: Bitmap): String? {
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
        } else {
            bitmap
        } ?: return null
        return try {
            val pixels = IntArray(software.width * software.height)
            software.getPixels(pixels, 0, software.width, 0, 0, software.width, software.height)
            val landscape = software.width >= software.height
            BlurHash.encode(pixels, software.width, software.height, if (landscape) 4 else 3, if (landscape) 3 else 4)
        } finally {
            software.recycle()
        }
    }

    private companion object {
        const val HASH_EDGE = 32
        const val ROTATED_90 = 90L
        const val ROTATED_270 = 270L
    }
}
