package com.hawkynt.mediadroid.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Dot crawl and rainbows removed from composite captures (VHS, LaserDisc, console rips): a temporal comb filter. What
 * a decoder leaves of the colour subcarrier — dots crawling along colour edges (the colour read as brightness),
 * rainbows over fine detail (the brightness read as colour) — follows the subcarrier's phase, which each standard turns
 * its own way from frame to frame. Where the picture stands still, frames whose residues cancel are averaged and the
 * picture itself stays; where it moves, the frames differ for reasons of their own and the frame is left as it is.
 * Motion is told from residue by comparing frames, or sums of frames, that carry the same residue: up to the threshold
 * the mean is taken in full, past twice it not at all, in between in part.
 *
 * NTSC ([apply]): the subcarrier runs at 227.5 times the line rate (SMPTE 170M), 525 lines a frame, so its phase turns
 * over every frame. The frames before and after carry the opposite residue to the frame shown and the same as each
 * other: their difference is motion, and (before + 2 × shown + after) / 4 cancels the residue.
 *
 * PAL ([applyPal]): the subcarrier runs at 283.75 times the line rate plus 25 Hz (ITU-R BT.1700: 4 433 618.75 Hz), 625
 * lines a frame, so its phase moves on by three quarters of a cycle a frame — the 25 Hz adds whole cycles — and comes
 * back only after four frames (the eight-field sequence); the V component's sign, switched every line, flips from
 * frame to frame as well. Two frames apart the residue is opposite, and the frames either side of the shown one carry
 * residues opposite to each other: the frame two before plus the frame shown, and the frames either side, each sum to
 * the picture without residue, so half their difference is motion and (two before + shown) / 2 the comb — the largest
 * share of the frame shown any residue-free mean of these four frames can keep, and it needs no frame later than the
 * one after.
 *
 * PAL-M (Brazil; ITU-R BT.1700's System M with PAL colour): the subcarrier runs at 227.25 times the line rate, 525
 * lines a frame at 29.97 frames a second, so its phase moves on by a quarter cycle a frame and the V switch flips with
 * the odd line count: two frames apart the residue is opposite, as PAL's, and PAL's comb clears it. Its rate and line
 * count are NTSC's, so only the container's or decoder's colour tags, or the household's choice, tell it ([Standard]).
 *
 * Bobbed to its fields ([applyPalFields]), a PAL or PAL-M capture shows each field as a picture, the fields of a frame
 * on lines of their own: the comb reads the same field's pictures only, two frames (four pictures) apart for the
 * opposite residue.
 *
 * Written from the standards' subcarrier relations (AGENTS.md rung 3); DScaler's comb filtering (GPL-2.0, its help and
 * plug-ins) described the feature, no code of it is taken. Held to simulated composite pictures by `DotCrawlTest`; the
 * GL passes (glsl/video/dot-crawl.frag, dot-crawl-pal.frag) are written from it.
 */
object DotCrawl {

    /** The relation between the residues of a capture's pictures, which decides the pictures the comb reads. */
    enum class Standard {
        /** The subcarrier's phase turns over every frame: the frames before and after ([apply]). */
        NTSC,

        /**
         * The residue is opposite two frames on, and opposite between the frames either side: PAL, and PAL-M, whose
         * phase turns a quarter cycle a frame and whose V switch flips with its odd line count ([applyPal]).
         */
        PAL,

        /**
         * PAL or PAL-M bobbed to a picture a field: the same field's residue is opposite two frames (four pictures)
         * on, so the comb reads the pictures two, four and six back ([applyPalFields]).
         */
        PAL_FIELDS,
        ;

        companion object {
            /**
             * The standard of a capture showing a picture every [intervalMs], in [system] as the household chose it,
             * [palColour] whether its container or decoder tags name PAL's colour (BT.470 System B/G primaries or
             * transfer). By hand it is what was chosen, at frame rate or (a picture every 25 ms or less: bobbed) per
             * field — NTSC's per frame either way. Automatic: 25 frames a second is PAL and 50 PAL bobbed; at any
             * other rate NTSC, unless the tags say PAL colour, which at NTSC's rate and line count is PAL-M (Brazil) —
             * the rate and the line count alone cannot tell the two apart (24 and 23.976 are film, no composite
             * capture).
             */
            fun of(intervalMs: Float, system: PictureAdjustment.ColourSystem, palColour: Boolean): Standard {
                val fields = intervalMs > 0f && intervalMs <= 25f
                val pal = when (system) {
                    PictureAdjustment.ColourSystem.NTSC -> false
                    PictureAdjustment.ColourSystem.PAL, PictureAdjustment.ColourSystem.PAL_M -> true
                    PictureAdjustment.ColourSystem.AUTO ->
                        intervalMs in 38.5f..41f || intervalMs in 19.25f..20.5f || palColour
                }
                return if (!pal) NTSC else if (fields) PAL_FIELDS else PAL
            }
        }
    }

    /** The motion threshold for the slider's [strength], 0 to 1, in 8-bit steps, or null at 0 (off): 4 to 20. */
    fun threshold(strength: Float): Float? = if (strength <= 0f) null else 4f + 16f * strength.coerceAtMost(1f)

    /**
     * The NTSC frame [current] (8-bit RGB, rows from the top, [width] × [height]) combed with [before] and [after]:
     * each pixel's motion the largest difference between [before] and [after] over its channels and its neighbours left
     * and right, the comb's mean taken in full up to [threshold], in part up to twice it.
     */
    fun apply(
        before: ByteArray,
        current: ByteArray,
        after: ByteArray,
        width: Int,
        height: Int,
        threshold: Float,
    ): ByteArray {
        require(current.size == width * height * 3 && before.size == current.size && after.size == current.size)
        val motion = { i: Int -> abs(v(after, i) - v(before, i)).toFloat() }
        return combed(current, width, height, threshold, motion) { i, shown ->
            (v(before, i) + 2f * shown + v(after, i)) / 4f
        }
    }

    /**
     * The PAL frame [current] (8-bit RGB, rows from the top, [width] × [height]) combed with [earlier] (two frames
     * before), [before] and [after]: each pixel's motion the largest of half the difference between [earlier] +
     * [current] and [before] + [after] over its channels and its neighbours left and right, the comb's mean
     * ([earlier] + [current]) / 2 taken in full up to [threshold], in part up to twice it.
     */
    fun applyPal(
        earlier: ByteArray,
        before: ByteArray,
        current: ByteArray,
        after: ByteArray,
        width: Int,
        height: Int,
        threshold: Float,
    ): ByteArray {
        require(
            current.size == width * height * 3 && earlier.size == current.size && before.size == current.size &&
                after.size == current.size,
        )
        val motion = { i: Int -> abs(v(earlier, i) + v(current, i) - v(before, i) - v(after, i)) / 2f }
        return combed(current, width, height, threshold, motion) { i, shown -> (v(earlier, i) + shown) / 2f }
    }

    /**
     * The picture [current] of a PAL capture bobbed to its fields (8-bit RGB, rows from the top, [width] × [height])
     * combed with the pictures of the same field before it: [twoBack], [fourBack] (two frames back, its residue
     * opposite) and [sixBack]. As [applyPal] with the same field's pictures for its frames — [fourBack] and [current]
     * cancel the residue as [twoBack] and [sixBack] do, so half the difference of their sums is motion — but all of
     * them before the one shown, which as on Android has only the next picture decoded ahead.
     */
    fun applyPalFields(
        sixBack: ByteArray,
        fourBack: ByteArray,
        twoBack: ByteArray,
        current: ByteArray,
        width: Int,
        height: Int,
        threshold: Float,
    ): ByteArray = applyPal(fourBack, twoBack, current, sixBack, width, height, threshold)

    private fun v(frame: ByteArray, i: Int) = frame[i].toInt() and 0xFF

    // [current] mixed towards [mean] by the share its [motion] (the largest over the channels and the neighbours left
    // and right) leaves.
    private inline fun combed(
        current: ByteArray,
        width: Int,
        height: Int,
        threshold: Float,
        motion: (Int) -> Float,
        mean: (Int, Float) -> Float,
    ): ByteArray {
        val out = ByteArray(current.size)
        for (y in 0 until height) for (x in 0 until width) {
            var most = 0f
            for (dx in -1..1) {
                val nx = (x + dx).coerceIn(0, width - 1)
                for (c in 0..2) most = maxOf(most, motion((y * width + nx) * 3 + c))
            }
            val share = (2f - most / threshold).coerceIn(0f, 1f)
            for (c in 0..2) {
                val i = (y * width + x) * 3 + c
                val shown = v(current, i).toFloat()
                out[i] = (shown + share * (mean(i, shown) - shown)).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }
}
