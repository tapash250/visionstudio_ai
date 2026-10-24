package com.visionstudio.core.database

import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import kotlinx.serialization.json.Json

private val ParametersJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun MediaItem.toEntity(slot: EngineSlot): GenerationHistoryEntity = GenerationHistoryEntity(
    id = id,
    taskId = taskId,
    slot = slot.name,
    mediaType = type.name,
    localUri = localUri,
    mimeType = mimeType,
    width = width,
    height = height,
    durationMillis = durationMillis,
    sizeBytes = sizeBytes,
    blurHash = blurHash,
    prompt = parameters.prompt,
    modelId = parameters.modelId,
    seed = parameters.seed,
    parametersJson = ParametersJson.encodeToString(GenerationParameters.serializer(), parameters),
    createdAtEpochMillis = createdAtEpochMillis,
)

/**
 * Maps a row back to the domain model. Rows whose stored parameters no longer decode (for
 * example after a schema change in the model) fall back to the denormalised columns rather
 * than disappearing from the Vault.
 */
fun GenerationHistoryEntity.toMediaItem(): MediaItem {
    val parameters = runCatching {
        ParametersJson.decodeFromString(GenerationParameters.serializer(), parametersJson)
    }.getOrElse { GenerationParameters(prompt = prompt, modelId = modelId, seed = seed) }
    val type = MediaType.entries.firstOrNull { it.name == mediaType } ?: MediaType.IMAGE
    return MediaItem(
        id = id,
        taskId = taskId,
        type = type,
        localUri = localUri,
        mimeType = mimeType,
        width = width,
        height = height,
        durationMillis = if (type == MediaType.VIDEO) durationMillis?.takeIf { it > 0 } ?: 1L else null,
        sizeBytes = sizeBytes,
        blurHash = blurHash,
        parameters = parameters,
        createdAtEpochMillis = createdAtEpochMillis,
    )
}

val GenerationHistoryEntity.engineSlot: EngineSlot?
    get() = EngineSlot.entries.firstOrNull { it.name == slot }
