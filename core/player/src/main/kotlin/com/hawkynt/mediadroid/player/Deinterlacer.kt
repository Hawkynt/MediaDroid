package com.hawkynt.mediadroid.player

import kotlin.math.abs

/**
 * The temporal deinterlacers: a picture of two interlaced fields with one of them rebuilt from its own
 * lines and from the frames before and after, so still parts keep their full detail and moving parts
 * lose their combing. The reference the player's graphics passes are written from, checked byte for
 * byte against their originals.
 *
 * YADIF and BWDIF converted from FFmpeg's libavfilter (LGPL-2.1-or-later, see `THIRD_PARTY_NOTICES.md`): `vf_yadif.c`
 * (Michael Niedermayer) and `vf_bwdif.c` (Thomas Mundt, after the BBC's w3fdif coefficients). Greedy High Motion
 * converted from GStreamer's `greedyh.c` (tvtime's, by Tom Barry after DScaler, relicensed LGPL-2.0-or-later for
 * GStreamer). Greedy 2-Frame written anew from DScaler's (GPL-2.0, behaviour only), held to xine-lib's port of it.
 * TomsMoComp converted from GStreamer's `tomsmocomp` (Tom Barry's, relicensed LGPL-2.0-or-later for GStreamer), its
 * MMXEXT search at the default effort, 5.
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

        /**
         * Greedy High Motion: of the line from the field before and the one from the field after, the one nearer
         * the average of the lines around it — smoothed along the row, the diagonal jaggie reduction — held within
         * a few steps of the lines above and below, and blended towards that average the more the two differ.
         */
        GREEDY_HM,

        /**
         * Greedy 2-Frame: the two fields' worth of the line woven (averaged) where neither it nor the lines above or
         * below moved since the frame before, judged two pixels at a time; otherwise the lines around it averaged.
         */
        GREEDY_2FRAME,

        /**
         * TomsMoComp, Tom Barry's motion search: each missing pixel woven from the pair of pixels — one from the field
         * before, one from the field after, straight across, a pixel to either side, or half a pixel — that agree
         * best, where they agree well enough; otherwise an edge-directed bob along the best of five directions.
         */
        TOMSMOCOMP,
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
                    Method.GREEDY_HM -> greedyHm(cur, prev2, next2, x, y)
                    Method.GREEDY_2FRAME -> greedy2Frame(prev, cur, prev2, next2, x, y)
                    Method.TOMSMOCOMP -> tomsMoComp(prev, cur, prev2, next2, x, y)
                }
            }
        }
        return Plane(w, h, out)
    }

    /** The graphics pass's `uMethod` for [method] (`temporal.frag`): YADIF 0, BWDIF 1, Greedy HM 2, Greedy 2F 3, … */
    fun shaderMethod(method: Method): Int = when (method) {
        Method.YADIF -> 0
        Method.BWDIF -> 1
        Method.GREEDY_HM -> 2
        Method.GREEDY_2FRAME -> 3
        Method.TOMSMOCOMP -> TOMSMOCOMP
    }

    /** The graphics pass's `uMethod` that rebuilds nothing: the frame woven as it is. */
    const val WEAVE = 4

    /** The graphics pass's `uMethod` for old-console mode ([OldGame]): the newest field stretched, `uAverage` asks. */
    const val OLD_CONSOLE = 5

    /** The graphics pass's `uMethod` for [Method.TOMSMOCOMP]. */
    const val TOMSMOCOMP = 6

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

    /** Greedy High Motion's settings, GStreamer's defaults (DScaler's): how far past the lines around a woven pixel
     * may lie, how much the two fields' lines may differ before blending starts, and how fast it blends then. */
    const val GREEDY_MAX_COMB = 5
    const val GREEDY_MOTION_THRESHOLD = 25
    const val GREEDY_MOTION_SENSE = 30

    /** Greedy 2-Frame's luma threshold, DScaler's: half a difference above this is movement. */
    const val GREEDY_2FRAME_THRESHOLD = 4

    /**
     * Greedy High Motion for the row [y] between the kept field's rows: [earlier] and [later] are the pictures of
     * the fields either side of the kept one (GStreamer's L2 and L2P). At the picture's top the row below is copied;
     * at its bottom the earlier field's row is woven in, as GStreamer's frame loop does.
     */
    private fun greedyHm(cur: Plane, earlier: Plane, later: Plane, x: Int, y: Int): Int {
        if (y == 0) return cur[x, 1]
        if (y + 1 == cur.height) return earlier[x, y]
        fun average(column: Int) = (cur[column, y - 1] + cur[column, y + 1]) / 2
        val l1 = cur[x, y - 1]
        val l3 = cur[x, y + 1]
        val avg = average(x)
        // The averages one pixel either side, the edge columns standing in for the missing ones.
        val avgBefore = if (x == 0) avg else average(x - 1)
        val avgAfter = if (x + 1 == cur.width) avg else average(x + 1)
        val avgSc = (avg + (avgBefore + avgAfter) / 2) / 2
        val l2 = earlier[x, y]
        val lp2 = later[x, y]
        val best = if (abs(l2 - avgSc) > abs(lp2 - avgSc)) lp2 else l2
        val max = minOf(maxOf(l1, l3) + GREEDY_MAX_COMB, 255)
        val min = maxOf(minOf(l1, l3) - GREEDY_MAX_COMB, 0)
        val clipped = best.coerceIn(min, max)
        val motion = (maxOf(abs(l2 - lp2) - GREEDY_MOTION_THRESHOLD, 0) * GREEDY_MOTION_SENSE).coerceAtMost(256)
        return (clipped * (256 - motion) + avgSc * motion) / 256
    }

    /**
     * Greedy 2-Frame for the row [y] of the newest field ([later], with [earlier] the same rows a frame before),
     * between the kept field's rows of [cur] (and of [prev], a frame before). The rows at the picture's edges, lacking
     * a kept row on one side, take the newest field's own.
     */
    private fun greedy2Frame(prev: Plane, cur: Plane, earlier: Plane, later: Plane, x: Int, y: Int): Int {
        if (y == 0 || y + 1 == cur.height) return later[x, y]
        val pair = x and 1.inv()
        fun still(a: Plane, b: Plane, row: Int): Boolean = (pair..minOf(pair + 1, cur.width - 1)).all {
            abs(a[it, row] - b[it, row]) shr 1 <= GREEDY_2FRAME_THRESHOLD
        }
        val weave = still(later, earlier, y) && (still(cur, prev, y - 1) || still(cur, prev, y + 1))
        return if (weave) (later[x, y] + earlier[x, y] + 1) shr 1 else (cur[x, y - 1] + cur[x, y + 1] + 1) shr 1
    }

    /** Rounded up, as MMX's `pavgb`. */
    private fun pavg(a: Int, b: Int) = (a + b + 1) shr 1

    /**
     * TomsMoComp for the row [y] between the kept field's rows of [cur], [earlier] and [later] the pictures of the
     * fields either side (GStreamer's pWeaveSrcP and pWeaveSrc), [prev] the kept field's frame before (pCopySrcP). The
     * first and last rebuilt row are the kept field's first and last; four columns at either side a plain bob.
     */
    private fun tomsMoComp(prev: Plane, cur: Plane, earlier: Plane, later: Plane, x: Int, y: Int): Int {
        val w = cur.width
        val lines = cur.height / 2
        val line = y / 2
        if (line == 0 || line == lines - 1) return cur[x, y xor 1]
        val up = y - 1
        val down = y + 1
        val b = cur[x, up]
        val e = cur[x, down]
        if (x < 4 || x >= w - 4) return pavg(b, e)

        // The weird bob: of four diagonals, the pair that agrees best (a later one where as good), clipped between the
        // pixels above and below, and then those two themselves where they agree as well.
        var bob = 0
        var bobDiff = Int.MAX_VALUE
        for (dx in intArrayOf(-1, 1, -2, 2)) {
            val u = cur[x + dx, up]
            val v = cur[x - dx, down]
            if (abs(u - v) <= bobDiff) {
                bob = pavg(u, v)
                bobDiff = abs(u - v)
            }
        }
        val low = minOf(b, e)
        val high = maxOf(b, e)
        bob = bob.coerceIn(low, high)
        // Where neither pixel around moved more than 4 since the frame before, the result is not clipped at the end.
        val still = maxOf(abs(b - prev[x, up]), abs(e - prev[x, down])) <= TOMS_MAX_MOVE
        if (abs(b - e) <= bobDiff) {
            bob = pavg(b, e)
            bobDiff = abs(b - e)
        }

        // The search: pairs across the fields either side, the one that agrees best (a later one where as good).
        var weave = 0
        var weaveDiff = 255
        fun merge(value: Int, weight: Int) {
            if (weight <= weaveDiff) {
                weave = value
                weaveDiff = weight
            }
        }
        fun pair(u: Int, v: Int) = merge(pavg(u, v), abs(u - v))
        pair(earlier[x - 1, y], later[x + 1, y])
        pair(earlier[x + 1, y], later[x - 1, y])
        pair(pavg(earlier[x - 1, y], earlier[x, y]), pavg(later[x, y], later[x + 1, y]))
        pair(pavg(earlier[x + 1, y], earlier[x, y]), pavg(later[x, y], later[x - 1, y]))
        // A slight bias towards no motion: what was found so far rates one worse.
        weaveDiff = minOf(weaveDiff + 1, 255)
        pair(earlier[x, y], later[x, y])

        // The weave where it rates no worse than the bob's uncertainty (at most 10) and 4 more.
        val useWeave = maxOf(weaveDiff - minOf(bobDiff, 10), 0) <= 4
        val result = if (useWeave) weave else bob
        return if (still) result else result.coerceIn(low, high)
    }

    private const val TOMS_MAX_MOVE = 4

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
