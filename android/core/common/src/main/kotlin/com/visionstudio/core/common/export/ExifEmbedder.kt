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
 * - `UserComment`: the JSON from [generationComment], stored as the EXIF specification defines it
 *   (type UNDEFINED, prefixed with the 8-byte `ASCII\0\0\0` character code; see [ExifUserComment]).
 * - `Software`: [EXPORT_APP_NAME], so tools can tell where the file came from.
 * - `DateTimeOriginal` / `DateTime`: the creation time, so galleries sort it correctly.
 *
 * PNG, JPEG and WebP are all supported by the AndroidX writer. Any other format is left as it is.
 * If the comment can't be retyped (an unexpected file layout), it is written the way the AndroidX
 * writer does by itself, as a plain ASCII field, which every reader tested still understands.
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
        val written = write(file, item, zone, comment, specCompliant = true) ||
            restore(file, before).let { write(file, item, zone, comment, specCompliant = false) }
        if (!written) restore(file, before)
        return written
    }

    /**
     * Writes the tags and verifies they read back. With [specCompliant], `UserComment` carries the
     * ASCII character code and is retyped to UNDEFINED; otherwise it is the AndroidX default.
     */
    private fun write(file: File, item: MediaItem, zone: ZoneId, comment: String, specCompliant: Boolean): Boolean = try {
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_USER_COMMENT, if (specCompliant) ExifUserComment.withAsciiCode(comment) else comment)
            setAttribute(ExifInterface.TAG_SOFTWARE, EXPORT_APP_NAME)
            val taken = exifDateTime(item.createdAtEpochMillis, zone)
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, taken)
            setAttribute(ExifInterface.TAG_DATETIME, taken)
            saveAttributes()
        }
        if (specCompliant) {
            val bytes = file.readBytes()
            if (!ExifUserComment.markUndefined(bytes)) throw IOException("UserComment layout not recognised")
            file.writeBytes(bytes)
        }
        // Never hand back a file we can't read back. The AndroidX reader drops the character code.
        ExifInterface(file.path).getAttribute(ExifInterface.TAG_USER_COMMENT) == comment
    } catch (unsupported: IOException) {
        false
    } catch (malformed: RuntimeException) {
        false
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
