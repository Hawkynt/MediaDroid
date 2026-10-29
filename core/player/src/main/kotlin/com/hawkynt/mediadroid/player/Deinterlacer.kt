package com.hawkynt.mediadroid.player

import kotlin.math.abs

/**
 * The temporal deinterlacers: a picture of two interlaced fields with one of them rebuilt from its own
 * lines and from the frames before and after, so still parts keep their full detail and moving parts
 * lose their combing. The reference the player's graphics passes are written from, checked byte for
 * byte against FFmpeg's own filters.
 *
 * Converted from FFmpeg's libavfilter (LGPL-2.1-or-later, see `THIRD_PARTY_NOTICES.md`): YADIF
 * (`vf_yadif.c`, Michael Niedermayer) and BWDIF (`vf_bwdif.c`, Thomas Mundt, after the BBC's w3fdif
 * coefficients).
 */
object Deinterlacer {

    /** One 8-bit picture plane, row by row from the top. */
    class Plane(val width: Int, val height: Int, val pixels: IntArray) {
        operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
    }

    enum class Method {
        /** Yet Another DeInterlacing Filter: the spatial guess along the best of five directions, held
         * within what the neighbouring fields allow. */
        YADIF,

        /** Bob Weaver: YADIF's temporal check with the w3fdif filter's cubic and high-frequency taps. */
        BWDIF,
    }

    /**
     * [cur] with one field rebuilt. With [second] false the later field is rebuilt, from [prev] and [cur]
     * — the picture at the earlier field's moment; with [second] true the earlier field, from [cur] and
     * [next] — the picture half a frame later. The earlier field is the top one when [topFieldFirst]
     * (see [FieldOrder]). All three planes have the same size, at least 10 × 10.
     */
    fun deinterlace(
        method: Method,
        prev: Plane,
        cur: Plane,
        next: Plane,
        second: Boolean,
        topFieldFirst: Boolean,
    ): Plane {
        val w = cur.width
        val h = cur.height
        val out = cur.pixels.copyOf()
        // FFmpeg: the field parity is tff ^ !second, and the rows rebuilt are those where (y ^ parity) & 1.
        val parity = rebuiltParity(second, topFieldFirst)
        val prev2 = if (second) cur else prev
        val next2 = if (second) next else cur
        for (y in 0 until h) {
            if ((y xor parity) and 1 == 0) continue
            for (x in 0 until w) {
                out[y * w + x] = when (method) {
                    Method.YADIF -> yadif(prev, cur, next, prev2, next2, x, y)
                    Method.BWDIF -> bwdif(prev, cur, next, prev2, next2, x, y)
                }
            }
        }
        return Plane(w, h, out)
    }

    /** The rows rebuilt are those whose number has this parity flipped: 0 rebuilds the odd rows, 1 the even. */
    fun rebuiltParity(second: Boolean, topFieldFirst: Boolean): Int =
        (if (topFieldFirst) 1 else 0) xor (if (second) 0 else 1)

