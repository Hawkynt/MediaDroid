package com.hawkynt.mediadroid.player

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * How bright an HDR video really gets, measured from its pictures as they play, for [ToneMap]'s curve: HDR10's metadata
 * names the mastering screen's peak (often 1000 or 4000 nits), rarely what a scene reaches — a night scene mapped for
 * 4000 nits comes out needlessly dark. Each picture's luminance is measured in blocks of 16 × 16 pixels (the brightest
 * pixel and the block's average, black pixels left out); the frame's peak is its brightest block's, its average the
 * blocks' mean. Both are smoothed over about 20 frames so the picture does not pump, unless the average jumps — a scene
 * change — when they follow at once.
 *
 * Converted from libplacebo 7.360's peak detection (LGPL-2.1-or-later, see `THIRD_PARTY_NOTICES.md`):
 * `pl_shader_detect_peak` and `update_peak_buf` in `src/shaders/colorspace.c` with `PL_PEAK_DETECT_DEFAULTS` — the
 * measurement in work groups of 16 × 16, 14-bit PQ, the black cutoff of 1 %, the IIR filter and the scene-change
 * hysteresis. The app's GL pass (glsl/video/peak.frag) measures the blocks; this takes over from there, as libplacebo's
 * processor side does. Held to libplacebo's own detection by `PeakDetectTest`.
 */
class PeakDetect {

    /** One picture's blocks: each one's brightest pixel and average, in [PQ_MAX] steps; 0 brightest is all black. */
    class Blocks(val maxima: IntArray, val averages: IntArray) {
        init {
            require(maxima.size == averages.size) { "${maxima.size} maxima, ${averages.size} averages" }
        }
    }

    // The smoothed values, in PQ; an average of 0 is no measurement yet.
    private var maxPq = 0f
    private var avgPq = 0f

    /** The source's peak as measured so far, in PQ — the curve's top, see [ToneMap.curve] — or null before any. */
    val peak: Float? get() = maxPq.takeIf { avgPq != 0f }

    /** The source's average as measured so far, in PQ, or null before any. */
    val average: Float? get() = avgPq.takeIf { it != 0f }

    /** Forgets what was measured: the next picture starts afresh. */
    fun reset() {
        maxPq = 0f
        avgPq = 0f
    }

    /** Takes one more picture's [blocks] into the measurement. */
    fun update(blocks: Blocks) {
        val count = blocks.maxima.size
        if (count == 0) return
        var active = 0
        var sum = 0L
        var brightest = 0
        for (i in 0 until count) {
            if (blocks.maxima[i] == 0) continue
            active++
            sum += blocks.averages[i]
            brightest = max(brightest, blocks.maxima[i])
        }
        var avg: Float
        var peak: Float
        if (active > 0) {
            avg = sum.toFloat() / (active.toLong() * PQ_MAX).toFloat()
            peak = brightest.toFloat() / PQ_MAX
        } else {
            // A solid black frame.
            avg = BLACK
            peak = BLACK
        }
        if (avgPq == 0f) {
            avgPq = avg
            maxPq = peak
        } else {
            // Small deviations (rounding) do not move the values.
            if (abs(avg - avgPq) < EPSILON) avg = avgPq
            if (abs(peak - maxPq) < EPSILON) peak = maxPq
        }
        avgPq += COEFF * (avg - avgPq)
        maxPq += COEFF * (peak - maxPq)
        // A scene change: the further the average jumps, the more the new values are taken as they are.
        val delta = active.toFloat() / count * abs(avg - avgPq)
        val jump = smoothstep(SCENE_LOW, SCENE_HIGH, delta)
        avgPq = jump * avg + (1 - jump) * avgPq
        maxPq = jump * peak + (1 - jump) * maxPq
    }

