package com.hawkynt.mediadroid.imaging

/**
 * ZSoft Paintbrush PCX: black and white, 16 colours as four bit planes, 256 colours or greyscale with
 * the trailing palette, and 24-bit colour as three planes. Converted from Pillow's PCX plugin and
 * decoder (`PcxImagePlugin.py`, `PcxDecode.c`, MIT-CMU licence, see THIRD_PARTY_NOTICES.md),
 * including its distrust of the stored line width: it is recomputed from the picture width.
 */
object PcxDecoder {

    private const val HEADER_SIZE = 128
    private const val PALETTE_MARKER = 12
    private const val TRAILING_PALETTE_SIZE = 769

    /** The picture, or null when [bytes] is no PCX this decoder understands. */
    fun decode(bytes: ByteArray, maxPixels: Int = Int.MAX_VALUE): RasterImage? =
        runCatching { decodeOrThrow(bytes, maxPixels) }.getOrNull()

    /** The PCX signature: the ZSoft manufacturer byte and a known version. */
    fun accepts(bytes: ByteArray): Boolean =
        bytes.size >= HEADER_SIZE && bytes[0].toInt() == 10 && bytes[1].toInt() in setOf(0, 2, 3, 4, 5)

    private fun decodeOrThrow(bytes: ByteArray, maxPixels: Int): RasterImage? {
        if (!accepts(bytes)) return null
        fun u8(at: Int) = bytes[at].toInt() and 0xFF
        fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)

        val version = u8(1)
        val bits = u8(3)
        val width = u16(8) - u16(4) + 1
        val height = u16(10) - u16(6) + 1
        val planes = u8(65)
        val storedStride = u16(66)
        if (width <= 0 || height <= 0) return null

        val headerPalette = IntArray(16) { argb(0xFF, u8(16 + it * 3), u8(17 + it * 3), u8(18 + it * 3)) }
        val mode = when {
            bits == 1 && planes == 1 -> Mode.BILEVEL
            bits == 1 && (planes == 2 || planes == 4) -> Mode.PLANAR_PALETTE
            version == 5 && bits == 8 && planes == 1 -> Mode.PALETTE_OR_GREY
            version == 5 && bits == 8 && planes == 3 -> Mode.RGB
            else -> return null
        }

        // Pillow's rule: the stride is what the width needs, padded to even when the file disagrees.
        var stride = (width * bits + 7) / 8
        if (storedStride != stride) stride += stride % 2
        val lineBytes = planes * stride

        // The whole run-length stream at once, so a run that crosses a scan line just continues.
        val data = ByteArray(lineBytes * height)
        var pos = HEADER_SIZE
        var filled = 0
        while (filled < data.size && pos < bytes.size) {
            val b = u8(pos++)
            if (b and 0xC0 == 0xC0) {
                if (pos >= bytes.size) break
                val value = bytes[pos++]
                repeat(b and 0x3F) { if (filled < data.size) data[filled++] = value }
            } else {
                data[filled++] = b.toByte()
            }
        }

        val trailingPalette = if (mode == Mode.PALETTE_OR_GREY && bytes.size >= TRAILING_PALETTE_SIZE &&
            u8(bytes.size - TRAILING_PALETTE_SIZE) == PALETTE_MARKER
        ) {
            val start = bytes.size - TRAILING_PALETTE_SIZE + 1
            IntArray(256) { argb(0xFF, u8(start + it * 3), u8(start + it * 3 + 1), u8(start + it * 3 + 2)) }
        } else {
            null
        }

        val out = RasterBuilder(width, height, maxPixels)
        for (y in 0 until height) {
            if (!out.wantsRow(y)) continue
            val line = y * lineBytes
            for (x in 0 until width) {
                val pixel = when (mode) {
                    Mode.BILEVEL -> if (bit(data, line, x)) grey(255) else grey(0)
                    Mode.PLANAR_PALETTE -> {
                        var index = 0
                        for (p in 0 until planes) if (bit(data, line + p * stride, x)) index = index or (1 shl p)
                        headerPalette[index]
                    }
                    Mode.PALETTE_OR_GREY -> {
                        val v = data[line + x].toInt() and 0xFF
                        trailingPalette?.get(v) ?: grey(v)
                    }
                    Mode.RGB -> argb(
                        0xFF,
                        data[line + x].toInt() and 0xFF,
                        data[line + stride + x].toInt() and 0xFF,
                        data[line + 2 * stride + x].toInt() and 0xFF,
                    )
                }
                out.set(x, y, pixel)
            }
        }
        return out.build()
    }

    private fun bit(data: ByteArray, start: Int, x: Int): Boolean =
        (data[start + x / 8].toInt() shr (7 - x % 8)) and 1 == 1

    private enum class Mode { BILEVEL, PLANAR_PALETTE, PALETTE_OR_GREY, RGB }
}
