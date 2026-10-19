package com.visionstudio.core.common.export

import android.Manifest
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.visionstudio.core.common.dispatchers.IoDispatcher
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.common.result.Result
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
internal class SystemMediaExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MediaExporter {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    override suspend fun exportToGallery(item: MediaItem, embedMetadata: Boolean): Result<ExportedMedia> =
        try {
            Result.Success(withContext(io) { export(item, embedMetadata) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: VisionStudioException) {
            Result.Error(failure)
        } catch (failure: IOException) {
            Result.Error(VisionStudioException.StorageException(failure.message ?: "Couldn't save the file", failure))
        } catch (failure: SecurityException) {
            Result.Error(VisionStudioException.StorageException("The system refused to save the file", failure))
        } catch (failure: RuntimeException) {
            Result.Error(VisionStudioException.StorageException(failure.message ?: "Couldn't save the file", failure))
        }

    override suspend fun createShareIntent(item: MediaItem, chooserTitle: CharSequence?, includePrompt: Boolean): Result<Intent> =
        try {
            // Resolving the file touches the disk (canonical path, existence, size): keep it off the caller's thread.
            val file = withContext(io) { resolveOwnFile(item) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                if (includePrompt && item.parameters.prompt.isNotBlank()) putExtra(Intent.EXTRA_TEXT, item.parameters.prompt)
                // The ClipData is what carries the read grant through the chooser on newer Android.
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Result.Success(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: VisionStudioException) {
            Result.Error(failure)
        } catch (failure: IOException) {
            Result.Error(VisionStudioException.StorageException("Couldn't read the file to share", failure))
        } catch (outside: IllegalArgumentException) {
            Result.Error(VisionStudioException.StorageException("This file can't be shared", outside))
        }

    // region export

    private suspend fun export(item: MediaItem, embedMetadata: Boolean): ExportedMedia {
        val source = resolveOwnFile(item)
        val name = item.exportFileName(zone)
        val folder = folderFor(item.type)

        // Edit EXIF on a private temp copy, so the original is never touched and the gallery only
        // ever receives the finished file in one pass.
        val temp = File.createTempFile("export-", "." + name.substringAfterLast('.'), context.cacheDir)
        try {
            source.copyTo(temp, overwrite = true)
            val embedded = embedMetadata && ExifEmbedder.embed(temp, item, zone)
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeScoped(temp, item, name, folder)
            } else {
                writeLegacy(temp, item, name, folder)
            }
            return ExportedMedia(uri, name, item.mimeType, folder, embedded)
        } finally {
            withContext(NonCancellable) { temp.delete() }
        }
    }

    /** Android 10+: a pending MediaStore row that only becomes visible once fully written. */
    private fun writeScoped(payload: File, item: MediaItem, name: String, folder: String): Uri {
        val resolver = context.contentResolver
        val collection = if (item.type == MediaType.VIDEO) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.DATE_TAKEN, item.createdAtEpochMillis)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, pending)
            ?: throw VisionStudioException.StorageException("The gallery didn't accept the file")
        try {
            val output = resolver.openOutputStream(uri, "w")
                ?: throw VisionStudioException.StorageException("Couldn't open the gallery file for writing")
            output.use { sink -> payload.inputStream().use { it.copyTo(sink, COPY_BUFFER) } }
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, published, null, null)
            return uri
        } catch (failure: Throwable) {
            // Never leave a half-written entry in the user's gallery.
            try {
                resolver.delete(uri, null, null)
            } catch (cleanup: RuntimeException) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    /** Android 8–9: no scoped storage, so write into the public folder (permission required) and register it. */
    @Suppress("DEPRECATION") // getExternalStoragePublicDirectory is only reached below API 29
    private fun writeLegacy(payload: File, item: MediaItem, name: String, folder: String): Uri {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            throw VisionStudioException.PermissionRequiredException(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val publicDirectory = if (item.type == MediaType.VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val directory = File(Environment.getExternalStoragePublicDirectory(publicDirectory), folder.substringAfter('/'))
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw VisionStudioException.StorageException("Couldn't create the ${folder} folder")
        }
        val target = uniqueFile(directory, name)
        try {
            payload.copyTo(target, overwrite = false)
            val collection = if (item.type == MediaType.VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, target.absolutePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, target.name)
                put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
                put(MediaStore.MediaColumns.SIZE, target.length())
                put(MediaStore.Images.ImageColumns.DATE_TAKEN, item.createdAtEpochMillis)
            }
            return context.contentResolver.insert(collection, values)
                ?: throw VisionStudioException.StorageException("The gallery didn't accept the file")
        } catch (failure: Throwable) {
            // A half-copied file in the public folder is worse than no file.
            target.delete()
            throw failure
        }
    }

    private fun uniqueFile(directory: File, name: String): File {
        var candidate = File(directory, name)
        var n = 1
        val base = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "")
        while (candidate.exists()) candidate = File(directory, "$base ($n)." + extension).also { n++ }
        return candidate
    }

    private fun folderFor(type: MediaType): String =
        (if (type == MediaType.VIDEO) "Movies" else "Pictures") + "/" + GALLERY_FOLDER

    // endregion

    /**
     * Resolves the item's file and refuses anything that is not a real, non-empty file inside the
     * app's own media folder. The check uses canonical paths, so `..` segments and symlinks cannot
     * point it at some other file.
     */
    private fun resolveOwnFile(item: MediaItem): File {
        val path = Uri.parse(item.localUri).path
            ?: throw VisionStudioException.StorageException("This item has no file")
        val file = File(path).canonicalFile
        val root = File(context.filesDir, StoragePaths.MEDIA_DIR).canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) {
            throw VisionStudioException.StorageException("This file isn't part of your library")
        }
        if (!file.isFile || file.length() == 0L) {
            throw VisionStudioException.StorageException("The original file is gone")
        }
        return file
    }

    private companion object {
        const val GALLERY_FOLDER = "VisionStudio"
        const val COPY_BUFFER = 64 * 1024
    }
}
