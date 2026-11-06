package com.hawkynt.mediadroid.player

import kotlin.math.abs

/**
 * Temporal denoising: each pixel averaged with the same pixel in the frames before and after it, where it barely
 * changed — grain dances from frame to frame while the picture stays, so a still part comes out clean without the
 * softening a spatial filter brings — and left alone where it changed more, which is motion. The frame before is taken
 * where the pixel differs from it by at most threshold A, then the one after on the same terms, and neither once the
 * differences add up past threshold B.
 *
 * Converted from FFmpeg's `libavfilter/vf_atadenoise.c` (Paul B Mahol, after Bartovčak and Vrankić, "Video Denoising
 * Based on Adaptive Temporal Averaging"; LGPL-2.1-or-later, see `THIRD_PARTY_NOTICES.md`): its parallel, unweighted
 * filter over three frames — FFmpeg's own options start at five, a picture two frames late; three keep the one frame
 * the stage's temporal deinterlacing runs behind. Held to FFmpeg's own filter by `TemporalDenoiseTest`; the GL pass
 * (glsl/video/temporal-denoise.frag) is written from it.
 */
object TemporalDenoise {

    /** FFmpeg's thresholds, fractions of full scale: [a] for one frame's difference, [b] for the sum of them. */
    data class Params(val a: Float, val b: Float)

    /** FFmpeg's defaults: 0.02 and 0.04. */
    val Defaults = Params(0.02f, 0.04f)

    /**
     * The settings for the slider's [strength], 0 to 1, or null at 0 (off): FFmpeg's thresholds times four times the
     * strength — its defaults at a quarter, four times as tolerant at the top (threshold A within FFmpeg's 0.3).
     */
    fun params(strength: Float): Params? {
        if (strength <= 0f) return null
        val scale = 4f * strength.coerceAtMost(1f)
        return Params((Defaults.a * scale).coerceAtMost(0.3f), Defaults.b * scale)
    }

    /** A threshold as FFmpeg keeps it for 8 bits: the fraction times 256, less one, cut to an integer. */
    fun threshold(fraction: Float): Int = (fraction * 256f - 1f).toInt()

    /** [current] (one channel, any layout) denoised against [before] and [after], the same pixels in the same order. */
    fun apply(before: ByteArray, current: ByteArray, after: ByteArray, params: Params): ByteArray {
        require(before.size == current.size && after.size == current.size) { "frames of different sizes" }
        val thra = threshold(params.a)
        val thrb = threshold(params.b)
        return ByteArray(current.size) { i ->
            val x = current[i].toInt() and 0xFF
            var sum = x
            var count = 1
            val left = before[i].toInt() and 0xFF
            val leftDiff = abs(x - left)
            if (leftDiff <= thra && leftDiff <= thrb) {
                sum += left
                count++
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
}
