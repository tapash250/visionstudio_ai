package com.visionstudio.core.common.export

import java.util.zip.CRC32

/**
 * Makes the `UserComment` tag (0x9286) spec-compliant: EXIF 2.32 §4.6.5 defines it as type
 * UNDEFINED whose first 8 bytes name the character code, `ASCII\0\0\0` for ITU-T T.50 (ASCII).
 *
 * The AndroidX writer has no byte-level setter and stores every string as type ASCII. So the
 * value is written through it with the 8-byte code already in front ([withAsciiCode]), and
 * [markUndefined] then changes only the entry's 2-byte type field from ASCII (2) to UNDEFINED (7).
 * Both types count single bytes, so the value bytes stay exactly where the writer put them; only
 * the ASCII terminator is dropped from the count. AndroidX's reader strips the code for either
 * type, so the JSON still reads back as-is.
 */
internal object ExifUserComment {

    /** The 8-byte character code for ASCII, as a string the AndroidX writer will store verbatim. */
    const val ASCII_CODE: String = "ASCII\u0000\u0000\u0000"

    fun withAsciiCode(comment: String): String = ASCII_CODE + comment

    /**
     * Finds the EXIF block in a JPEG, PNG or WebP file held in [file] and retypes its `UserComment`
     * entry in place, updating the PNG chunk CRC when needed. Returns false, leaving [file]
     * unchanged, if the layout is not exactly what the writer produces: no EXIF block, no
     * `UserComment`, an unexpected type, or a value that doesn't start with [ASCII_CODE].
     */
    fun markUndefined(file: ByteArray): Boolean {
        val block = locateExif(file) ?: return false
        if (!retypeUserComment(file, block.start, block.end)) return false
        if (block.pngChunkTypeAt != null) writePngCrc(file, typeAt = block.pngChunkTypeAt, crcAt = block.end)
        return true
    }

    /**
     * The TIFF structure inside a container, from [start] to [end]. [pngChunkTypeAt] is set when it
     * is a PNG chunk, whose CRC (stored at [end]) covers the chunk type and data.
     */
    private class ExifBlock(val start: Int, val end: Int, val pngChunkTypeAt: Int? = null)

    private fun locateExif(b: ByteArray): ExifBlock? = when {
        b.startsWith(0, JPEG_SOI) -> locateInJpeg(b)
        b.startsWith(0, PNG_SIGNATURE) -> locateInPng(b)
        b.startsWith(0, RIFF) && b.startsWith(8, WEBP) -> locateInWebp(b)
        else -> null
    }

    private fun locateInJpeg(b: ByteArray): ExifBlock? {
        var i = JPEG_SOI.size
        while (i + 4 <= b.size && b.u8(i) == 0xFF) {
            val marker = b.u8(i + 1)
            if (marker == JPEG_SOS || marker == JPEG_EOI) return null
            if (marker in JPEG_STANDALONE) {
                i += 2
                continue
            }
            val length = b.u16be(i + 2)
            val end = i + 2 + length
            if (length < 2 || end > b.size) return null
            if (marker == JPEG_APP1 && b.startsWith(i + 4, EXIF_HEADER)) {
                return ExifBlock(start = i + 4 + EXIF_HEADER.size, end = end)
            }
            i = end
        }
        return null
    }

    private fun locateInPng(b: ByteArray): ExifBlock? {
        var i = PNG_SIGNATURE.size
        while (i + 12 <= b.size) {
            val length = b.u32be(i)
            val dataStart = i + 8
            val dataEnd = dataStart.toLong() + length
            if (length < 0 || dataEnd + 4 > b.size) return null
            if (b.startsWith(i + 4, PNG_EXIF)) return ExifBlock(dataStart, dataEnd.toInt(), pngChunkTypeAt = i + 4)
            if (b.startsWith(i + 4, PNG_IEND)) return null
            i = dataEnd.toInt() + 4
        }
        return null
    }

    private fun locateInWebp(b: ByteArray): ExifBlock? {
        var i = 12
        while (i + 8 <= b.size) {
            val size = b.u32le(i + 4)
            val dataStart = i + 8
            val dataEnd = dataStart.toLong() + size
            if (size < 0 || dataEnd > b.size) return null
            if (b.startsWith(i, WEBP_EXIF)) {
                // Some writers keep the JPEG-style "Exif\0\0" header inside the chunk.
                val start = if (b.startsWith(dataStart, EXIF_HEADER)) dataStart + EXIF_HEADER.size else dataStart
                return ExifBlock(start, dataEnd.toInt())
            }
            i = dataEnd.toInt() + (size and 1) // chunks are padded to an even size
        }
        return null
    }

    /** Walks IFD0 to the Exif IFD and retypes its UserComment entry. [start]..[end] bound the TIFF block. */
    private fun retypeUserComment(b: ByteArray, start: Int, end: Int): Boolean {
        if (end - start < TIFF_HEADER_SIZE) return false
        val tiff = Tiff(b, start, end, littleEndian = b.startsWith(start, TIFF_LITTLE_ENDIAN))
        if (!tiff.littleEndian && !b.startsWith(start, TIFF_BIG_ENDIAN)) return false
        if (tiff.u16(2) != TIFF_MAGIC) return false

        val exifIfdEntry = tiff.findEntry(ifdOffset = tiff.u32(4), tag = TAG_EXIF_IFD_POINTER) ?: return false
        val commentEntry = tiff.findEntry(ifdOffset = tiff.u32(exifIfdEntry + 8), tag = TAG_USER_COMMENT) ?: return false

        if (tiff.u16(commentEntry + 2) != TYPE_ASCII) return false
        val count = tiff.u32(commentEntry + 4)
        if (count < ASCII_CODE.length) return false
        // Values longer than 4 bytes live at an offset; ours always does, since the code alone is 8.
        val valueAt = tiff.u32(commentEntry + 8)
        if (!tiff.contains(valueAt, count) || !b.startsWith(start + valueAt, ASCII_CODE_BYTES)) return false

        tiff.putU16(commentEntry + 2, TYPE_UNDEFINED)
        // The ASCII writer appended a NUL terminator; an UNDEFINED value has none, so leave it out
        // of the count (the byte stays behind as padding).
        if (b[start + valueAt + count - 1] == 0.toByte()) tiff.putU32(commentEntry + 4, count - 1)
        return true
    }

