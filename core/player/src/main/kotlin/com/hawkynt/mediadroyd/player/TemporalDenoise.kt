package com.hawkynt.mediadroyd.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Temporal denoising: each pixel averaged with the same point of the picture in the frames before and after it, where
 * it barely changed — grain dances from frame to frame while the picture stays, so it comes out clean without the
 * softening a spatial filter brings — and left alone where it changed more. The frame before is taken where the pixel
 * differs from it by at most threshold A, then the one after on the same terms, and neither once the differences add
 * up past threshold B.
 *
 * The frames either side are first moved onto the one shown along the motion between them ([aligned]): the vectors
 * of MotionInterpolation's search, every pixel taken from the up to four 16-pixel blocks whose doubled windows cover
 * it, weighted towards their centres — so a pan or a moving object is denoised like a still part rather than left
 * alone as motion. And the thresholds follow the noise the picture itself carries ([noiseSigma], [NoiseLevel]): a
 * clean source is barely touched, a grainy one smoothed, at the same slider.
 *
 * The filter is converted from FFmpeg's `libavfilter/vf_atadenoise.c` (Paul B Mahol, after Bartovčak and Vrankić,
 * "Video Denoising Based on Adaptive Temporal Averaging"; LGPL-2.1-or-later, see `THIRD_PARTY_NOTICES.md`): its
 * parallel, unweighted filter over three frames — FFmpeg's own options start at five, a picture two frames late; three
 * keep the one frame the stage's temporal deinterlacing runs behind. Held to FFmpeg's own filter by
 * `TemporalDenoiseTest`; the GL pass (glsl/video/temporal-denoise.frag) is written from it.
 */
object TemporalDenoise {

    /** FFmpeg's thresholds, fractions of full scale: [a] for one frame's difference, [b] for the sum of them. */
    data class Params(val a: Float, val b: Float)

    /** FFmpeg's defaults: 0.02 and 0.04. */
    val Defaults = Params(0.02f, 0.04f)

    /** FFmpeg's largest threshold A. */
    private const val MAX_A = 0.3f

    /**
     * Threshold A as multiples of the noise's standard deviation at full strength: six, three in the middle — two
     * frames' grain differs by √2 σ, so three σ takes in all but a few in a hundred of a still part's pixels.
     */
    const val SIGMAS = 6f

    /**
     * The settings for the slider's [strength], 0 to 1, or null at 0 (off). With the noise's standard deviation
     * [sigma] (in 8-bit steps, see [NoiseLevel]) known, threshold A is [SIGMAS] times it times the strength, in whole
     * steps, and B twice that; before the first estimate, FFmpeg's thresholds times four times the strength — its
     * defaults at a quarter, four times as tolerant at the top. Threshold A stays within FFmpeg's 0.3 either way.
     */
    fun params(strength: Float, sigma: Double? = null): Params? {
        if (strength <= 0f) return null
        val s = strength.coerceAtMost(1f)
        if (sigma != null) {
            val steps = floor(sigma * SIGMAS * s).toFloat().coerceIn(0f, MAX_STEPS)
            // Fractions whose threshold() comes out at whole steps: A at steps, B at twice that.
            return Params((steps + 1.5f) / 256f, (2f * steps + 1.5f) / 256f)
        }
        val scale = 4f * s
        return Params((Defaults.a * scale).coerceAtMost(MAX_A), Defaults.b * scale)
    }

    // Threshold A's largest whole step within FFmpeg's 0.3.
    private const val MAX_STEPS = 75f

    /** A threshold as FFmpeg keeps it for 8 bits: the fraction times 256, less one, cut to an integer. */
    fun threshold(fraction: Float): Int = (fraction * 256f - 1f).toInt()

    /**
     * [current] (one channel, any layout) denoised against [before] and [after], the same pixels in the same order. A
     * side that is null (a cut lies that way) is not taken; without the frame before, the one after is judged alone.
     */
    fun apply(before: ByteArray?, current: ByteArray, after: ByteArray?, params: Params): ByteArray {
        require(before == null || before.size == current.size) { "frames of different sizes" }
        require(after == null || after.size == current.size) { "frames of different sizes" }
        val thra = threshold(params.a)
        val thrb = threshold(params.b)
        return ByteArray(current.size) { i ->
            val x = current[i].toInt() and 0xFF
            var sum = x
            var count = 1
            var open = true
            if (before != null) {
                val left = before[i].toInt() and 0xFF
                val leftDiff = abs(x - left)
                open = leftDiff <= thra && leftDiff <= thrb
                if (open) {
                    sum += left
                    count++
                }
            }
            if (open && after != null) {
                val right = after[i].toInt() and 0xFF
                val rightDiff = abs(x - right)
                if (rightDiff <= thra && rightDiff <= thrb) {
                    sum += right
                    count++
                }
            }
            ((sum + (count shr 1)) / count).toByte()
        }
    }

