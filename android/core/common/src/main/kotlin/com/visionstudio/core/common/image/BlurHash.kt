package com.visionstudio.core.common.image

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.withSign

/**
 * BlurHash (https://blurha.sh) encoder and decoder on raw ARGB pixel arrays, in the
 * `Bitmap.getPixels` layout. Pure Kotlin and CPU-bound: call it on the Default dispatcher.
 *
 * Generated media gets a ~30-character hash at save time, so galleries and the T2I stage can
 * paint an instant colour-accurate placeholder before the full image decodes.
 */
object BlurHash {

    /** Encodes [pixels] (width × height ARGB). Components must be in 1..9. */
    fun encode(pixels: IntArray, width: Int, height: Int, componentsX: Int = 4, componentsY: Int = 3): String {
        require(componentsX in 1..MAX_COMPONENTS && componentsY in 1..MAX_COMPONENTS) { "Components must be in 1..9" }
        require(width > 0 && height > 0 && pixels.size >= width * height) { "Pixel buffer does not match ${width}x$height" }

        val linear = FloatArray(width * height * CHANNELS)
        for (i in 0 until width * height) {
            val argb = pixels[i]
            linear[i * CHANNELS] = srgbToLinear((argb shr 16) and 0xFF)
            linear[i * CHANNELS + 1] = srgbToLinear((argb shr 8) and 0xFF)
            linear[i * CHANNELS + 2] = srgbToLinear(argb and 0xFF)
        }
        val cosX = cosineTable(componentsX, width)
        val cosY = cosineTable(componentsY, height)

        val factors = Array(componentsX * componentsY) { FloatArray(CHANNELS) }
        for (j in 0 until componentsY) {
            for (i in 0 until componentsX) {
                val normalisation = if (i == 0 && j == 0) 1f else 2f
                var r = 0f
                var g = 0f
                var b = 0f
                for (y in 0 until height) {
                    val cy = cosY[j * height + y]
                    for (x in 0 until width) {
                        val basis = cosX[i * width + x] * cy
                        val p = (y * width + x) * CHANNELS
                        r += basis * linear[p]
                        g += basis * linear[p + 1]
                        b += basis * linear[p + 2]
                    }
                }
                val scale = normalisation / (width * height)
                factors[j * componentsX + i].apply {
                    this[0] = r * scale
                    this[1] = g * scale
                    this[2] = b * scale
                }
            }
        }

        val out = StringBuilder(4 + 2 * factors.size)
        encode83((componentsX - 1) + (componentsY - 1) * MAX_COMPONENTS, 1, out)

        val maximumValue: Float
        if (factors.size > 1) {
            var actualMax = 0f
            for (k in 1 until factors.size) for (c in 0 until CHANNELS) actualMax = max(actualMax, abs(factors[k][c]))
            val quantisedMax = floor(actualMax * QUANT_SCALE - 0.5f).toInt().coerceIn(0, MAX_QUANT)
            maximumValue = (quantisedMax + 1) / QUANT_SCALE
            encode83(quantisedMax, 1, out)
        } else {
            maximumValue = 1f
            encode83(0, 1, out)
        }

        val dc = factors[0]
        encode83((linearToSrgb(dc[0]) shl 16) + (linearToSrgb(dc[1]) shl 8) + linearToSrgb(dc[2]), DC_LENGTH, out)
        for (k in 1 until factors.size) {
            val f = factors[k]
            val qr = quantiseAc(f[0], maximumValue)
            val qg = quantiseAc(f[1], maximumValue)
            val qb = quantiseAc(f[2], maximumValue)
            encode83(qr * AC_BASE * AC_BASE + qg * AC_BASE + qb, 2, out)
        }
        return out.toString()
    }

