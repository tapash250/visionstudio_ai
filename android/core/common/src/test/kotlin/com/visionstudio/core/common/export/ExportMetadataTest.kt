package com.visionstudio.core.common.export

import com.visionstudio.core.model.AspectRatio
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ExportMetadataTest {

    private val zone = ZoneId.of("UTC")
    private val created = ZonedDateTime.of(2026, 10, 12, 15, 30, 0, 0, zone).toInstant().toEpochMilli()

    private fun image(
        parameters: GenerationParameters = GenerationParameters(
            prompt = "a glass koi", modelId = "flux/dev", seed = 421337, aspectRatio = AspectRatio.LANDSCAPE_16_9,
        ),
        id: String = "abcdef12-3456",
        mime: String = "image/png",
    ) = MediaItem(
        id = id, taskId = "t", type = MediaType.IMAGE, localUri = "file:///i", mimeType = mime, width = 1024, height = 576,
        sizeBytes = 1, parameters = parameters, createdAtEpochMillis = created,
    )

    private fun video(parameters: GenerationParameters) = MediaItem(
        id = "vid-1", taskId = "t", type = MediaType.VIDEO, localUri = "file:///v", mimeType = "video/mp4", width = 1280,
        height = 720, durationMillis = 5_000, sizeBytes = 1, parameters = parameters, createdAtEpochMillis = created,
    )

    private fun String.parse(): JsonObject = Json.parseToJsonElement(this).jsonObject
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content

    @Test
    fun `the comment is valid json carrying the generation parameters`() {
        val json = image().generationComment().parse()

        assertEquals(EXPORT_APP_NAME, json.text("app"))
        assertEquals("a glass koi", json.text("prompt"))
        assertEquals("flux/dev", json.text("model"))
        assertEquals("421337", json.text("seed"))
        assertEquals("1024", json.text("width"))
        assertEquals("16:9", json.text("aspect_ratio"))
        assertEquals("30", json.text("steps"))
        assertEquals("7.0", json.text("guidance_scale"))
        assertEquals("2026-10-12T15:30:00Z", json.text("created_at"))
        assertEquals("image", json.text("type"))
    }

    @Test
    fun `values the item does not have are left out, not written as blanks`() {
        val json = image(GenerationParameters(prompt = "p", modelId = "")).generationComment().parse()

        assertFalse(json.containsKey("seed"))
        assertFalse(json.containsKey("model"))
        assertFalse(json.containsKey("negative_prompt"))
        assertFalse(json.containsKey("strength"))
    }

    @Test
    fun `negative prompt and strength are included when set`() {
        val json = image(GenerationParameters(prompt = "p", negativePrompt = "blurry", modelId = "m", strength = 0.6f)).generationComment().parse()

        assertEquals("blurry", json.text("negative_prompt"))
        assertEquals("0.6", json.text("strength"))
    }

    @Test
    fun `video comments omit image-only settings`() {
        val json = video(GenerationParameters(prompt = "dolly", modelId = "kling")).generationComment().parse()

        assertEquals("video", json.text("type"))
        assertNull(json["steps"])
        assertNull(json["guidance_scale"])
    }

    @Test
    fun `non-ascii prompts are escaped so the bytes are pure ascii, and still round trip`() {
        val prompt = "金魚 in the 雨, café ☕ 🐟"
        val comment = image(GenerationParameters(prompt = prompt, modelId = "m")).generationComment()

        assertTrue("every char is 7-bit: $comment", comment.all { it.code < 0x80 })
        assertEquals("a parser restores the original text exactly", prompt, comment.parse().text("prompt"))
    }

    @Test
    fun `quotes, backslashes and newlines in a prompt cannot break the json`() {
        val prompt = "she said \"hi\" \\ and\nleft\ttab"
        val json = image(GenerationParameters(prompt = prompt, modelId = "m")).generationComment().parse()

        assertEquals(prompt, json.text("prompt"))
    }

    @Test
    fun `an oversized prompt is trimmed so the exif block still fits`() {
        val huge = "金".repeat(20_000) // 6 bytes each once escaped
        val item = image(GenerationParameters(prompt = huge, negativePrompt = "x".repeat(5_000), modelId = "m"))

        val comment = item.generationComment()

        assertTrue("fits: ${comment.length}", comment.length <= 48_000)
        assertNull("negative prompt dropped first", comment.parse()["negative_prompt"])
        assertTrue(comment.parse().text("prompt")!!.isNotEmpty())
    }

    // region file names

    @Test
    fun `file name carries the time and seed`() {
        assertEquals("VisionStudioAI_20261012_153000_421337.png", image().exportFileName(zone))
    }

    @Test
    fun `file name uses the users time zone`() {
        assertEquals("VisionStudioAI_20261013_003000_421337.png", image().exportFileName(ZoneId.of("Asia/Tokyo")))
    }

    @Test
    fun `without a seed the name falls back to the id`() {
        val name = image(GenerationParameters(prompt = "p", modelId = "m"), id = "abcdef12-3456").exportFileName(zone)
        assertEquals("VisionStudioAI_20261012_153000_abcdef12.png", name)
    }

    @Test
    fun `extension follows the real mime type, not the media type`() {
        assertEquals("jpg", image(mime = "image/jpeg").exportFileName(zone).substringAfterLast('.'))
        assertEquals("webp", image(mime = "image/webp").exportFileName(zone).substringAfterLast('.'))
        assertEquals("mp4", video(GenerationParameters(prompt = "p", modelId = "m")).exportFileName(zone).substringAfterLast('.'))
        assertEquals("png", image(mime = "application/octet-stream").exportFileName(zone).substringAfterLast('.'))
    }

    @Test
    fun `hostile ids cannot inject path characters`() {
        val name = image(GenerationParameters(prompt = "p", modelId = "m"), id = "../../etc/pa ss").exportFileName(zone)

        assertTrue("safe characters only: $name", name.matches(Regex("[A-Za-z0-9_.-]+")))
        assertFalse(name.contains("/"))
        assertFalse(name.contains(".."))
    }

    @Test
    fun `exif dates use the fixed colon format`() {
        assertEquals("2026:10:12 15:30:00", exifDateTime(created, zone))
        assertEquals("2026:10:13 00:30:00", exifDateTime(created, ZoneId.of("Asia/Tokyo")))
    }

    // endregion
}
