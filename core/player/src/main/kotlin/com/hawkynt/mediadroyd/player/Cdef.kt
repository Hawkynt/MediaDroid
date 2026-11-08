package com.hawkynt.mediadroyd.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Deringing: the ripples a low-bitrate encoder leaves beside edges and text ("mosquito noise") smoothed along the
 * edge's own direction — AV1's Constrained Directional Enhancement Filter, its in-loop deringing, run here on the
 * decoded picture. Each 8 × 8 block's dominant direction is found on its brightness (the line along which its pixels
 * vary least); every pixel is then blended with the pixels two either way along that direction (primary) and across it
 * at 45° (secondary), each difference constrained so that a large one — a real edge — counts for nothing. The primary
 * strength follows the block's own contrast, as AV1's luma does.
 *
 * Converted from dav1d 1.5.0's `src/cdef_tmpl.c` (`cdef_find_dir_c`, `cdef_filter_block_c`, `constrain`) and
 * `src/cdef_apply_tmpl.c` (`adjust_strength`), with `src/tables.c`'s directions (BSD-2-Clause, see
 * `THIRD_PARTY_NOTICES.md`) — the reference the GL passes (glsl/video/cdef-*.frag) are written from, held to dav1d's
 * filter by `CdefTest`. Every pixel is read from the unfiltered picture, nothing past its edges; pixels outside the
 * full blocks stay as they are.
 */
object Cdef {

    /** AV1's settings: [primary] strength 0..15, [secondary] 0, 1, 2 or 4, and the [damping] 3..6. */
    data class Params(val primary: Int, val secondary: Int, val damping: Int)

    /**
     * The settings for the slider's [strength], 0 to 1, or null at 0 (off): primary strength, secondary strength and
     * damping rising together, AV1's strongest at the top.
     */
    fun params(strength: Float): Params? {
        if (strength <= 0f) return null
        val s = strength.coerceAtMost(1f)
        return Params(
            (15f * s).roundToInt().coerceAtLeast(1),
            intArrayOf(0, 1, 2, 4)[(3f * s).roundToInt()],
            3 + (3f * s).roundToInt(),
        )
    }

    /** The brightness CDEF finds directions on: (77 R + 150 G + 29 B + 128) / 256. */
    fun luma(r: Int, g: Int, b: Int): Int = (77 * r + 150 * g + 29 * b + 128) shr 8

    /**
     * dav1d's direction table: per entry the two taps' offsets (dx, dy), the entry for direction d at d + 2 — two
     * entries before and after for the secondary taps at d ± 2.
     */
    private val DIRECTIONS = arrayOf(
        intArrayOf(0, 1, 0, 2), intArrayOf(0, 1, -1, 2), intArrayOf(1, -1, 2, -2), intArrayOf(1, 0, 2, -1),
        intArrayOf(1, 0, 2, 0), intArrayOf(1, 0, 2, 1), intArrayOf(1, 1, 2, 2), intArrayOf(0, 1, 1, 2),
        intArrayOf(0, 1, 0, 2), intArrayOf(0, 1, -1, 2), intArrayOf(1, -1, 2, -2), intArrayOf(1, 0, 2, -1),
    )

    /** The divisors dav1d weighs the partial sums of the slanted lines by (840 over their length). */
    private val DIVISORS = intArrayOf(840, 420, 280, 210, 168, 140, 120)

    /**
     * An 8 × 8 block's direction, 0 to 7, and its variance, from its brightness [at] (x, y) — dav1d's
     * `cdef_find_dir_c`: for each of eight directions the squared sums of the block's lines that way, the direction
     * whose lines are most alike winning; the variance the gap to the direction at right angles.
     */
    fun direction(at: (Int, Int) -> Int): Pair<Int, Int> {
        val hv = Array(2) { IntArray(8) }
        val diag = Array(2) { IntArray(15) }
        val alt = Array(4) { IntArray(11) }
        for (y in 0 until 8) for (x in 0 until 8) {
            val px = at(x, y) - 128
            diag[0][y + x] += px
            alt[0][y + (x shr 1)] += px
            hv[0][y] += px
            alt[1][3 + y - (x shr 1)] += px
            diag[1][7 + y - x] += px
            alt[2][3 - (y shr 1) + x] += px
            hv[1][x] += px
            alt[3][(y shr 1) + x] += px
        }
        // Unsigned in dav1d; the costs stay well within a Long here.
        val cost = LongArray(8)
        for (n in 0 until 8) {
            cost[2] += hv[0][n].toLong() * hv[0][n]
            cost[6] += hv[1][n].toLong() * hv[1][n]
        }
        cost[2] *= 105
        cost[6] *= 105
        for (n in 0 until 7) {
            val d = DIVISORS[n]
            cost[0] += (diag[0][n].toLong() * diag[0][n] + diag[0][14 - n].toLong() * diag[0][14 - n]) * d
            cost[4] += (diag[1][n].toLong() * diag[1][n] + diag[1][14 - n].toLong() * diag[1][14 - n]) * d
        }
        cost[0] += diag[0][7].toLong() * diag[0][7] * 105
        cost[4] += diag[1][7].toLong() * diag[1][7] * 105
        for (n in 0 until 4) {
            var c = 0L
            for (m in 0 until 5) c += alt[n][3 + m].toLong() * alt[n][3 + m]
            c *= 105
            for (m in 0 until 3) {
                val d = DIVISORS[2 * m + 1]
                c += (alt[n][m].toLong() * alt[n][m] + alt[n][10 - m].toLong() * alt[n][10 - m]) * d
            }
            cost[n * 2 + 1] = c
        }
        var best = 0
        for (n in 1 until 8) if (cost[n] > cost[best]) best = n
        return best to ((cost[best] - cost[best xor 4]) shr 10).toInt()
    }