    /** A TIFF block within [b], addressed by offsets relative to [start] as the format requires. */
    private class Tiff(val b: ByteArray, val start: Int, val end: Int, val littleEndian: Boolean) {
        fun contains(offset: Int, length: Int): Boolean =
            offset >= 0 && length >= 0 && start.toLong() + offset + length <= end

        fun u16(offset: Int): Int {
            check(contains(offset, 2))
            val at = start + offset
            return if (littleEndian) b.u8(at) or (b.u8(at + 1) shl 8) else (b.u8(at) shl 8) or b.u8(at + 1)
        }

        /** Unsigned 32-bit value; anything past Int.MAX_VALUE cannot be a valid offset here, so it maps to -1. */
        fun u32(offset: Int): Int {
            check(contains(offset, 4))
            val at = start + offset
            val value = if (littleEndian) {
                b.u8(at).toLong() or (b.u8(at + 1).toLong() shl 8) or (b.u8(at + 2).toLong() shl 16) or (b.u8(at + 3).toLong() shl 24)
            } else {
                (b.u8(at).toLong() shl 24) or (b.u8(at + 1).toLong() shl 16) or (b.u8(at + 2).toLong() shl 8) or b.u8(at + 3).toLong()
            }
            return if (value > Int.MAX_VALUE) -1 else value.toInt()
        }

        fun putU16(offset: Int, value: Int) {
            check(contains(offset, 2))
            val at = start + offset
            val hi = (value shr 8 and 0xFF).toByte()
            val lo = (value and 0xFF).toByte()
            if (littleEndian) {
                b[at] = lo
                b[at + 1] = hi
            } else {
                b[at] = hi
                b[at + 1] = lo
            }
        }

        fun putU32(offset: Int, value: Int) {
            check(contains(offset, 4))
            val at = start + offset
            val bytes = (0 until 4).map { (value shr (8 * it) and 0xFF).toByte() }
            (if (littleEndian) bytes else bytes.reversed()).forEachIndexed { i, byte -> b[at + i] = byte }
        }

        /** Offset of the 12-byte entry for [tag] in the IFD at [ifdOffset], or null. */
        fun findEntry(ifdOffset: Int, tag: Int): Int? {
            if (!contains(ifdOffset, 2)) return null
            val entries = u16(ifdOffset)
            if (!contains(ifdOffset + 2, entries * IFD_ENTRY_SIZE)) return null
            return (0 until entries)
                .map { ifdOffset + 2 + it * IFD_ENTRY_SIZE }
                .firstOrNull { u16(it) == tag }
        }
    }

    private fun writePngCrc(b: ByteArray, typeAt: Int, crcAt: Int) {
        val crc = CRC32().apply { update(b, typeAt, crcAt - typeAt) }.value
        b[crcAt] = (crc shr 24 and 0xFF).toByte()
        b[crcAt + 1] = (crc shr 16 and 0xFF).toByte()
        b[crcAt + 2] = (crc shr 8 and 0xFF).toByte()
        b[crcAt + 3] = (crc and 0xFF).toByte()
    }

    private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF
    private fun ByteArray.u16be(at: Int): Int = (u8(at) shl 8) or u8(at + 1)
    private fun ByteArray.u32be(at: Int): Int {
        val value = (u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()
        return if (value > Int.MAX_VALUE) -1 else value.toInt()
    }
    private fun ByteArray.u32le(at: Int): Int {
        val value = u8(at).toLong() or (u8(at + 1).toLong() shl 8) or (u8(at + 2).toLong() shl 16) or (u8(at + 3).toLong() shl 24)
        return if (value > Int.MAX_VALUE) -1 else value.toInt()
    }
    private fun ByteArray.startsWith(at: Int, prefix: ByteArray): Boolean =
        at >= 0 && at + prefix.size <= size && prefix.indices.all { this[at + it] == prefix[it] }

    private val ASCII_CODE_BYTES = ASCII_CODE.toByteArray(Charsets.US_ASCII)
    private val EXIF_HEADER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) // "Exif\0\0"
    private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val PNG_EXIF = "eXIf".toByteArray(Charsets.US_ASCII)
    private val PNG_IEND = "IEND".toByteArray(Charsets.US_ASCII)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
    private val WEBP_EXIF = "EXIF".toByteArray(Charsets.US_ASCII)
    private val TIFF_LITTLE_ENDIAN = "II".toByteArray(Charsets.US_ASCII)
    private val TIFF_BIG_ENDIAN = "MM".toByteArray(Charsets.US_ASCII)

    private const val JPEG_APP1 = 0xE1
    private const val JPEG_SOS = 0xDA
    private const val JPEG_EOI = 0xD9
    private val JPEG_STANDALONE = setOf(0x01) + (0xD0..0xD7)
    private const val TIFF_MAGIC = 42
    private const val TIFF_HEADER_SIZE = 8
    private const val IFD_ENTRY_SIZE = 12
    private const val TAG_EXIF_IFD_POINTER = 0x8769
    private const val TAG_USER_COMMENT = 0x9286
    private const val TYPE_ASCII = 2
    private const val TYPE_UNDEFINED = 7
}
