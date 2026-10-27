package com.hawkynt.mediadroid.imaging

/**
 * Baseline TIFF (TIFF 6.0, Adobe): the first picture of a file, in strips or tiles, uncompressed or
 * LZW / Deflate / PackBits (see [TiffCompression]), with the horizontal-differencing predictor;
 * bilevel, greyscale (white- or black-is-zero, 1 to 16 bits), palette and RGB, each with an optional
 * alpha sample. Tag numbers and field types are the specification's. JPEG-in-TIFF, CMYK and YCbCr
 * are left to return null.
 */
object TiffDecoder {

    private const val IMAGE_WIDTH = 256
    private const val IMAGE_LENGTH = 257
    private const val BITS_PER_SAMPLE = 258
    private const val COMPRESSION = 259
    private const val PHOTOMETRIC = 262
    private const val STRIP_OFFSETS = 273
    private const val SAMPLES_PER_PIXEL = 277
    private const val ROWS_PER_STRIP = 278
    private const val STRIP_BYTE_COUNTS = 279
    private const val PLANAR_CONFIGURATION = 284
    private const val PREDICTOR = 317
    private const val COLOR_MAP = 320
    private const val TILE_WIDTH = 322
    private const val TILE_LENGTH = 323
    private const val TILE_OFFSETS = 324
    private const val TILE_BYTE_COUNTS = 325
    private const val EXTRA_SAMPLES = 338

    private const val WHITE_IS_ZERO = 0
    private const val BLACK_IS_ZERO = 1
    private const val RGB = 2
    private const val PALETTE = 3

    private const val ASSOCIATED_ALPHA = 1
    private const val UNASSOCIATED_ALPHA = 2

    private val INTEL = byteArrayOf(0x49, 0x49, 42, 0)
    private val MOTOROLA = byteArrayOf(0x4D, 0x4D, 0, 42)

    /** The TIFF signature: `II*\0` (little-endian) or `MM\0*` (big-endian). */
    fun accepts(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val head = bytes.copyOf(4)
        return head.contentEquals(INTEL) || head.contentEquals(MOTOROLA)
    }

    /** The picture, or null when [bytes] is no TIFF this decoder understands. */
    fun decode(bytes: ByteArray, maxPixels: Int = Int.MAX_VALUE): RasterImage? =
        runCatching { decodeOrThrow(bytes, maxPixels) }.getOrNull()

    private fun decodeOrThrow(bytes: ByteArray, maxPixels: Int): RasterImage? {
        if (!accepts(bytes)) return null
        val r = Reader(bytes, littleEndian = bytes[0] == INTEL[0])
        val tags = r.directory(r.u32(4).toInt())

        fun one(tag: Int, default: Int? = null): Int? = tags[tag]?.firstOrNull() ?: default
        val width = one(IMAGE_WIDTH) ?: return null
        val height = one(IMAGE_LENGTH) ?: return null
        val samples = one(SAMPLES_PER_PIXEL, 1)!!
        val bitsList = tags[BITS_PER_SAMPLE] ?: intArrayOf(1)
        val bits = bitsList.first()
        val compression = one(COMPRESSION, TiffCompression.NONE)!!
        val photometric = one(PHOTOMETRIC, BLACK_IS_ZERO)!!
        val predictor = one(PREDICTOR, 1)!!
        val planar = one(PLANAR_CONFIGURATION, 1)!!
        val extra = one(EXTRA_SAMPLES, 0)!!

        if (width <= 0 || height <= 0) return null
        if (bitsList.any { it != bits } || bits !in setOf(1, 2, 4, 8, 16)) return null
        if (compression !in TiffCompression.supported || predictor !in setOf(1, 2)) return null
        if (planar != 1 && samples > 1) return null
        val colourSamples = when (photometric) {
            WHITE_IS_ZERO, BLACK_IS_ZERO, PALETTE -> 1
            RGB -> 3
            else -> return null
        }
        if (samples < colourSamples) return null
        val hasAlpha = samples > colourSamples && (extra == ASSOCIATED_ALPHA || extra == UNASSOCIATED_ALPHA)
        val colourMap = if (photometric == PALETTE) tags[COLOR_MAP] ?: return null else null

        // Strips are tiles as wide as the picture.
        val tiled = tags.containsKey(TILE_OFFSETS)
        val blockWidth = if (tiled) one(TILE_WIDTH) ?: return null else width
        val blockHeight = if (tiled) one(TILE_LENGTH) ?: return null else minOf(one(ROWS_PER_STRIP, height)!!, height)
        val offsets = tags[if (tiled) TILE_OFFSETS else STRIP_OFFSETS] ?: return null
        val counts = tags[if (tiled) TILE_BYTE_COUNTS else STRIP_BYTE_COUNTS]
        val across = (width + blockWidth - 1) / blockWidth
        val rowBytes = (blockWidth * samples * bits + 7) / 8
        val maxValue = (1 shl bits) - 1

        val out = RasterBuilder(width, height, maxPixels)
        for ((block, offset) in offsets.withIndex()) {
            val x0 = (block % across) * blockWidth
            val y0 = (block / across) * blockHeight
            if (y0 >= height) break
            val rows = minOf(blockHeight, height - y0)
            val wantsAny = (0 until rows).any { out.wantsRow(y0 + it) }
            if (!wantsAny) continue
            val expected = rowBytes * blockHeight
            val start = offset.toLong() and 0xFFFFFFFFL
            val count = counts?.getOrNull(block)?.toLong()?.and(0xFFFFFFFFL) ?: (bytes.size - start)
            val end = minOf(bytes.size.toLong(), start + count).toInt()
            val raw = TiffCompression.decompress(compression, bytes.copyOfRange(start.toInt(), end), expected)
            val data = if (raw.size >= expected) raw else raw.copyOf(expected)
            if (predictor == 2) undoPredictor(data, rowBytes, rows, samples, bits, r.littleEndian)

            for (row in 0 until rows) {
                val y = y0 + row
                if (!out.wantsRow(y)) continue
                val line = row * rowBytes
                for (col in 0 until blockWidth) {
                    val x = x0 + col
                    if (x >= width) break
                    fun sample(k: Int): Int = sampleAt(data, line, col * samples + k, bits, r.littleEndian)
                    val alpha = if (hasAlpha) to8(sample(colourSamples), bits) else 0xFF
                    var pixel = when (photometric) {
                        WHITE_IS_ZERO -> grey(255 - scale(sample(0), maxValue, bits))
                        BLACK_IS_ZERO -> grey(scale(sample(0), maxValue, bits))
                        PALETTE -> {
                            val i = sample(0)
                            val n = colourMap!!.size / 3
                            argb(0xFF, colourMap[i] shr 8, colourMap[n + i] shr 8, colourMap[2 * n + i] shr 8)
                        }
                        else -> argb(0xFF, to8(sample(0), bits), to8(sample(1), bits), to8(sample(2), bits))
                    }
                    pixel = (pixel and 0x00FFFFFF) or (alpha shl 24)
                    if (hasAlpha && extra == ASSOCIATED_ALPHA) pixel = unpremultiply(pixel)
                    out.set(x, y, pixel)
                }
            }
        }
        return out.build()
    }