    /** dav1d's `adjust_strength`: the primary strength scaled by the block's variance, none for a flat block. */
    fun adjustedStrength(strength: Int, variance: Int): Int {
        if (variance == 0) return 0
        val i = if (variance shr 6 != 0) minOf(log2(variance shr 6), 12) else 0
        return (strength * (4 + i) + 8) shr 4
    }

    private fun log2(v: Int): Int = 31 - Integer.numberOfLeadingZeros(v)

    /** dav1d's `constrain`: [diff] cut down the further it reaches past what [threshold] and [shift] allow. */
    private fun constrain(diff: Int, threshold: Int, shift: Int): Int {
        val a = abs(diff)
        val v = minOf(a, maxOf(0, threshold - (a shr shift)))
        return if (diff < 0) -v else v
    }

    /**
     * [rgb] (8-bit RGB, rows from the top, [width] × [height]) deringed with [params]: every full 8 × 8 block — the
     * grid starting [originX] and [originY] pixels in, where a crop cut into the picture's own blocks — its direction
     * found on its brightness, each colour filtered along it.
     */
    fun apply(
        rgb: ByteArray,
        width: Int,
        height: Int,
        params: Params,
        originX: Int = 0,
        originY: Int = 0,
    ): ByteArray {
        require(rgb.size == width * height * 3) { "not $width × $height RGB" }
        val out = rgb.copyOf()
        fun value(x: Int, y: Int, c: Int) = rgb[(y * width + x) * 3 + c].toInt() and 0xFF
        val primary = params.primary
        val secondary = params.secondary
        val damping = params.damping
        var by = originY
        while (by + 8 <= height) {
            var bx = originX
            while (bx + 8 <= width) {
                var dir = 0
                var strength = 0
                if (primary > 0) {
                    val found = direction { x, y ->
                        luma(value(bx + x, by + y, 0), value(bx + x, by + y, 1), value(bx + x, by + y, 2))
                    }
                    dir = found.first
                    strength = adjustedStrength(primary, found.second)
                }
                if (strength != 0 || secondary != 0) {
                    filterBlock(rgb, out, width, height, bx, by, strength, secondary, dir, damping)
                }
                bx += 8
            }
            by += 8
        }
        return out
    }

    // dav1d's cdef_filter_block_c on one block of every channel, from [rgb] into [out].
    private fun filterBlock(
        rgb: ByteArray,
        out: ByteArray,
        width: Int,
        height: Int,
        bx: Int,
        by: Int,
        primary: Int,
        secondary: Int,
        dir: Int,
        damping: Int,
    ) {
        val primaryTap = 4 - (primary and 1)
        val primaryShift = if (primary > 0) maxOf(0, damping - log2(primary)) else 0
        val secondaryShift = if (secondary > 0) damping - log2(secondary) else 0
        val along = DIRECTIONS[dir + 2]
        val plus = DIRECTIONS[dir + 4]
        val minus = DIRECTIONS[dir]
        for (y in by until by + 8) for (x in bx until bx + 8) for (c in 0..2) {
            val px = rgb[(y * width + x) * 3 + c].toInt() and 0xFF
            // A tap past the picture's edge takes no part, as dav1d's padding makes it.
            fun tap(dx: Int, dy: Int): Int? {
                val tx = x + dx
                val ty = y + dy
                if (tx < 0 || ty < 0 || tx >= width || ty >= height) return null
                return rgb[(ty * width + tx) * 3 + c].toInt() and 0xFF
            }
            var sum = 0
            var min = px
            var max = px
            var tapK = primaryTap
            for (k in 0..1) {
                if (primary > 0) {
                    for (sign in intArrayOf(1, -1)) {
                        val p = tap(sign * along[2 * k], sign * along[2 * k + 1]) ?: continue
                        sum += tapK * constrain(p - px, primary, primaryShift)
                        min = minOf(min, p)
                        max = maxOf(max, p)
                    }
                    tapK = (tapK and 3) or 2
                }
                if (secondary > 0) {
                    for (offsets in arrayOf(plus, minus)) for (sign in intArrayOf(1, -1)) {
                        val s = tap(sign * offsets[2 * k], sign * offsets[2 * k + 1]) ?: continue
                        sum += (2 - k) * constrain(s - px, secondary, secondaryShift)
                        min = minOf(min, s)
                        max = maxOf(max, s)
                    }
                }
            }
            var v = px + ((sum - (if (sum < 0) 1 else 0) + 8) shr 4)
            // Only with both strengths does dav1d hold the result within the taps' range.
            if (primary > 0 && secondary > 0) v = v.coerceIn(min, max)
            out[(y * width + x) * 3 + c] = v.toByte()
        }
    }
}
