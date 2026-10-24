package com.visionstudio.feature.gallery.model

import androidx.compose.runtime.Immutable
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Which fact a row shows. The UI maps each to a localised label. */
enum class InspectorField {
    SEED,
    MODEL,
    CREATED,
    SIZE,
    FRAME,
    DURATION,
    CAMERA,
    STEPS,
    GUIDANCE,
    FILE_SIZE,
    SOURCE,
}

@Immutable
data class InspectorRow(
    val field: InspectorField,
    val value: String,
    /** Technical values (seed, model ID) render in monospace and can be copied. */
    val technical: Boolean,
)

@Immutable
data class InspectorModel(
    val prompt: String,
    val negativePrompt: String?,
    val rows: List<InspectorRow>,
)

/**
 * Everything the metadata sheet shows, derived from the stored [MediaItem]. Pure, so it is
 * tested directly: no field is shown that the item does not actually have (a video has no
 * guidance scale; an image has no camera move), and the timestamp is formatted for the user's
 * locale and time zone.
 */
fun MediaItem.toInspector(locale: Locale, zone: ZoneId): InspectorModel {
    val p = parameters
    val rows = buildList {
        p.seed?.let { add(InspectorRow(InspectorField.SEED, it.toString(), technical = true)) }
        if (p.modelId.isNotBlank()) add(InspectorRow(InspectorField.MODEL, p.modelId, technical = true))
        add(InspectorRow(InspectorField.CREATED, formatTimestamp(createdAtEpochMillis, locale, zone), technical = false))
        add(InspectorRow(InspectorField.SIZE, "$width × $height", technical = true))
        add(InspectorRow(InspectorField.FRAME, p.aspectRatio.label, technical = true))
        when (type) {
            MediaType.VIDEO -> {
                durationMillis?.let { add(InspectorRow(InspectorField.DURATION, formatDuration(it), technical = true)) }
                p.cameraMotion?.let { add(InspectorRow(InspectorField.CAMERA, it.displayName, technical = false)) }
            }
            MediaType.IMAGE -> {
                add(InspectorRow(InspectorField.STEPS, p.steps.toString(), technical = true))
                add(InspectorRow(InspectorField.GUIDANCE, String.format(Locale.US, "%.1f", p.guidanceScale), technical = true))
            }
        }
        add(InspectorRow(InspectorField.FILE_SIZE, formatBytes(sizeBytes), technical = true))
        if (p.sourceImageUri != null) add(InspectorRow(InspectorField.SOURCE, "photo", technical = false))
    }
    return InspectorModel(
        prompt = p.prompt,
        negativePrompt = p.negativePrompt.takeIf { it.isNotBlank() },
        rows = rows,
    )
}

fun formatTimestamp(epochMillis: Long, locale: Locale, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(epochMillis).atZone(zone))

/** `m:ss`, rounding to the nearest second so a 4.9 s clip reads `0:05`. */
fun formatDuration(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0) + HALF_SECOND) / MILLIS_PER_SECOND
    return "%d:%02d".format(totalSeconds / SECONDS_PER_MINUTE, totalSeconds % SECONDS_PER_MINUTE)
}

fun formatBytes(bytes: Long): String = when {
    bytes < KIB -> "$bytes B"
    bytes < MIB -> String.format(Locale.US, "%.0f KB", bytes / KIB.toDouble())
    bytes < GIB -> String.format(Locale.US, "%.1f MB", bytes / MIB.toDouble())
    else -> String.format(Locale.US, "%.2f GB", bytes / GIB.toDouble())
}

private const val HALF_SECOND = 500L
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val KIB = 1_024L
private const val MIB = KIB * 1_024
private const val GIB = MIB * 1_024