    companion object {
        /** The side of a measured block, in pixels: libplacebo's work group. */
        const val BLOCK = 16

        /** Luminance is measured in 14-bit PQ. */
        const val PQ_MAX = (1 shl 14) - 1

        // libplacebo's value for a solid black frame, its rounding margin, its IIR filter over 20 frames and its scene
        // change thresholds (1 and 3, about a hundredth of PQ each).
        private const val BLACK = 1e-6f
        private const val EPSILON = 1f / PQ_MAX
        private val COEFF = 1f - exp(-1f / 20f)
        private const val SCENE_LOW = 1f * 1e-2f
        private const val SCENE_HIGH = 3f * 1e-2f

        // Pixels darker than this (in PQ, 1 %) fade out of the measurement, the black ones dropped.
        private const val BLACK_CUTOFF = 0.01f

        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** What glsl/video/peak.frag is handed for [transfer] (see [ToneMap.Pass.uniforms]). */
        fun pass(transfer: ToneMap.Transfer) = ToneMap.Pass(transfer, ToneMap.curve(transfer), measuring = true)

        /** A pixel's luminance in [PQ_MAX] steps, as libplacebo measures it, from its coded values [rgb] (0..1). */
        fun luminance(rgb: FloatArray, pass: ToneMap.Pass): Int {
            val color = rgb.copyOf()
            ToneMap.linearize(color, pass)
            var y = pass.luma[0] * color[0] + pass.luma[1] * color[1] + pass.luma[2] * color[2]
            y *= ToneMap.SDR_WHITE / 10000f
            y = y.coerceIn(0f, 1f).pow(ToneMap.Printed.M1)
            y = (ToneMap.Printed.C1 + ToneMap.Printed.C2 * y) / (1f + ToneMap.Printed.C3 * y)
            y = y.pow(ToneMap.Printed.M2)
            y *= smoothstep(0f, BLACK_CUTOFF, y)
            return (PQ_MAX * y).toInt()
        }

        /**
         * The [Blocks] of a picture of coded HDR values — [rgb16] 16-bit RGB, [width] × [height], rows from the top —
         * as the GL pass measures them: blocks from the top left, those at the edges holding what is left.
         */
        fun blocks(rgb16: ShortArray, width: Int, height: Int, transfer: ToneMap.Transfer): Blocks {
            val pass = pass(transfer)
            val columns = (width + BLOCK - 1) / BLOCK
            val rows = (height + BLOCK - 1) / BLOCK
            val maxima = IntArray(columns * rows)
            val averages = IntArray(columns * rows)
            val pixel = FloatArray(3)
            for (by in 0 until rows) {
                for (bx in 0 until columns) {
                    var sum = 0L
                    var brightest = 0
                    var lit = 0
                    for (y in by * BLOCK until min(height, by * BLOCK + BLOCK)) {
                        for (x in bx * BLOCK until min(width, bx * BLOCK + BLOCK)) {
                            val i = (y * width + x) * 3
                            for (c in 0..2) pixel[c] = (rgb16[i + c].toInt() and 0xFFFF) / 65535f
                            val l = luminance(pixel, pass)
                            sum += l
                            brightest = max(brightest, l)
                            if (l > 0) lit++
                        }
                    }
                    maxima[by * columns + bx] = brightest
                    averages[by * columns + bx] = if (lit > 0) (sum / lit).toInt() else 0
                }
            }
            return Blocks(maxima, averages)
        }

        /**
         * The [Blocks] the GL pass wrote, read back as RGBA bytes ([rgba], [count] blocks): each block's brightest in
         * red (high byte) and green (low byte), its average in blue and alpha.
         */
        fun unpack(rgba: ByteArray, count: Int): Blocks {
            fun at(i: Int) = rgba[i].toInt() and 0xFF
            return Blocks(
                IntArray(count) { at(it * 4) shl 8 or at(it * 4 + 1) },
                IntArray(count) { at(it * 4 + 2) shl 8 or at(it * 4 + 3) },
            )
        }
    }
}
