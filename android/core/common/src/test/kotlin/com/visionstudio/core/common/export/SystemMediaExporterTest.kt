package com.visionstudio.core.common.export

import android.app.Application
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.common.result.Result
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SystemMediaExporterTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeMediaProvider
    private lateinit var exporter: SystemMediaExporter
    private lateinit var mediaDir: File

    @Before
    fun setUp() {
        resetFileProviderCache()
        provider = Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media")
        exporter = SystemMediaExporter(app, Dispatchers.IO)
        mediaDir = File(app.filesDir, StoragePaths.MEDIA_DIR).apply { mkdirs() }
    }

    private fun bitmapFile(name: String, format: Bitmap.CompressFormat): File {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2255AA.toInt()) }
        return File(mediaDir, name).also { f -> FileOutputStream(f).use { bitmap.compress(format, 95, it) } }
    }

    private fun imageItem(file: File, mime: String, seed: Long? = 421337) = MediaItem(
        id = "img-0001", taskId = "t", type = MediaType.IMAGE, localUri = Uri.fromFile(file).toString(), mimeType = mime,
        width = 16, height = 16, sizeBytes = file.length(),
        parameters = GenerationParameters(prompt = "a glass koi 金魚", negativePrompt = "blurry", modelId = "flux/dev", seed = seed),
        createdAtEpochMillis = 1_800_000_000_000L,
    )

    private fun videoItem(file: File) = MediaItem(
        id = "vid-0001", taskId = "t", type = MediaType.VIDEO, localUri = Uri.fromFile(file).toString(), mimeType = "video/mp4",
        width = 1280, height = 720, durationMillis = 5_000, sizeBytes = file.length(),
        parameters = GenerationParameters(prompt = "dolly in", modelId = "kling"), createdAtEpochMillis = 1_800_000_000_000L,
    )

    private fun export(item: MediaItem, embed: Boolean = true) = runBlocking { exporter.exportToGallery(item, embed) }

    private fun Result<ExportedMedia>.ok(): ExportedMedia = (this as? Result.Success)?.data ?: error("expected success but was $this")
    private fun <T> Result<T>.okValue(): T = (this as? Result.Success)?.data ?: error("expected success but was " + ((this as? Result.Error)?.exception?.let { it.javaClass.simpleName + ": " + it.message + " cause=" + it.cause }))
    private fun Result<*>.error(): VisionStudioException = (this as? Result.Error)?.exception ?: error("expected error but was $this")

    // region images

    @Test
    fun `a png is saved to Pictures VisionStudio, pending until fully written`() {
        val source = bitmapFile("a.png", Bitmap.CompressFormat.PNG)

        val exported = export(imageItem(source, "image/png")).ok()

        val row = provider.rows.values.single()
        assertEquals("Pictures/VisionStudio", exported.folder)
        assertEquals("Pictures/VisionStudio", row.onInsert.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals("VisionStudioAI_", exported.displayName.take(15))
        assertEquals("image/png", row.onInsert.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("hidden from the gallery while writing", 1, row.onInsert.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals("published when done", 0, row.updates.single().getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(1_800_000_000_000L, row.onInsert.getAsLong(MediaStore.MediaColumns.DATE_TAKEN))
        assertTrue(row.file.length() > 0)
        assertFalse(row.deleted)
        assertEquals(ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), row.id), exported.uri)
    }

    @Test
    fun `generation parameters are embedded in the exported copy, readable as json`() {
        val source = bitmapFile("a.png", Bitmap.CompressFormat.PNG)

        val exported = export(imageItem(source, "image/png")).ok()

        assertTrue(exported.metadataEmbedded)
        val saved = provider.rows.values.single().file
        val comment = ExifInterface(saved.path).getAttribute(ExifInterface.TAG_USER_COMMENT)!!
        val json = Json.parseToJsonElement(comment).jsonObject
        assertEquals("a glass koi 金魚", json["prompt"]!!.jsonPrimitive.content)
        assertEquals("421337", json["seed"]!!.jsonPrimitive.content)
        assertEquals("flux/dev", json["model"]!!.jsonPrimitive.content)
        assertEquals(EXPORT_APP_NAME, ExifInterface(saved.path).getAttribute(ExifInterface.TAG_SOFTWARE))
        assertNotNull(ExifInterface(saved.path).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
    }

    @Test
    fun `the private original is never modified`() {
        val source = bitmapFile("a.png", Bitmap.CompressFormat.PNG)
        val before = source.readBytes()

        export(imageItem(source, "image/png")).ok()

        assertTrue("original bytes unchanged", before.contentEquals(source.readBytes()))
        assertNull("original carries no export metadata", ExifInterface(source.path).getAttribute(ExifInterface.TAG_USER_COMMENT))
    }

    @Test
    fun `jpeg and webp also carry the metadata`() {
        val jpeg = export(imageItem(bitmapFile("a.jpg", Bitmap.CompressFormat.JPEG), "image/jpeg")).ok()
        val webp = export(imageItem(bitmapFile("a.webp", Bitmap.CompressFormat.WEBP_LOSSLESS), "image/webp")).ok()

        assertTrue(jpeg.metadataEmbedded)
        assertTrue(webp.metadataEmbedded)
        assertTrue(jpeg.displayName.endsWith(".jpg"))
        assertTrue(webp.displayName.endsWith(".webp"))
    }

    @Test
    fun `embedding can be turned off and the file is then an exact copy`() {
        val source = bitmapFile("a.png", Bitmap.CompressFormat.PNG)

        val exported = export(imageItem(source, "image/png"), embed = false).ok()

        assertFalse(exported.metadataEmbedded)
        assertTrue(source.readBytes().contentEquals(provider.rows.values.single().file.readBytes()))
    }

    @Test
    fun `an image format that cannot hold exif is still saved, flagged as without metadata`() {
        val gif = File(mediaDir, "a.gif").apply { writeBytes("GIF89a-not-really-a-gif".toByteArray()) }

        val exported = export(imageItem(gif, "image/gif")).ok()

        assertFalse(exported.metadataEmbedded)
        assertTrue(gif.readBytes().contentEquals(provider.rows.values.single().file.readBytes()))
    }

    @Test
    fun `a corrupt png is still exported byte for byte and the failed edit leaves nothing half-written`() {
        val corrupt = File(mediaDir, "bad.png").apply { writeBytes(ByteArray(64) { 0x7F }) }

        val exported = export(imageItem(corrupt, "image/png")).ok()

        assertFalse(exported.metadataEmbedded)
        assertTrue("restored to the original bytes", corrupt.readBytes().contentEquals(provider.rows.values.single().file.readBytes()))
    }

    // endregion

    // region video

    @Test
    fun `a video goes to Movies VisionStudio untouched`() {
        val video = File(mediaDir, "v.mp4").apply { writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }

        val exported = export(videoItem(video)).ok()

        val row = provider.rows.values.single()
        assertEquals("Movies/VisionStudio", exported.folder)
        assertEquals("Movies/VisionStudio", row.onInsert.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals("video/mp4", row.onInsert.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertFalse("videos carry no EXIF", exported.metadataEmbedded)
        assertTrue("every byte copied", video.readBytes().contentEquals(row.file.readBytes()))
        assertEquals(ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), row.id), exported.uri)
        assertTrue(exported.displayName.endsWith(".mp4"))
    }

    // endregion

    // region failure handling

    @Test
    fun `if the gallery rejects the file nothing is left behind and the error is typed`() {
        provider.insertReturnsNull = true
        val source = bitmapFile("a.png", Bitmap.CompressFormat.PNG)

        val error = export(imageItem(source, "image/png")).error()

        assertTrue(error is VisionStudioException.StorageException)
        assertTrue(provider.rows.isEmpty())
    }

    @Test
    fun `a failure while writing removes the half-created gallery entry`() {
        provider.failOpen = true
        val source = bitmapFile("a.png", Bitmap.CompressFormat.PNG)

        val error = export(imageItem(source, "image/png")).error()

        assertTrue("was $error", error is VisionStudioException.StorageException)
        val row = provider.rows.values.single()
        assertTrue("the pending row was deleted, so the gallery never shows a broken file", row.deleted)
        assertTrue("and it was never published", row.updates.isEmpty())
    }

    @Test
    fun `temporary copies are always cleaned up, success or failure`() {
        val source = bitmapFile("a.png", Bitmap.CompressFormat.PNG)
        export(imageItem(source, "image/png")).ok()
        provider.failOpen = true
        export(imageItem(source, "image/png"))

        val leftovers = app.cacheDir.listFiles { f -> f.name.startsWith("export-") }.orEmpty()
        assertEquals(emptyList<String>(), leftovers.map { it.name })
    }

    @Test
    fun `a missing original is reported, not exported`() {
        val gone = File(mediaDir, "gone.png")

        val error = export(imageItem(gone, "image/png")).error()

        assertTrue(error is VisionStudioException.StorageException)
        assertTrue(provider.rows.isEmpty())
    }

    @Test
    fun `an empty original is refused`() {
        val empty = File(mediaDir, "empty.png").apply { writeBytes(ByteArray(0)) }
        assertTrue(export(imageItem(empty, "image/png")).error() is VisionStudioException.StorageException)
        assertTrue(provider.rows.isEmpty())
    }

    @Test
    fun `files outside the app media folder are refused, including dot-dot tricks`() {
        val outside = File(app.filesDir, "secret.txt").apply { writeText("not media") }
        val viaTraversal = Uri.fromFile(File(mediaDir, "../secret.txt")).toString()

        val direct = export(imageItem(outside, "image/png")).error()
        val traversal = export(imageItem(outside, "image/png").copy(localUri = viaTraversal)).error()

        assertTrue(direct is VisionStudioException.StorageException)
        assertTrue(traversal is VisionStudioException.StorageException)
        assertTrue("nothing was exported", provider.rows.isEmpty())
    }

    @Test
    fun `a symlink inside the media folder cannot reach outside it`() {
        val outside = File(app.filesDir, "secret.png").also { FileOutputStream(it).use { out -> Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out) } }
        val link = File(mediaDir, "link.png")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())

        val error = export(imageItem(link, "image/png")).error()

        assertTrue("symlink escape blocked: $error", error is VisionStudioException.StorageException)
        assertTrue(provider.rows.isEmpty())
    }

    // endregion

    // region sharing

    @Test
    fun `the share intent is a chooser around ACTION_SEND with a granted content uri`() {
        val source = bitmapFile("share.png", Bitmap.CompressFormat.PNG)
        val item = imageItem(source, "image/png")

        val chooser = runBlocking { exporter.createShareIntent(item, "Share creation") }.okValue()

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Share creation", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        @Suppress("DEPRECATION")
        val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", stream.scheme)
        assertEquals("${app.packageName}.fileprovider", stream.authority)
        assertTrue("read access granted to the receiver", send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue("and carried through the chooser", chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("the clip data carries the grant on newer Android", stream, send.clipData!!.getItemAt(0).uri)
        assertNull("no prompt text unless asked", send.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue("the share never exposes a raw file path", stream.toString().startsWith("content://"))
    }

    @Test
    fun `the prompt can be attached as text on request`() {
        val item = imageItem(bitmapFile("share.png", Bitmap.CompressFormat.PNG), "image/png")

        val chooser = runBlocking { exporter.createShareIntent(item, null, includePrompt = true) }.okValue()

        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("a glass koi 金魚", send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `sharing a video uses its mime type`() {
        val video = File(mediaDir, "v.mp4").apply { writeBytes(ByteArray(2_000) { 1 }) }

        val chooser = runBlocking { exporter.createShareIntent(videoItem(video)) }.okValue()

        @Suppress("DEPRECATION")
        assertEquals("video/mp4", chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!.type)
    }

    @Test
    fun `sharing refuses files outside the media folder and missing files`() {
        val outside = File(app.filesDir, "secret.png").apply { writeBytes(ByteArray(10) { 1 }) }
        val missing = File(mediaDir, "nope.png")

        assertTrue(runBlocking { exporter.createShareIntent(imageItem(outside, "image/png")) }.error() is VisionStudioException.StorageException)
        assertTrue(runBlocking { exporter.createShareIntent(imageItem(missing, "image/png")) }.error() is VisionStudioException.StorageException)
    }

    @Test
    fun `sharing does not write anything to the gallery`() {
        runBlocking { exporter.createShareIntent(imageItem(bitmapFile("a.png", Bitmap.CompressFormat.PNG), "image/png")) }

        assertTrue(provider.rows.isEmpty())
    }

    // endregion

    /**
     * FileProvider memoises its resolved root directories in a static map keyed by authority. Robolectric
     * gives every test a fresh app data directory but keeps the class loaded, so without this a test
     * would see the first test's (deleted) directory and reject its own files.
     */
    private fun resetFileProviderCache() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (cache.get(null) as MutableMap<String, Any?>).clear()
    }
}
