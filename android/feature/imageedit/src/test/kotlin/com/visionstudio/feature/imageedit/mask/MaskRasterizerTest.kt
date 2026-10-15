package com.visionstudio.feature.imageedit.mask

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MaskRasterizerTest {

    private val rasterizer = MaskRasterizer(Dispatchers.Default)

    private fun stroke(vararg xy: Float, diameter: Float = 0.2f, erase: Boolean = false) =
        MaskStroke(xy, diameter, erase)

    private fun decode(mask: RasterizedMask): Bitmap =
        BitmapFactory.decodeByteArray(mask.png.bytes, 0, mask.png.bytes.size)

    @Test
    fun `no strokes produces an entirely black mask`() = runBlocking {
        val mask = rasterizer.rasterize(emptyList(), 40, 30)

        assertTrue(mask.isEmpty)
        assertEquals(0f, mask.coverage, 0f)
        val bitmap = decode(mask)
        assertEquals(40, bitmap.width)
        assertEquals(30, bitmap.height)
        for (y in 0 until 30) for (x in 0 until 40) assertEquals(BLACK, bitmap.getPixel(x, y))
    }

    @Test
    fun `every pixel is exactly black or white`() = runBlocking {
        val mask = rasterizer.rasterize(
            listOf(
                stroke(0.1f, 0.2f, 0.5f, 0.7f, 0.9f, 0.3f, diameter = 0.13f),
                stroke(0.2f, 0.9f, 0.8f, 0.1f, diameter = 0.07f),
            ),
            97,
            61,
        )
        val bitmap = decode(mask)
        var white = 0L
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                assertTrue("pixel ($x,$y) = ${Integer.toHexString(pixel)}", pixel == WHITE || pixel == BLACK)
                if (pixel == WHITE) white++
            }
        }
        assertEquals(white, mask.whitePixels)
        assertTrue(mask.coverage > 0f && mask.coverage < 1f)
    }

    @Test
    fun `brush paints white where drawn and leaves the rest black`() = runBlocking {
        // Horizontal stroke through the centre of a 100x100 mask, 20% diameter = 20px thick.
        val mask = rasterizer.rasterize(listOf(stroke(0.1f, 0.5f, 0.9f, 0.5f, 0.9f, 0.5f)), 100, 100)
        val bitmap = decode(mask)

        assertEquals(WHITE, bitmap.getPixel(50, 50))
        assertEquals(WHITE, bitmap.getPixel(50, 45))
        assertEquals(BLACK, bitmap.getPixel(50, 5))
        assertEquals(BLACK, bitmap.getPixel(50, 95))
        // The round cap reaches a full radius (10px) past the first sample at x=10.
        assertEquals(WHITE, bitmap.getPixel(2, 50))
        assertEquals(BLACK, bitmap.getPixel(1, 10))
    }

    @Test
    fun `a single tap becomes a round dot`() = runBlocking {
        val mask = rasterizer.rasterize(listOf(stroke(0.5f, 0.5f, diameter = 0.3f)), 100, 100)
        val bitmap = decode(mask)

        assertEquals(WHITE, bitmap.getPixel(50, 50))
        assertEquals(WHITE, bitmap.getPixel(50, 40))
        assertEquals(BLACK, bitmap.getPixel(50, 20))
        // Round: the bounding-box corner of a 30px dot is outside the circle.
        assertEquals(BLACK, bitmap.getPixel(36, 36))
        val expectedArea = Math.PI * 15 * 15
        assertEquals(expectedArea, mask.whitePixels.toDouble(), expectedArea * 0.1)
    }

    @Test
    fun `eraser cuts into earlier brush strokes and order matters`() = runBlocking {
        val paint = stroke(0.1f, 0.5f, 0.9f, 0.5f, 0.9f, 0.5f, diameter = 0.3f)
        val erase = stroke(0.5f, 0.5f, diameter = 0.1f, erase = true)

        val erased = decode(rasterizer.rasterize(listOf(paint, erase), 100, 100))
        val erasedFirst = decode(rasterizer.rasterize(listOf(erase, paint), 100, 100))

        assertEquals("eraser hole after paint", BLACK, erased.getPixel(50, 50))
        assertEquals("paint beside the hole", WHITE, erased.getPixel(70, 50))
        assertEquals("paint over an earlier erase", WHITE, erasedFirst.getPixel(50, 50))
    }

    @Test
    fun `strokes scale with the output resolution`() = runBlocking {
        val strokes = listOf(stroke(0.2f, 0.2f, 0.8f, 0.8f, 0.8f, 0.8f, diameter = 0.1f))
        val small = rasterizer.rasterize(strokes, 100, 100)
        val large = rasterizer.rasterize(strokes, 400, 400)

        assertEquals(small.coverage, large.coverage, 0.01f)
    }

    @Test
    fun `strokes beyond the frame are clipped without error`() = runBlocking {
        // A 50px-wide dot (radius 25) centred on the bottom-right corner of a 100px frame: only a quarter shows.
        val mask = rasterizer.rasterize(listOf(stroke(1f, 1f, diameter = 0.5f)), 100, 100)

        val expectedQuarter = Math.PI * 25 * 25 / 4
        assertEquals(expectedQuarter, mask.whitePixels.toDouble(), expectedQuarter * 0.08)
        assertEquals(WHITE, decode(mask).getPixel(99, 99))
    }

    @Test
    fun `non-positive size is rejected`() {
        val error = runCatching { runBlocking { rasterizer.rasterize(emptyList(), 0, 10) } }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `cancellation stops rasterization`() {
        val many = List(5_000) { stroke(0.1f, 0.1f, 0.9f, 0.9f, 0.9f, 0.9f, diameter = 0.05f) }
        val outcome = runCatching {
            runBlocking {
                kotlinx.coroutines.withTimeout(1) { rasterizer.rasterize(many, 2048, 2048) }
            }
        }
        assertTrue(outcome.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
    }

    @Test
    fun `result png is opaque`() = runBlocking {
        val bitmap = decode(rasterizer.rasterize(listOf(stroke(0.5f, 0.5f)), 16, 16))
        for (y in 0 until 16) for (x in 0 until 16) assertEquals(0xFF, bitmap.getPixel(x, y) ushr 24)
        assertFalse(bitmap.hasAlpha() && bitmap.getPixel(0, 0) ushr 24 != 0xFF)
    }

    private companion object {
        const val WHITE = -0x1
        const val BLACK = -0x1000000
    }
}
