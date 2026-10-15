package com.visionstudio.feature.gallery.model

import androidx.compose.runtime.Immutable
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class GalleryFilter {
    ALL,
    IMAGES,
    VIDEOS,
    ;

    fun accepts(item: MediaItem): Boolean = when (this) {
        ALL -> true
        IMAGES -> item.type == MediaType.IMAGE
        VIDEOS -> item.type == MediaType.VIDEO
    }
}

/** The day a section of the grid belongs to, resolved relative to "now" so the UI can say "Today". */
@Immutable
sealed interface DayBucket {
    data object Today : DayBucket
    data object Yesterday : DayBucket
    data class On(val date: LocalDate) : DayBucket
}

/** One row of the grid: a full-width day header, or a media tile. */
@Immutable
sealed interface GridEntry {
    val key: String

    data class Header(val bucket: DayBucket, val count: Int) : GridEntry {
        override val key: String get() = "header-${bucket.keyPart()}"
    }

    data class Tile(val item: MediaItem) : GridEntry {
        override val key: String get() = "media-${item.id}"
    }
}

private fun DayBucket.keyPart(): String = when (this) {
    DayBucket.Today -> "today"
    DayBucket.Yesterday -> "yesterday"
    is DayBucket.On -> date.toString()
}

/**
 * Filters [items], orders them newest first, and lays them out under a header per calendar day
 * (in [zone], so "yesterday" means the user's yesterday). Items sharing a day keep their order.
 */
fun buildGrid(
    items: List<MediaItem>,
    filter: GalleryFilter,
    nowEpochMillis: Long,
    zone: ZoneId,
): List<GridEntry> {
    val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()
    val byDay = items
        .asSequence()
        .filter(filter::accepts)
        .sortedByDescending { it.createdAtEpochMillis }
        .groupBy { Instant.ofEpochMilli(it.createdAtEpochMillis).atZone(zone).toLocalDate() }

    return buildList {
        // groupBy keeps first-seen order, which after the sort is newest day first.
        for ((date, dayItems) in byDay) {
            val bucket = when (date) {
                today -> DayBucket.Today
                today.minusDays(1) -> DayBucket.Yesterday
                else -> DayBucket.On(date)
            }
            add(GridEntry.Header(bucket, dayItems.size))
            dayItems.forEach { add(GridEntry.Tile(it)) }
        }
    }
}

/** A video tile currently on screen, in viewport pixels. */
data class VisibleVideo(val id: String, val top: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

/**
 * Chooses the one video that should autoplay: among tiles at least [minVisibleFraction] on
 * screen, the one whose centre is closest to the middle of the viewport. Returns null when no
 * tile qualifies. Ties go to the earlier (higher) tile so the choice is stable.
 */
fun pickActiveVideo(
    visible: List<VisibleVideo>,
    viewportStart: Int,
    viewportEnd: Int,
    minVisibleFraction: Float = MIN_VISIBLE_FRACTION,
): String? {
    val viewportCenter = (viewportStart + viewportEnd) / 2f
    return visible
        .filter { it.height > 0 }
        .filter { video ->
            val shown = (minOf(video.bottom, viewportEnd) - maxOf(video.top, viewportStart)).coerceAtLeast(0)
            shown.toFloat() / video.height >= minVisibleFraction
        }
        .minWithOrNull(compareBy<VisibleVideo> { kotlin.math.abs((it.top + it.bottom) / 2f - viewportCenter) }.thenBy { it.top })
        ?.id
}

const val MIN_VISIBLE_FRACTION: Float = 0.6f