    private fun yadif(prev: Plane, cur: Plane, next: Plane, prev2: Plane, next2: Plane, x: Int, y: Int): Int {
        val w = cur.width
        val h = cur.height
        val m = if (y > 0) -1 else 1
        val p = if (y + 1 < h) 1 else -1
        val c = cur[x, y + m]
        val d = (prev2[x, y] + next2[x, y]) shr 1
        val e = cur[x, y + p]
        val temporalDiff0 = abs(prev2[x, y] - next2[x, y])
        val temporalDiff1 = (abs(prev[x, y + m] - c) + abs(prev[x, y + p] - e)) shr 1
        val temporalDiff2 = (abs(next[x, y + m] - c) + abs(next[x, y + p] - e)) shr 1
        var diff = maxOf(temporalDiff0 shr 1, temporalDiff1, temporalDiff2)
        var spatialPred = (c + e) shr 1

        // The edge-directed guess, three columns in from either side: each diagonal is tried only
        // while the one before it was the better match.
        if (x >= 3 && x < w - 3) {
            var spatialScore = abs(cur[x - 1, y + m] - cur[x - 1, y + p]) + abs(c - e) +
                abs(cur[x + 1, y + m] - cur[x + 1, y + p]) - 1
            fun check(j: Int): Boolean {
                val score = abs(cur[x - 1 + j, y + m] - cur[x - 1 - j, y + p]) +
                    abs(cur[x + j, y + m] - cur[x - j, y + p]) +
                    abs(cur[x + 1 + j, y + m] - cur[x + 1 - j, y + p])
                if (score >= spatialScore) return false
                spatialScore = score
                spatialPred = (cur[x + j, y + m] + cur[x - j, y + p]) shr 1
                return true
            }
            if (check(-1)) check(-2)
            if (check(1)) check(2)
        }

        // The rows next to the picture's top and bottom edge skip the check two rows out (FFmpeg's mode 2).
        if (y != 1 && y + 2 != h) {
            val b = (prev2[x, y + 2 * m] + next2[x, y + 2 * m]) shr 1
            val f = (prev2[x, y + 2 * p] + next2[x, y + 2 * p]) shr 1
            val max = maxOf(d - e, d - c, minOf(b - c, f - e))
            val min = minOf(d - e, d - c, maxOf(b - c, f - e))
            diff = maxOf(diff, min, -max)
        }

        return spatialPred.coerceIn(d - diff, d + diff)
    }

    private val COEF_LF = intArrayOf(4309, 213)
    private val COEF_HF = intArrayOf(5570, 3801, 1016)
    private val COEF_SP = intArrayOf(5077, 981)

    private fun bwdif(prev: Plane, cur: Plane, next: Plane, prev2: Plane, next2: Plane, x: Int, y: Int): Int {
        val h = cur.height
        val edge = y < 4 || y + 5 > h
        val m = if (!edge || y > 0) -1 else 1
        val p = if (!edge || y + 1 < h) 1 else -1
        val c = cur[x, y + m]
        val d = (prev2[x, y] + next2[x, y]) shr 1
        val e = cur[x, y + p]
        val temporalDiff0 = abs(prev2[x, y] - next2[x, y])
        val temporalDiff1 = (abs(prev[x, y + m] - c) + abs(prev[x, y + p] - e)) shr 1
        val temporalDiff2 = (abs(next[x, y + m] - c) + abs(next[x, y + p] - e)) shr 1
        var diff = maxOf(temporalDiff0 shr 1, temporalDiff1, temporalDiff2)
        if (diff == 0) return d

        fun spatialCheck() {
            val b = ((prev2[x, y - 2] + next2[x, y - 2]) shr 1) - c
            val f = ((prev2[x, y + 2] + next2[x, y + 2]) shr 1) - e
            val dc = d - c
            val de = d - e
            val max = maxOf(de, dc, minOf(b, f))
            val min = minOf(de, dc, maxOf(b, f))
            diff = maxOf(diff, min, -max)
        }

        val interpol = if (edge) {
            if (y >= 2 && y + 3 <= h) spatialCheck()
            (c + e) shr 1
        } else {
            spatialCheck()
            if (abs(c - e) > temporalDiff0) {
                val hf = (COEF_HF[0] * (prev2[x, y] + next2[x, y]) -
                    COEF_HF[1] * (prev2[x, y - 2] + next2[x, y - 2] + prev2[x, y + 2] + next2[x, y + 2]) +
                    COEF_HF[2] * (prev2[x, y - 4] + next2[x, y - 4] + prev2[x, y + 4] + next2[x, y + 4])) shr 2
                (hf + COEF_LF[0] * (c + e) - COEF_LF[1] * (cur[x, y - 3] + cur[x, y + 3])) shr 13
            } else {
                (COEF_SP[0] * (c + e) - COEF_SP[1] * (cur[x, y - 3] + cur[x, y + 3])) shr 13
            }
        }
        return interpol.coerceIn(d - diff, d + diff).coerceIn(0, 255)
    }
}
