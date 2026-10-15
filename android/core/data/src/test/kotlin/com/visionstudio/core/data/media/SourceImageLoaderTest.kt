package com.visionstudio.core.data.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.visionstudio.core.common.error.VisionStudioException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SourceImageLoaderTest {

    @get:Rule val temp = TemporaryFolder()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val loader get() = SourceImageLoader(context, Dispatchers.IO, Dispatchers.Default)

    private fun jpeg(width: Int, height: Int, exifOrientation: Int? = null): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
        val file = File(temp.root, "photo-$width-$height-${exifOrientation ?: 0}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        exifOrientation?.let {
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, it.toString())
                saveAttributes()
            }
        }
        return Uri.fromFile(file)
    }

    @Test
    fun `small upright image passes through at its own size as png`() = runBlocking {
        val source = loader.load(jpeg(200, 100))

        assertEquals(200, source.width)
        assertEquals(100, source.height)
        assertEquals("image/png", source.payload.mimeType)
        val decoded = BitmapFactory.decodeByteArray(source.payload.bytes, 0, source.payload.bytes.size)
        assertEquals(200, decoded.width)
        assertEquals(100, decoded.height)
    }

    @Test
    fun `exif rotate 90 swaps width and height`() = runBlocking {
        val source = loader.load(jpeg(200, 100, ExifInterface.ORIENTATION_ROTATE_90))

        assertEquals(100, source.width)
        assertEquals(200, source.height)
    }

    @Test
    fun `oversized image is downscaled so its long edge is the cap`() = runBlocking {
        val source = loader.load(jpeg(1600, 800), maxEdge = 400)

        assertEquals(400, maxOf(source.width, source.height))
        assertEquals(2.0, source.width.toDouble() / source.height, 0.02)
    }

    @Test
    fun `rotation and downscale combine`() = runBlocking {
        val source = loader.load(jpeg(1600, 800, ExifInterface.ORIENTATION_ROTATE_270), maxEdge = 400)

        assertEquals(200, source.width)
        assertEquals(400, source.height)
    }

    @Test
    fun `non image file is a storage error`() {
        val file = File(temp.root, "notes.txt").apply { writeText("not an image") }

        val error = runCatching { runBlocking { loader.load(Uri.fromFile(file)) } }.exceptionOrNull()

        assertTrue("got $error", error is VisionStudioException.StorageException)
    }

    @Test
    fun `subsample keeps long edge at or above the cap`() {
        assertEquals(1, SourceImageLoader.sampleSizeFor(3000, 2000, 2048))
        assertEquals(2, SourceImageLoader.sampleSizeFor(4200, 3000, 2048))
        assertEquals(4, SourceImageLoader.sampleSizeFor(9000, 6000, 2048))
    }
}