    /**
     * Decodes [hash] into opaque ARGB pixels of [width] × [height], or null if the hash is
     * malformed. [punch] scales contrast; values above 1 exaggerate colour separation.
     */
    fun decode(hash: String, width: Int, height: Int, punch: Float = 1f): IntArray? {
        if (!isValid(hash) || width <= 0 || height <= 0) return null
        val sizeFlag = decode83(hash, 0, 1)
        val numY = sizeFlag / MAX_COMPONENTS + 1
        val numX = sizeFlag % MAX_COMPONENTS + 1
        val maxValue = (decode83(hash, 1, 2) + 1) / QUANT_SCALE * punch

        val colors = Array(numX * numY) { FloatArray(CHANNELS) }
        val dc = decode83(hash, 2, 2 + DC_LENGTH)
        colors[0][0] = srgbToLinear(dc shr 16)
        colors[0][1] = srgbToLinear((dc shr 8) and 0xFF)
        colors[0][2] = srgbToLinear(dc and 0xFF)
        for (k in 1 until colors.size) {
            val start = 2 + DC_LENGTH + (k - 1) * 2
            val value = decode83(hash, start, start + 2)
            colors[k][0] = unquantiseAc(value / (AC_BASE * AC_BASE), maxValue)
            colors[k][1] = unquantiseAc((value / AC_BASE) % AC_BASE, maxValue)
            colors[k][2] = unquantiseAc(value % AC_BASE, maxValue)
        }

        val cosX = cosineTable(numX, width)
        val cosY = cosineTable(numY, height)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (j in 0 until numY) {
                    val cy = cosY[j * height + y]
                    for (i in 0 until numX) {
                        val basis = cosX[i * width + x] * cy
                        val c = colors[j * numX + i]
                        r += c[0] * basis
                        g += c[1] * basis
                        b += c[2] * basis
                    }
                }
                pixels[y * width + x] = OPAQUE or (linearToSrgb(r) shl 16) or (linearToSrgb(g) shl 8) or linearToSrgb(b)
            }
        }
        return pixels
    }

    fun isValid(hash: String): Boolean {
        if (hash.length < MIN_LENGTH || hash.any { CHARSET.indexOf(it) == -1 }) return false
        val sizeFlag = CHARSET.indexOf(hash[0])
        val components = (sizeFlag / MAX_COMPONENTS + 1) * (sizeFlag % MAX_COMPONENTS + 1)
        return hash.length == 4 + 2 * components
    }

    private fun cosineTable(components: Int, size: Int): FloatArray =
        FloatArray(components * size) { index ->
            val component = index / size
            val position = index % size
            cos(PI * component * position / size).toFloat()
        }

    private fun quantiseAc(value: Float, maximum: Float): Int =
        floor(signedPow(value / maximum, 0.5f) * 9f + 9.5f).toInt().coerceIn(0, AC_BASE - 1)

    private fun unquantiseAc(quantised: Int, maximum: Float): Float =
        signedPow((quantised - 9f) / 9f, 2f) * maximum

    private fun signedPow(value: Float, exponent: Float): Float = abs(value).pow(exponent).withSign(value)

    private fun srgbToLinear(channel: Int): Float {
        val v = channel / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSrgb(value: Float): Int {
        val v = min(1f, max(0f, value))
        val srgb = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
        return (srgb * 255f).roundToInt().coerceIn(0, 255)
    }

    /** Appends [length] base-83 digits of [value], most significant first. */
    private fun encode83(value: Int, length: Int, out: StringBuilder) {
        for (i in 1..length) {
            var divisor = 1
            repeat(length - i) { divisor *= BASE }
            out.append(CHARSET[(value / divisor) % BASE])
        }
    }

    private fun decode83(hash: String, from: Int, to: Int): Int {
        var value = 0
        for (i in from until to) value = value * BASE + CHARSET.indexOf(hash[i])
        return value
    }

    private const val CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"
    private const val BASE = 83
    private const val CHANNELS = 3
    private const val MAX_COMPONENTS = 9
    private const val QUANT_SCALE = 166f
    private const val MAX_QUANT = 82
    private const val AC_BASE = 19
    private const val DC_LENGTH = 4
    private const val MIN_LENGTH = 6
    private const val OPAQUE = -0x1000000
}
