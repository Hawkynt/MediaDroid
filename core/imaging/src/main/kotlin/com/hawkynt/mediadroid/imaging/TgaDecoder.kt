package com.hawkynt.mediadroid.imaging

/**
 * Truevision TGA: colour-mapped, true-colour and greyscale pictures, raw or run-length encoded, from
 * any of the four origins. Converted from the TGA loader of Sean Barrett's stb_image (public domain /
 * MIT, see THIRD_PARTY_NOTICES.md), with two corrections from the TGA 2.0 specification stb leaves
 * out: colour-map indices count from the map's first entry, and the right-to-left origin bit is honoured.
 */
object TgaDecoder {

    private const val HEADER_SIZE = 18

    /** The picture, or null when [bytes] is no TGA this decoder understands. */
    fun decode(bytes: ByteArray, maxPixels: Int = Int.MAX_VALUE): RasterImage? =
        runCatching { decodeOrThrow(bytes, maxPixels) }.getOrNull()

    private fun decodeOrThrow(bytes: ByteArray, maxPixels: Int): RasterImage? {
        if (bytes.size < HEADER_SIZE) return null
        val input = Input(bytes)
        val idLength = input.u8()
        val colourMapType = input.u8()
        val imageType = input.u8()
        val mapFirst = input.u16()
        val mapLength = input.u16()
        val mapBits = input.u8()
        input.skip(4) // x and y origin of the picture on screen
        val width = input.u16()
        val height = input.u16()
        val bits = input.u8()
        val descriptor = input.u8()

        if (colourMapType > 1 || width < 1 || height < 1) return null
        val rle = imageType >= 8
        val kind = imageType and 7
        val indexed = kind == 1
        when (kind) {
            1 -> if (colourMapType != 1 || (bits != 8 && bits != 16)) return null
            2 -> if (bits !in setOf(15, 16, 24, 32)) return null
            3 -> if (bits != 8 && bits != 16) return null
            else -> return null
        }

        input.skip(idLength)
        // A colour map may be present even for a true-colour picture; it is then simply skipped.
        val palette = if (colourMapType == 1) {
            if (mapBits !in setOf(15, 16, 24, 32) || mapLength == 0) return null
            IntArray(mapLength) { readTrueColour(input, mapBits, alphaBits = if (mapBits == 32) 8 else 0) }
        } else {
            null
        }
        val alphaBits = descriptor and 0x0F
        val rightToLeft = descriptor and 0x10 != 0
        val topDown = descriptor and 0x20 != 0

        fun readPixel(): Int = when {
            indexed -> {
                val index = (if (bits == 8) input.u8() else input.u16()) - mapFirst
                palette!!.getOrElse(index) { palette[0] }
            }
            kind == 3 -> if (bits == 8) grey(input.u8()) else input.u8().let { v -> argb(input.u8(), v, v, v) }
            else -> readTrueColour(input, bits, alphaBits)
        }

        val out = RasterBuilder(width, height, maxPixels)
        var run = 0
        var repeating = false
        var pixel = 0
        for (i in 0 until width * height) {
            if (rle) {
                if (run == 0) {
                    val packet = input.u8()
                    run = (packet and 0x7F) + 1
                    repeating = packet and 0x80 != 0
                    pixel = readPixel()
                } else if (!repeating) {
                    pixel = readPixel()
                }
                run--
            } else {
                pixel = readPixel()
            }
            val col = i % width
            val row = i / width
            val x = if (rightToLeft) width - 1 - col else col
            val y = if (topDown) row else height - 1 - row
            out.set(x, y, pixel)
        }
        return out.build()
    }

    /**
     * One true-colour value stored little-endian as B, G, R (and A). 16-bit values are 5:5:5 plus a
     * top bit that Pillow — the oracle here — reads as inverted alpha: set means transparent. stb
     * ignores the bit; Pillow's reading is the one checked against a real file (`rgba16.tga`).
     */
    private fun readTrueColour(input: Input, bits: Int, alphaBits: Int): Int = when (bits) {
        15, 16 -> {
            val v = input.u16()
            val r = (v shr 10 and 0x1F) * 255 / 31
            val g = (v shr 5 and 0x1F) * 255 / 31
            val b = (v and 0x1F) * 255 / 31
            val a = if (bits == 16 && v and 0x8000 != 0) 0 else 0xFF
            argb(a, r, g, b)
        }
        24 -> {
            val b = input.u8()
            val g = input.u8()
            val r = input.u8()
            argb(0xFF, r, g, b)
        }
        else -> {
            val b = input.u8()
            val g = input.u8()
            val r = input.u8()
            val a = input.u8()
            argb(if (alphaBits == 0) 0xFF else a, r, g, b)
        }
    }

    private class Input(private val bytes: ByteArray) {
        private var pos = 0
        fun u8(): Int = bytes[pos++].toInt() and 0xFF
        fun u16(): Int = u8() or (u8() shl 8)
        fun skip(n: Int) {
            pos += n
        }
    }
}
