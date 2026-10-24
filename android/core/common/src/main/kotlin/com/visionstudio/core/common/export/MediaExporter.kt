package com.visionstudio.core.common.export

import android.content.Intent
import android.net.Uri
import com.visionstudio.core.common.result.Result
import com.visionstudio.core.model.MediaItem

/** A file the user can now find in their gallery. */
data class ExportedMedia(
    /** `content://media/...` URI of the saved copy. */
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    /** Folder as the user sees it, for example `Pictures/VisionStudio`. */
    val folder: String,
    /** True when the generation parameters were embedded in the image's EXIF block. */
    val metadataEmbedded: Boolean,
)

/**
 * Takes generated media out of the app's private storage: into the shared gallery
 * ([exportToGallery]) or to another app through the share sheet ([createShareIntent]).
 *
 * The original in private storage is never modified. Both operations read it only after checking
 * that it really lives inside the app's own media folder.
 */
interface MediaExporter {

    /**
     * Saves a copy to the shared `Pictures/VisionStudio` or `Movies/VisionStudio` folder through
     * MediaStore. Images get their generation parameters embedded in EXIF when [embedMetadata] is
     * set and the format supports it.
     *
     * On Android 10+ no permission is needed. On Android 8–9 the result is
     * `PermissionRequiredException(WRITE_EXTERNAL_STORAGE)` until the user grants it.
     *
     * Nothing half-written is ever left in the gallery: on any failure the new entry is removed.
     */
    suspend fun exportToGallery(item: MediaItem, embedMetadata: Boolean = true): Result<ExportedMedia>

    /**
     * An `ACTION_SEND` intent wrapped in the system chooser. The file is shared through a
     * `FileProvider`, so the receiving app gets read access to that one file and nothing else.
     * It shares the original, clean file; no EXIF is added. [includePrompt] adds the prompt as text.
     */
    suspend fun createShareIntent(item: MediaItem, chooserTitle: CharSequence? = null, includePrompt: Boolean = false): Result<Intent>
}
