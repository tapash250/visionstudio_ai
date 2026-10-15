package com.visionstudio.feature.gallery.model

import com.visionstudio.core.model.AspectRatio
import com.visionstudio.core.model.CameraMotion
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class InspectorModelTest {

    private val zone = ZoneId.of("UTC")
    private val created = ZonedDateTime.of(2026, 10, 12, 15, 30, 0, 0, zone).toInstant().toEpochMilli()

    private fun image(parameters: GenerationParameters = GenerationParameters(prompt = "a glass koi", modelId = "flux/dev", seed = 421337)) = MediaItem(
        id = "i", taskId = "t", type = MediaType.IMAGE, localUri = "file:///i.png", width = 1024, height = 576,
        sizeBytes = 2_411_724, parameters = parameters, createdAtEpochMillis = created,
    )

    private fun video(parameters: GenerationParameters) = MediaItem(
        id = "v", taskId = "t", type = MediaType.VIDEO, localUri = "file:///v.mp4", width = 1280, height = 720,
        durationMillis = 4_900, sizeBytes = 8_000_000, parameters = parameters, createdAtEpochMillis = created,
    )

    private fun InspectorModel.row(field: InspectorField) = rows.firstOrNull { it.field == field }

    @Test
    fun `an image shows prompt, seed, model, timestamp and its render settings`() {
        val model = image().toInspector(Locale.US, zone)

        assertEquals("a glass koi", model.prompt)
        assertEquals("421337", model.row(InspectorField.SEED)!!.value)
        assertEquals("flux/dev", model.row(InspectorField.MODEL)!!.value)
        assertEquals("1024 × 576", model.row(InspectorField.SIZE)!!.value)
        assertEquals("30", model.row(InspectorField.STEPS)!!.value)
        assertEquals("7.0", model.row(InspectorField.GUIDANCE)!!.value)
        assertEquals("2.3 MB", model.row(InspectorField.FILE_SIZE)!!.value)
        assertNotNull(model.row(InspectorField.CREATED))
    }

    @Test
    fun `seed and model are technical and copyable, the date is not`() {
        val model = image().toInspector(Locale.US, zone)

        assertTrue(model.row(InspectorField.SEED)!!.technical)
        assertTrue(model.row(InspectorField.MODEL)!!.technical)
        assertFalse(model.row(InspectorField.CREATED)!!.technical)
    }

    @Test
    fun `the timestamp is localised and uses the given zone`() {
        val us = formatTimestamp(created, Locale.US, ZoneId.of("UTC"))
        val tokyo = formatTimestamp(created, Locale.US, ZoneId.of("Asia/Tokyo"))
        val german = formatTimestamp(created, Locale.GERMANY, ZoneId.of("UTC"))

        assertTrue("US format: $us", us.contains("Oct 12, 2026"))
        assertTrue("15:30 UTC is 3:30 PM: $us", us.contains("3:30"))
        assertTrue("Tokyo is UTC+9, so it is already Oct 13: $tokyo", tokyo.contains("Oct 13, 2026"))
        assertTrue("German month-day order differs: $german", german.contains("12.10.2026"))
    }

    @Test
    fun `a video shows duration and camera move but no image-only settings`() {
        val model = video(
            GenerationParameters(prompt = "dolly in", modelId = "kling", cameraMotion = CameraMotion.DOLLY_FORWARD, aspectRatio = AspectRatio.LANDSCAPE_16_9),
        ).toInspector(Locale.US, zone)

        assertEquals("0:05", model.row(InspectorField.DURATION)!!.value)
        assertEquals("Dolly Forward", model.row(InspectorField.CAMERA)!!.value)
        assertEquals("16:9", model.row(InspectorField.FRAME)!!.value)
        assertNull("videos have no step count", model.row(InspectorField.STEPS))
        assertNull("videos have no guidance", model.row(InspectorField.GUIDANCE))
    }

    @Test
    fun `an image never shows video-only rows`() {
        val model = image().toInspector(Locale.US, zone)
        assertNull(model.row(InspectorField.DURATION))
        assertNull(model.row(InspectorField.CAMERA))
    }

    @Test
    fun `missing facts are omitted rather than shown blank`() {
        val model = image(GenerationParameters(prompt = "p", modelId = "")).toInspector(Locale.US, zone)

        assertNull("no seed recorded", model.row(InspectorField.SEED))
        assertNull("no model recorded", model.row(InspectorField.MODEL))
        assertNull(model.row(InspectorField.SOURCE))
    }

    @Test
    fun `negative prompt appears only when set, and edits note their source photo`() {
        assertNull(image().toInspector(Locale.US, zone).negativePrompt)

        val edited = image(
            GenerationParameters(prompt = "p", negativePrompt = "blurry", modelId = "m", sourceImageUri = "content://photo"),
        ).toInspector(Locale.US, zone)

        assertEquals("blurry", edited.negativePrompt)
        assertNotNull(edited.row(InspectorField.SOURCE))
    }

    @Test
    fun `durations round to the nearest second`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:05", formatDuration(4_900))
        assertEquals("0:04", formatDuration(4_400))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("10:00", formatDuration(600_000))
        assertEquals("0:00", formatDuration(-5))
    }

    @Test
    fun `byte sizes use sensible units`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1_023))
        assertEquals("1 KB", formatBytes(1_024))
        assertEquals("512 KB", formatBytes(524_288))
        assertEquals("1.0 MB", formatBytes(1_048_576))
        assertEquals("2.3 MB", formatBytes(2_411_724))
        assertEquals("1.50 GB", formatBytes(1_610_612_736))
    }
}