    /**
     * The motion between two frames as [aligned] reads it: the half-vectors (x, y per block, row by row) of a
     * [gridWidth] × [gridHeight] grid of 16-pixel blocks that MotionInterpolation.Estimator found between the two
     * frames' luma at [scale] times smaller — the earlier frame lies a half-vector one way of the moment between, the
     * later one the same the other way.
     */
    class Motion(val gridWidth: Int, val gridHeight: Int, val scale: Int, val vectors: IntArray) {
        init {
            require(vectors.size == gridWidth * gridHeight * 2) { "two values a block" }
        }
    }

    /** Which frame of a pair [aligned] moves onto the other. */
    enum class Side(val sign: Int) {
        /** The earlier frame, moved onto the later one: read one whole vector along it. */
        BEFORE(1),

        /** The later frame, moved onto the earlier one: read one whole vector against it. */
        AFTER(-1),
    }

    /**
     * [frame] (one channel, [width] × [height], row by row from the top) moved onto the frame shown along [motion]:
     * at the search's size each pixel takes the up to four blocks whose doubled 32-pixel windows cover it (inside the
     * search's picture), weighted by MotionInterpolation's overlapped-block table, and reads [frame] one whole vector
     * away ([side]), kept inside the picture and [motion]'s scale times as far at full size; the weighted sum rounds
     * to the nearest step. A pixel no block covers stays as it is.
     */
    fun aligned(frame: ByteArray, width: Int, height: Int, motion: Motion, side: Side): ByteArray {
        require(frame.size == width * height) { "one value a pixel" }
        val scale = motion.scale
        val searchW = width / scale
        val searchH = height / scale
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            val hy = y / scale
            for (x in 0 until width) {
                val hx = x / scale
                var sum = 0L
                var weights = 0L
                val firstX = Math.floorDiv(hx + 8, 16) - 1
                val firstY = Math.floorDiv(hy + 8, 16) - 1
                for (j in 0..1) for (i in 0..1) {
                    val mbX = firstX + i
                    val mbY = firstY + j
                    if (mbX < 0 || mbY < 0 || mbX >= motion.gridWidth || mbY >= motion.gridHeight) continue
                    val startX = mbX * 16 - 8
                    val startY = mbY * 16 - 8
                    if (hx < maxOf(startX, 0) || hy < maxOf(startY, 0)) continue
                    if (hx >= minOf(startX + 32, searchW) || hy >= minOf(startY + 32, searchH)) continue
                    val w = MotionInterpolation.obmcWeight((hx - startX) + (hy - startY) * 32)
                    if (w == 0) continue
                    val b = mbX + mbY * motion.gridWidth
                    val ox = (side.sign * 2 * motion.vectors[b * 2]).coerceIn(-hx, searchW - 1 - hx)
                    val oy = (side.sign * 2 * motion.vectors[b * 2 + 1]).coerceIn(-hy, searchH - 1 - hy)
                    val sx = (x + scale * ox).coerceIn(0, width - 1)
                    val sy = (y + scale * oy).coerceIn(0, height - 1)
                    sum += w.toLong() * (frame[sy * width + sx].toInt() and 0xFF)
                    weights += w
                }
                out[y * width + x] = if (weights == 0L) {
                    frame[y * width + x]
                } else {
                    ((sum + weights / 2) / weights).toInt().toByte()
                }
            }
        }
        return out
    }

    /**
     * The standard deviation of the noise in [plane] (in its own steps), by Immerkær's fast estimate (J. Immerkær,
     * "Fast Noise Variance Estimation", Computer Vision and Image Understanding 64(2), 1996): the picture filtered
     * with the difference of two Laplacians, [[1, −2, 1], [−2, 4, −2], [1, −2, 1]], which cancels whatever is flat or
     * runs straight, and the mean of its magnitude over the inner pixels times √(π/2) / 6. Edges and texture add a
     * little; the grain of a quiet picture reads true. Zero for a plane under three pixels either way.
     */
    fun noiseSigma(plane: Deinterlacer.Plane): Double {
        val w = plane.width
        val h = plane.height
        if (w < 3 || h < 3) return 0.0
        val p = plane.pixels
        var sum = 0L
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val k = y * w + x
                val v = p[k - w - 1] - 2 * p[k - w] + p[k - w + 1] -
                    2 * p[k - 1] + 4 * p[k] - 2 * p[k + 1] +
                    p[k + w - 1] - 2 * p[k + w] + p[k + w + 1]
                sum += abs(v)
            }
        }
        return sqrt(PI / 2) * sum / (6.0 * (w - 2) * (h - 2))
    }

    /**
     * The noise level as the player follows it: each frame's [noiseSigma] folded into a running average (an eighth of
     * the new estimate each time), so the thresholds drift with the source rather than flicker from frame to frame.
     */
    class NoiseLevel {
        /** The level so far, in 8-bit steps; null before the first frame. */
        var sigma: Double? = null
            private set

        /** Folds in one frame's [estimate] and returns the level. */
        fun add(estimate: Double): Double {
            val next = sigma?.let { it + (estimate - it) / 8.0 } ?: estimate
            sigma = next
            return next
        }
    }
}
