package com.visionstudio.core.common.export

import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ExifUserCommentTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun image(name: String, format: Bitmap.CompressFormat): File {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2255AA.toInt()) }
        return folder.newFile(name).also { f -> FileOutputStream(f).use { bitmap.compress(format, 95, it) } }
    }

    private fun item(file: File, mime: String) = MediaItem(
        id = "img-1", taskId = "t", type = MediaType.IMAGE, localUri = Uri.fromFile(file).toString(), mimeType = mime,
        width = 16, height = 16, sizeBytes = file.length(),
        parameters = GenerationParameters(prompt = "a glass koi 金魚", modelId = "flux/dev", seed = 7),
        createdAtEpochMillis = 1_800_000_000_000L,
    )

    /**
     * Independent of the code under test: finds the UserComment IFD entry by its raw bytes (tag
     * 0x9286 then type) in either byte order, and returns its type.
     */
    private fun userCommentType(bytes: ByteArray): Int? {
        for (i in 0 until bytes.size - 4) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val t0 = bytes[i + 2].toInt() and 0xFF
            val t1 = bytes[i + 3].toInt() and 0xFF
            if (b0 == 0x86 && b1 == 0x92 && t1 == 0 && t0 in setOf(2, 7)) return t0 // little-endian
            if (b0 == 0x92 && b1 == 0x86 && t0 == 0 && t1 in setOf(2, 7)) return t1 // big-endian
        }
        return null
    }

    private fun assertSpecCompliant(format: Bitmap.CompressFormat, name: String, mime: String) {
        val file = image(name, format)
        val media = item(file, mime)

        assertTrue(ExifEmbedder.embed(file, media, ZoneOffset.UTC))

        val bytes = file.readBytes()
        assertEquals("$mime UserComment is type UNDEFINED", 7, userCommentType(bytes))
        val expected = (ExifUserComment.ASCII_CODE + media.generationComment()).toByteArray(Charsets.US_ASCII)
        assertTrue("$mime value starts with the ASCII character code", bytes.indexOf(expected) >= 0)
        assertEquals(media.generationComment(), ExifInterface(file.path).getAttribute(ExifInterface.TAG_USER_COMMENT))
        // UNDEFINED carries no terminator: the value is exactly the code plus the JSON.
        assertEquals(expected.size.toLong(), ExifInterface(file.path).getAttributeRange(ExifInterface.TAG_USER_COMMENT)!![1])
    }

    @Test
    fun `png user comment is UNDEFINED with the ASCII character code`() =
        assertSpecCompliant(Bitmap.CompressFormat.PNG, "a.png", "image/png")

    @Test
    fun `jpeg user comment is UNDEFINED with the ASCII character code`() =
        assertSpecCompliant(Bitmap.CompressFormat.JPEG, "a.jpg", "image/jpeg")

    @Test
    fun `webp user comment is UNDEFINED with the ASCII character code`() =
        @Suppress("DEPRECATION") // WEBP is the only lossy WebP constant on API 26-29
        assertSpecCompliant(Bitmap.CompressFormat.WEBP, "a.webp", "image/webp")

    @Test
    fun `the retyped png still has a valid chunk crc`() {
        val file = image("a.png", Bitmap.CompressFormat.PNG)
        assertTrue(ExifEmbedder.embed(file, item(file, "image/png"), ZoneOffset.UTC))

        val bytes = file.readBytes()
        val chunk = bytes.indexOf("eXIf".toByteArray(Charsets.US_ASCII))
        val length = java.nio.ByteBuffer.wrap(bytes, chunk - 4, 4).int
        val stored = java.nio.ByteBuffer.wrap(bytes, chunk + 4 + length, 4).int.toLong() and 0xFFFFFFFFL
        val actual = java.util.zip.CRC32().apply { update(bytes, chunk, 4 + length) }.value
        assertEquals(actual, stored)
    }

    @Test
    fun `an unrecognised layout is refused and left untouched`() {
        val notAnImage = "not an image at all, just bytes".toByteArray()
        val copy = notAnImage.copyOf()
        assertFalse(ExifUserComment.markUndefined(copy))
        assertArrayEquals(notAnImage, copy)

        // A real PNG without any EXIF chunk.
        val plain = image("plain.png", Bitmap.CompressFormat.PNG).readBytes()
        val plainCopy = plain.copyOf()
        assertFalse(ExifUserComment.markUndefined(plainCopy))
        assertArrayEquals(plain, plainCopy)
    }

    @Test
    fun `a comment written without the character code is not retyped`() {
        val file = image("legacy.jpg", Bitmap.CompressFormat.JPEG)
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_USER_COMMENT, "{\"prompt\":\"legacy\"}")
            saveAttributes()
        }
        val bytes = file.readBytes()
        val copy = bytes.copyOf()

        assertFalse(ExifUserComment.markUndefined(copy))
        assertArrayEquals(bytes, copy)
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int =
        (0..size - needle.size).firstOrNull { start -> needle.indices.all { this[start + it] == needle[it] } } ?: -1
}
