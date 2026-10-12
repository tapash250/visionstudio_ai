package com.visionstudio.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class MediaType(val defaultMimeType: String, val fileExtension: String) {
    IMAGE("image/png", "png"),
    VIDEO("video/mp4", "mp4"),
}

/** A generated asset persisted in app-specific storage and surfaced in the Media Vault. */
@Serializable
data class MediaItem(
    val id: String,
    val taskId: String,
    val type: MediaType,
    val localUri: String,
    val mimeType: String = type.defaultMimeType,
    val width: Int,
    val height: Int,
    val durationMillis: Long? = null,
    val sizeBytes: Long,
    val blurHash: String? = null,
    val parameters: GenerationParameters,
    val createdAtEpochMillis: Long,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(localUri.isNotBlank()) { "localUri must not be blank" }
        require(width > 0 && height > 0) { "Invalid dimensions ${width}x$height" }
        require(sizeBytes >= 0) { "sizeBytes must be non-negative" }
        when (type) {
            MediaType.VIDEO -> require(durationMillis != null && durationMillis > 0) {
                "Video items require a positive durationMillis"
            }
            MediaType.IMAGE -> require(durationMillis == null) { "Image items cannot have a duration" }
        }
    }

    val dimensions: Dimensions get() = Dimensions(width, height)
    val isVideo: Boolean get() = type == MediaType.VIDEO
}
