package com.visionstudio.feature.gallery.model

import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class GalleryModelTest {

    private val zone = ZoneId.of("America/New_York")
    private val now = ZonedDateTime.of(2026, 10, 12, 15, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun item(id: String, createdAt: Long, type: MediaType = MediaType.IMAGE) = MediaItem(
        id = id,
        taskId = "t",
        type = type,
        localUri = "file:///$id",
        width = 100,
        height = 100,
        durationMillis = if (type == MediaType.VIDEO) 5_000 else null,
        sizeBytes = 1,
        parameters = GenerationParameters(prompt = id, modelId = "m"),
        createdAtEpochMillis = createdAt,
    )

    private fun List<GridEntry>.describe() = map {
        when (it) {
            is GridEntry.Header -> "H:${it.bucket}:${it.count}"
            is GridEntry.Tile -> "T:${it.item.id}"
            GridEntry.More -> "MORE"
        }
    }

    @Test
    fun `a partial window withholds only the oldest day's count and ends with More`() {
        val items = listOf(item("today", now), item("old1", at(2025, 1, 5, hour = 9)), item("old2", at(2025, 1, 5, hour = 8)))

        val grid = buildGrid(items, GalleryFilter.ALL, now, zone, complete = false).describe()

        assertEquals(listOf("H:Today:1", "T:today", "H:On(date=2025-01-05):null", "T:old1", "T:old2", "MORE"), grid)
    }

    @Test
    fun `items created in the same millisecond are ordered by id, newest id first`() {
        val items = listOf(item("a", now), item("c", now), item("b", now))

        assertEquals(listOf("H:Today:3", "T:c", "T:b", "T:a"), buildGrid(items, GalleryFilter.ALL, now, zone).describe())
    }

    @Test
    fun `an empty partial window has no footer`() {
        assertTrue(buildGrid(emptyList(), GalleryFilter.ALL, now, zone, complete = false).isEmpty())
    }

    @Test
    fun `groups under today, yesterday and dated headers, newest first`() {
        val items = listOf(
            item("old", at(2026, 9, 30)),
            item("today-late", at(2026, 10, 12, 14)),
            item("yesterday", at(2026, 10, 11, 9)),
            item("today-early", at(2026, 10, 12, 8)),
        )

        val grid = buildGrid(items, GalleryFilter.ALL, now, zone)

        assertEquals(
            listOf(
                "H:Today:2", "T:today-late", "T:today-early",
                "H:Yesterday:1", "T:yesterday",
                "H:On(date=2026-09-30):1", "T:old",
            ),
            grid.describe(),
        )
    }

    @Test
    fun `the day boundary follows the users time zone, not UTC`() {
        // 23:30 New York on Oct 11 is 03:30 UTC on Oct 12: "yesterday" for this user, "today" in UTC.
        val lateEvening = at(2026, 10, 11, 23, 30)

        val grid = buildGrid(listOf(item("late", lateEvening)), GalleryFilter.ALL, now, zone)
        val inUtc = buildGrid(listOf(item("late", lateEvening)), GalleryFilter.ALL, now, ZoneId.of("UTC"))

        assertEquals(DayBucket.Yesterday, (grid.first() as GridEntry.Header).bucket)
        assertEquals(DayBucket.Today, (inUtc.first() as GridEntry.Header).bucket)
    }

    @Test
    fun `midnight sharp starts a new day`() {
        val grid = buildGrid(
            listOf(item("a", at(2026, 10, 12, 0, 0)), item("b", at(2026, 10, 11, 23, 59))),
            GalleryFilter.ALL, now, zone,
        )
        assertEquals(listOf("H:Today:1", "T:a", "H:Yesterday:1", "T:b"), grid.describe())
    }

    @Test
    fun `filters keep only the chosen media type and drop empty days`() {
        val items = listOf(
            item("img", at(2026, 10, 12, 10)),
            item("vid-today", at(2026, 10, 12, 11), MediaType.VIDEO),
            item("vid-old", at(2026, 8, 1), MediaType.VIDEO),
        )

        assertEquals(listOf("H:Today:1", "T:img"), buildGrid(items, GalleryFilter.IMAGES, now, zone).describe())
        assertEquals(
            listOf("H:Today:1", "T:vid-today", "H:On(date=2026-08-01):1", "T:vid-old"),
            buildGrid(items, GalleryFilter.VIDEOS, now, zone).describe(),
        )
        assertEquals(
            "no filter keeps everything: 2 day headers + 3 tiles",
            listOf("H:Today:2", "T:vid-today", "T:img", "H:On(date=2026-08-01):1", "T:vid-old"),
            buildGrid(items, GalleryFilter.ALL, now, zone).describe(),
        )
    }

    @Test
    fun `an empty library or a filter with no matches produces an empty grid`() {
        assertTrue(buildGrid(emptyList(), GalleryFilter.ALL, now, zone).isEmpty())
        assertTrue(buildGrid(listOf(item("img", now)), GalleryFilter.VIDEOS, now, zone).isEmpty())
    }

    @Test
    fun `keys are unique and stable`() {
        val items = listOf(item("a", at(2026, 10, 12)), item("b", at(2026, 10, 12, 13)), item("c", at(2026, 10, 11)))
        val keys = buildGrid(items, GalleryFilter.ALL, now, zone).map { it.key }

        assertEquals(keys.size, keys.toSet().size)
        assertEquals(listOf("header-today", "media-b", "media-a", "header-yesterday", "media-c"), keys)
    }

    @Test
    fun `dated header carries its date`() {
        val header = buildGrid(listOf(item("a", at(2025, 1, 5))), GalleryFilter.ALL, now, zone).first() as GridEntry.Header
        assertEquals(DayBucket.On(LocalDate.of(2025, 1, 5)), header.bucket)
        assertEquals("header-2025-01-05", header.key)
    }

    // region autoplay selection

    private fun v(id: String, top: Int, bottom: Int) = VisibleVideo(id, top, bottom)

    @Test
    fun `the video nearest the middle of the screen wins`() {
        val picked = pickActiveVideo(listOf(v("top", 0, 400), v("middle", 700, 1100), v("low", 1500, 1900)), 0, 2000)
        assertEquals("middle", picked)
    }

    @Test
    fun `a tile mostly scrolled off is not eligible`() {
        // Only 30% of "edge" is on screen.
        assertNull(pickActiveVideo(listOf(v("edge", -700, 300)), 0, 2000))
        // 50% is still under the 60% bar.
        assertNull(pickActiveVideo(listOf(v("half", -200, 200)), 0, 2000))
        // 75% qualifies.
        assertEquals("most", pickActiveVideo(listOf(v("most", -100, 300)), 0, 2000))
    }

    @Test
    fun `an ineligible tile never beats an eligible one even if closer to centre`() {
        val picked = pickActiveVideo(listOf(v("half-visible-central", 900, 1900), v("fully-visible", 0, 300)), 0, 1200)
        // The first is only 300/1000 on screen (bottom clipped at 1200 -> 300 shown); the second is fully visible.
        assertEquals("fully-visible", picked)
    }

    @Test
    fun `ties go to the higher tile so the choice is stable`() {
        val picked = pickActiveVideo(listOf(v("below", 1000, 1200), v("above", 800, 1000)), 0, 2000)
        assertEquals("above", picked)
    }

    @Test
    fun `no videos or degenerate tiles select nothing`() {
        assertNull(pickActiveVideo(emptyList(), 0, 2000))
        assertNull(pickActiveVideo(listOf(v("flat", 500, 500)), 0, 2000))
    }

    // endregion
}