    /** Sample number [index] of the row starting at byte [line]; 16-bit samples honour the byte order. */
    private fun sampleAt(data: ByteArray, line: Int, index: Int, bits: Int, littleEndian: Boolean): Int =
        when (bits) {
            8 -> data[line + index].toInt() and 0xFF
            16 -> {
                val a = data[line + index * 2].toInt() and 0xFF
                val b = data[line + index * 2 + 1].toInt() and 0xFF
                if (littleEndian) a or (b shl 8) else (a shl 8) or b
            }
            else -> {
                val bit = index * bits
                val byte = data[line + bit / 8].toInt() and 0xFF
                (byte shr (8 - bits - bit % 8)) and ((1 shl bits) - 1)
            }
        }

    /** A sample as 0..255: 16-bit keeps the high byte, narrower samples are stretched. */
    private fun to8(value: Int, bits: Int): Int = when (bits) {
        8 -> value
        16 -> value shr 8
        else -> value * 255 / ((1 shl bits) - 1)
    }

    private fun scale(value: Int, maxValue: Int, bits: Int): Int =
        if (bits == 16) value shr 8 else value * 255 / maxValue

    /** Predictor 2: each sample is stored as the difference to the same sample of the pixel on its left. */
    private fun undoPredictor(
        data: ByteArray,
        rowBytes: Int,
        rows: Int,
        samples: Int,
        bits: Int,
        littleEndian: Boolean,
    ) {
        for (row in 0 until rows) {
            val line = row * rowBytes
            when (bits) {
                8 -> for (i in samples until rowBytes) {
                    data[line + i] = (data[line + i] + data[line + i - samples]).toByte()
                }
                16 -> {
                    val count = rowBytes / 2
                    for (i in samples until count) {
                        val sum = sampleAt(data, line, i, 16, littleEndian) +
                            sampleAt(data, line, i - samples, 16, littleEndian)
                        val lo = (sum and 0xFF).toByte()
                        val hi = (sum shr 8 and 0xFF).toByte()
                        data[line + i * 2] = if (littleEndian) lo else hi
                        data[line + i * 2 + 1] = if (littleEndian) hi else lo
                    }
                }
            }
        }
    }

    private fun unpremultiply(pixel: Int): Int {
        val a = pixel ushr 24
        if (a == 0 || a == 255) return pixel
        fun c(shift: Int) = minOf(255, ((pixel shr shift and 0xFF) * 255 + a / 2) / a)
        return argb(a, c(16), c(8), c(0))
    }

    private class Reader(val bytes: ByteArray, val littleEndian: Boolean) {

        fun u8(at: Int): Int = bytes[at].toInt() and 0xFF

        fun u16(at: Int): Int = if (littleEndian) u8(at) or (u8(at + 1) shl 8) else (u8(at) shl 8) or u8(at + 1)

        fun u32(at: Int): Long = if (littleEndian) {
            u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        } else {
            (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        }

        /** Every entry of the directory at [at] whose values are integers, as tag → values. */
        fun directory(at: Int): Map<Int, IntArray> {
            val count = u16(at)
            val tags = HashMap<Int, IntArray>()
            for (i in 0 until count) {
                val entry = at + 2 + i * 12
                val tag = u16(entry)
                val type = u16(entry + 2)
                val n = u32(entry + 4).toInt()
                val size = when (type) {
                    1, 6, 7 -> 1 // BYTE, SBYTE, UNDEFINED
                    3, 8 -> 2 // SHORT, SSHORT
                    4, 9 -> 4 // LONG, SLONG
                    else -> continue
                }
                if (n <= 0 || n > bytes.size) continue
                val base = if (n * size <= 4) entry + 8 else u32(entry + 8).toInt()
                tags[tag] = IntArray(n) { k ->
                    when (size) {
                        1 -> u8(base + k)
                        2 -> u16(base + k * 2)
                        else -> u32(base + k * 4).toInt()
                    }
                }
            }
            return tags
        }
    }
}
