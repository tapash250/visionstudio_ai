package com.visionstudio.core.data.media

import android.content.Context
import android.net.Uri
import com.visionstudio.core.common.dispatchers.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-specific storage for generated media (`filesDir/media`). No storage permission is
 * needed, files are removed on uninstall, and the Vault reads them directly. Export to the
 * shared MediaStore is a separate, explicit user action.
 */
@Singleton
class MediaFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val root: File by lazy { File(context.filesDir, MEDIA_DIR) }

    suspend fun newFile(id: String, extension: String): File = withContext(io) {
        root.mkdirs()
        File(root, "$id.${extension.trimStart('.')}")
    }

    fun uriOf(file: File): String = Uri.fromFile(file).toString()

    /** Resolves a stored `file://` URI, refusing anything outside the media directory. */
    fun fileOf(uri: String): File? {
        val path = Uri.parse(uri).path ?: return null
        val file = File(path).canonicalFile
        return file.takeIf { it.path.startsWith(root.canonicalPath + File.separator) }
    }

    suspend fun delete(uri: String): Boolean = withContext(io) { fileOf(uri)?.delete() == true }

    private companion object {
        const val MEDIA_DIR = "media"
    }
}
