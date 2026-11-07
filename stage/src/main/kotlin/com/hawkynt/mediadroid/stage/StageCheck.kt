package com.hawkynt.mediadroid.stage

import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureCheck

/**
 * What the dry run behind PictureCheck feeds the real stage and how it plays them, the same on every platform: test
 * pictures at 576p (smooth random blotches about 12 pixels across, moving 4 pixels across and 2 down a picture, so
 * motion has something to find and the pictures differ as video does), as RGBA and as a decoder's 4:2:0 planes, and
 * the moments of a picture's time the window asks for. How they reach the stage, and how its work is measured, is the
 * platform's (Android's PictureChecker, the desktop's StageDryRun).
 */
object StageCheck {
    /** How many test pictures there are; the dry run plays them in turn. */
    const val PICTURES = 8

    /** Test picture [k] (0 until [PICTURES]) at PictureCheck.WIDTH × HEIGHT: RGBA bytes, rows from the top, opaque. */
    fun picture(k: Int): ByteArray {
        val w = PictureCheck.WIDTH
        val h = PictureCheck.HEIGHT
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            for (c in 0 until 3) rgba[i + c] = blotch(x + k * 4, y + k * 2, c).toByte()
            rgba[i + 3] = -1
        }
        return rgba
    }

    /**
     * [rgba] (a test picture) as limited-range BT.601 yuv420p, as a decoder hands an SD picture over: the Y plane, then
     * Cb and Cr at half the size each way, each chroma sample from the top-left pixel of its two by two.
     */
    fun planes(rgba: ByteArray): ByteArray {
        val w = PictureCheck.WIDTH
        val h = PictureCheck.HEIGHT
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        fun c(x: Int, y: Int, k: Int) =
            rgba[(y.coerceAtMost(h - 1) * w + x.coerceAtMost(w - 1)) * 4 + k].toInt() and 0xFF
        val out = ByteArray(w * h + 2 * cw * ch)
        for (y in 0 until h) for (x in 0 until w) {
            val luma = 0.299 * c(x, y, 0) + 0.587 * c(x, y, 1) + 0.114 * c(x, y, 2)
            out[y * w + x] = (16 + luma * 219 / 255 + 0.5).toInt().toByte()
        }
        for (y in 0 until ch) for (x in 0 until cw) {
            val r = c(2 * x, 2 * y, 0)
            val g = c(2 * x, 2 * y, 1)
            val b = c(2 * x, 2 * y, 2)
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            out[w * h + y * cw + x] = (128 + (b - luma) / 1.772 * 224 / 255 + 0.5).toInt().toByte()
            out[w * h + cw * ch + y * cw + x] = (128 + (r - luma) / 1.402 * 224 / 255 + 0.5).toInt().toByte()
        }
        return out
    }

    /**
     * The moments of a picture's [spanMs] the window asks the stage for, as shares of it from 0: one per screen refresh
     * ([refreshMs]) while smooth motion mixes, both fields at field rate, else the picture once.
     */
    fun phases(picture: PictureAdjustment, spanMs: Double, refreshMs: Double): List<Double> = when {
        picture.interpolation != PictureAdjustment.Interpolation.OFF ->
            generateSequence(0.0) { it + refreshMs }.takeWhile { it < spanMs }.map { it / spanMs }.toList()
        picture.isFieldRate -> listOf(0.0, 0.5)
        else -> listOf(0.0)
    }

    private fun blotch(x: Int, y: Int, channel: Int): Int {
        val cell = 12
        val cx = Math.floorDiv(x, cell)
        val cy = Math.floorDiv(y, cell)
        val fx = (x - cx * cell).toFloat() / cell
        val fy = (y - cy * cell).toFloat() / cell
        fun level(i: Int, j: Int): Float {
            var v = i * 73856093 xor j * 19349663 xor channel * 83492791
            v = (v xor (v ushr 13)) * 0x5bd1e995
            return ((v xor (v ushr 15)) and 0xFF).toFloat()
        }
        val top = level(cx, cy) * (1 - fx) + level(cx + 1, cy) * fx
        val bottom = level(cx, cy + 1) * (1 - fx) + level(cx + 1, cy + 1) * fx
        return (top * (1 - fy) + bottom * fy).toInt()
    }
}
