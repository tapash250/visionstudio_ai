package com.visionstudio.core.common.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BlurHashTest {

    private fun solid(argb: Int, w: Int = 16, h: Int = 16) = IntArray(w * h) { argb }

    private fun channel(argb: Int, shift: Int) = (argb shr shift) and 0xFF

    @Test
    fun `hash length matches component count`() {
        val hash = BlurHash.encode(solid(0xFF336699.toInt()), 16, 16, componentsX = 4, componentsY = 3)
        assertEquals(4 + 2 * 12, hash.length)
        assertTrue(BlurHash.isValid(hash))
    }

    // Expected values produced by the reference implementation (pip package `blurhash`).
    @Test
    fun `solid colour encodes identically to the reference implementation`() {
        assertEquals("LHBWp{tWfQtWtWj]fQj]fQfQfQfQ", BlurHash.encode(solid(0xFF6366F1.toInt()), 16, 16, 4, 3))
    }

    @Test
    fun `decode matches the reference implementation`() {
        val decoded = BlurHash.decode("LHBWp{tWfQtWtWj]fQj]fQfQfQfQ", 8, 8)!!
        val expected = mapOf((0 to 0) to intArrayOf(113, 123, 255), (5 to 3) to intArrayOf(103, 109, 253))
        expected.forEach { (xy, rgb) ->
            val px = decoded[xy.second * 8 + xy.first]
            assertEquals(0xFF, channel(px, 24))
            intArrayOf(16, 8, 0).forEachIndexed { c, shift -> assertTrue(abs(channel(px, shift) - rgb[c]) <= 1) }
        }
    }

    @Test
    fun `horizontal gradient preserves its direction`() {
        val w = 32
        val pixels = IntArray(w * 8) { i ->
            val v = (i % w) * 255 / (w - 1)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val decoded = BlurHash.decode(BlurHash.encode(pixels, w, 8), w, 8)!!
        assertTrue(channel(decoded[0], 16) < 80)
        assertTrue(channel(decoded[w - 1], 16) > 175)
    }

    @Test
    fun `canonical reference hash decodes`() {
        // Example hash from the BlurHash specification README.
        val decoded = BlurHash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdnj", 32, 32)
        assertNotNull(decoded)
        assertEquals(32 * 32, decoded!!.size)
    }

    @Test
    fun `multi-component image encodes identically to the reference implementation`() {
        val pixels = IntArray(20 * 20) { i -> (0xFF shl 24) or (((i * 7) and 0xFF) shl 16) or (((i * 3) and 0xFF) shl 8) or (i and 0xFF) }
        assertEquals("KfHU^4t6W-obbYSzZ_i{XR", BlurHash.encode(pixels, 20, 20, 3, 3))
    }

    @Test
    fun `malformed hashes are rejected`() {
        assertFalse(BlurHash.isValid("short"))
        assertFalse(BlurHash.isValid("LEHV6nWB2yk8pyo0adR*.7kCMdn")) // one char short
        assertFalse(BlurHash.isValid("LEHV6nWB2yk8pyo0adR*.7kCMdn\""))
        assertNull(BlurHash.decode("garbage!", 4, 4))
    }
}
