package com.visionstudio.core.common.export

import androidx.exifinterface.media.ExifInterface
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import java.io.File
import java.io.IOException
import java.time.ZoneId

/**
 * Writes an image's generation parameters into its own EXIF block, so the settings travel with
 * the file wherever it is copied.
 *
 * - `UserComment`: the JSON from [generationComment].
 * - `Software`: [EXPORT_APP_NAME], so tools can tell where the file came from.
 * - `DateTimeOriginal` / `DateTime`: the creation time, so galleries sort it correctly.
 *
 * PNG, JPEG and WebP are all supported by the AndroidX writer. Any other format is left as it is.
 *
 * Known deviation, verified against two independent readers (Pillow and piexif): the AndroidX
 * writer stores `UserComment` as a plain ASCII field and does not add the 8-byte `ASCII\0\0\0`
 * character-set marker the EXIF specification describes. Both readers recover the JSON exactly;
 * a reader that insists on the marker may show the field unparsed.
 */
internal object ExifEmbedder {

    /**
     * Edits [file] in place. Returns true if the metadata was written and reads back intact,
     * false if the format can't carry EXIF or the write failed; in that case [file] is unchanged
     * or still a valid image, never half-written.
     */
    fun embed(file: File, item: MediaItem, zone: ZoneId): Boolean {
        if (item.type != MediaType.IMAGE || !isSupported(item.mimeType)) return false
        val comment = item.generationComment()
        val before = try {
            file.readBytes()
        } catch (unreadable: IOException) {
            return false
        }
        val written = try {
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_USER_COMMENT, comment)
                setAttribute(ExifInterface.TAG_SOFTWARE, EXPORT_APP_NAME)
                val taken = exifDateTime(item.createdAtEpochMillis, zone)
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, taken)
                setAttribute(ExifInterface.TAG_DATETIME, taken)
                saveAttributes()
            }
            // Never hand back a file we can't read back.
            ExifInterface(file.path).getAttribute(ExifInterface.TAG_USER_COMMENT) == comment
        } catch (unsupported: IOException) {
            false
        } catch (malformed: RuntimeException) {
            false
        }
        if (!written) restore(file, before)
        return written
    }

    /** Puts [original] back, so a failed or partial write can never leave a damaged image. */
    private fun restore(file: File, original: ByteArray) {
        try {
            file.writeBytes(original)
        } catch (unwritable: IOException) {
            // Nothing more can be done; the caller exports whatever is on disk.
        }
    }

    fun isSupported(mimeType: String): Boolean = mimeType.lowercase() in SUPPORTED

    private val SUPPORTED = setOf("image/png", "image/jpeg", "image/jpg", "image/webp")
}
