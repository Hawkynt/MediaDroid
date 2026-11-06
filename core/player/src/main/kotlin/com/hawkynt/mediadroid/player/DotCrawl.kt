package com.hawkynt.mediadroid.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Dot crawl and rainbows removed from composite captures (VHS, LaserDisc, console rips): a temporal comb filter. A
 * composite signal carries the colour on a subcarrier at 227.5 times the line rate (SMPTE 170M), so its phase turns
 * over from one frame to the next; what a decoder leaves of it — dots crawling along colour edges (the colour read as
 * brightness), rainbows over fine detail (the brightness read as colour) — flips sign every frame. Where the picture
 * stands still, the frames before and after carry the opposite residue to the frame shown, and the mean
 * (before + 2 × shown + after) / 4 cancels it while the picture itself stays; where it moves, the frames differ for
 * reasons of their own and the frame is left as it is. The frames before and after share the shown frame's opposite
 * phase, so the difference between them alone tells motion from residue: up to the threshold the mean is taken in
 * full, past twice it not at all, in between in part.
 *
 * Written from the NTSC standard's subcarrier relation (AGENTS.md rung 3); DScaler's comb filtering (GPL-2.0, its help
 * and plug-ins) described the feature, no code of it is taken. Held to a simulated composite picture by
 * `DotCrawlTest`; the GL pass (glsl/video/dot-crawl.frag) is written from it. PAL's subcarrier turns over every two
 * frames, so this cancels NTSC's residue; PAL's stays.
 */
object DotCrawl {

    /** The motion threshold for the slider's [strength], 0 to 1, in 8-bit steps, or null at 0 (off): 4 to 20. */
    fun threshold(strength: Float): Float? = if (strength <= 0f) null else 4f + 16f * strength.coerceAtMost(1f)

    /**
     * The frame [current] (8-bit RGB, rows from the top, [width] × [height]) combed with [before] and [after]: each
     * pixel's motion the largest difference between [before] and [after] over its channels and its neighbours left and
     * right, the comb's mean taken in full up to [threshold], in part up to twice it.
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
        fun v(frame: ByteArray, i: Int) = frame[i].toInt() and 0xFF
        val out = ByteArray(current.size)
        for (y in 0 until height) for (x in 0 until width) {
            var motion = 0
            for (dx in -1..1) {
                val nx = (x + dx).coerceIn(0, width - 1)
                for (c in 0..2) {
                    val i = (y * width + nx) * 3 + c
                    motion = maxOf(motion, abs(v(after, i) - v(before, i)))
                }
            }
            val share = (2f - motion / threshold).coerceIn(0f, 1f)
            for (c in 0..2) {
                val i = (y * width + x) * 3 + c
                val shown = v(current, i).toFloat()
                val combed = (v(before, i) + 2f * shown + v(after, i)) / 4f
                out[i] = (shown + share * (combed - shown)).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }
}
