package com.hawkynt.mediadroyd.player

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * How often each level occurs in the picture on screen — luma ([luma]) and both colour differences ([cb], [cr]), 256
 * levels each — to set brightness and contrast by: the darkest peak is the picture's black, the brightest its white;
 * piled up at 0 or 255 they are clipped. The picture is taken as it is shown, every filter and the colour correction
 * applied, in full-range RGB, and turned into Y'CbCr by BT.709's weights (ITU-R BT.709-6, items 3.2 to 3.4) at full
 * range: Y' = 0.2126 R + 0.7152 G + 0.0722 B, Cb = (B − Y') / 1.8556 + 128, Cr = (R − Y') / 1.5748 + 128.
 */
class Histogram(
    val luma: IntArray,
    val cb: IntArray,
    val cr: IntArray,
    /** How many pixels were counted. */
    val samples: Int,
) {
    /** The share of the pixels at full black: crushed, below the screen's darkest step. */
    val clippedBlack: Float get() = if (samples == 0) 0f else luma[0].toFloat() / samples

    /** The share of the pixels at full white: clipped, above the screen's brightest step. */
    val clippedWhite: Float get() = if (samples == 0) 0f else luma[LEVELS - 1].toFloat() / samples

    companion object {
        const val LEVELS = 256

        /** The video range BT.601 and BT.709 code luma in (16 to 235) and colour (16 to 240); outside it is dimmed. */
        val LUMA_RANGE = 16..235
        val CHROMA_RANGE = 16..240

        /** At most this many pixels are counted, every [step]th one either way; enough for a smooth histogram. */
        const val MAX_SAMPLES = 1 shl 16

        /** The step that keeps a [width] × [height] picture within [MAX_SAMPLES]. */
        fun step(width: Int, height: Int): Int {
            var step = 1
            while ((width / step) * (height / step) > MAX_SAMPLES) step++
            return step
        }

        /** The histogram of [rgba] ([width] × [height], RGBA), every [step]th pixel across and down. */
        fun of(rgba: ByteArray, width: Int, height: Int, step: Int = step(width, height)): Histogram {
            val luma = IntArray(LEVELS)
            val cb = IntArray(LEVELS)
            val cr = IntArray(LEVELS)
            var samples = 0
            for (y in 0 until height step step) {
                var at = y * width * 4
                for (x in 0 until width step step) {
                    val r = rgba[at].toInt() and 255
                    val g = rgba[at + 1].toInt() and 255
                    val b = rgba[at + 2].toInt() and 255
                    val yy = 0.2126 * r + 0.7152 * g + 0.0722 * b
                    luma[level(yy)]++
                    cb[level((b - yy) / 1.8556 + 128.0)]++
                    cr[level((r - yy) / 1.5748 + 128.0)]++
                    samples++
                    at += step * 4
                }
            }
            return Histogram(luma, cb, cr, samples)
        }

        private fun level(v: Double): Int = v.roundToInt().coerceIn(0, LEVELS - 1)

        /**
         * [counts] as heights of at most [rows], on a log scale (DScaler's Histogram filter, read for the
         * behaviour): a level that occurs at all at least one row, the most frequent all of them.
         */
        fun heights(counts: IntArray, rows: Int): IntArray {
            val most = counts.maxOrNull() ?: 0
            if (most == 0) return IntArray(counts.size)
            val scale = ln(1.0 + most)
            return IntArray(counts.size) { i ->
                val c = counts[i]
                if (c == 0) 0 else (ln(1.0 + c) / scale * rows).roundToInt().coerceIn(1, rows)
            }
        }
    }
}
