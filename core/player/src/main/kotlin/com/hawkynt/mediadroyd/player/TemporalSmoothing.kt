package com.hawkynt.mediadroyd.player

import kotlin.math.abs

/**
 * Motion-compensated temporal smoothing of a deinterlaced picture (PictureAdjustment.Deinterlace.SMOOTH_MC), after the
 * method QTGMC describes: every field is first deinterlaced on its own (the base, here [Deinterlacer.Method.BWDIF] at
 * field rate — on such pictures nearer the truth than TomsMoComp); each field's base is then averaged with the bases of
 * the fields before and after it, each moved along the motion found between them, the more the better they match; the
 * softening that brings is sharpened back — contra-sharpening, which gives back at most what the averaging took away
 * — held within what the base itself shows around each pixel (QTGMC's "repair"); and the field's own lines, which came
 * from the camera, are put back as they were. Random noise and the flicker between the fields' own guesses average
 * out, while edges stay where they are.
 *
 * Written from QTGMC's documented method (Vit, Didée, -Vit-'s QTGMC for AviSynth; GPL — method only, no code taken,
 * see `THIRD_PARTY_NOTICES.md`), with a local search per pixel on the graphics chip where QTGMC uses MVTools' blocks:
 * displacements of up to [SEARCH_X] pixels across and [SEARCH_Y] down or up, the one whose 3 × 3 patch differs least.
 * Held to ground truth: progressive moving pictures split into fields, the result nearer the original frames than
 * BWDIF alone (TemporalSmoothingTest).
 */
object TemporalSmoothing {

    /** How far across the search looks either way, in pixels. */
    const val SEARCH_X = 3

    /** How far down or up the search looks, in pixels. */
    const val SEARCH_Y = 2

    /** A neighbour joins the average only where its patch differs by less than this much a pixel on average. */
    const val MATCH = 32

    /** The shown pixel's weight in the average, and a neighbour's at a perfect match, falling to 0 at [MATCH]. */
    const val SELF_WEIGHT = 8
    const val FULL_WEIGHT = 4

    /**
     * The picture of [cur]'s field (its second where [second], by [topFieldFirst]): the field's base averaged along
     * the motion with the bases of the field before and after, sharpened back within the base's own range, and the
     * field's own lines restored. [prevPrev] is the frame two before and [after] the one after [next] — the bases of
     * the neighbouring fields weigh them in their temporal check; where a stage has none, the nearest frame stands in.
     */
    fun deinterlace(
        prevPrev: Deinterlacer.Plane,
        prev: Deinterlacer.Plane,
        cur: Deinterlacer.Plane,
        next: Deinterlacer.Plane,
        after: Deinterlacer.Plane,
        second: Boolean,
        topFieldFirst: Boolean,
    ): Deinterlacer.Plane {
        val method = Deinterlacer.Method.BWDIF
        fun base(a: Deinterlacer.Plane, b: Deinterlacer.Plane, c: Deinterlacer.Plane, s: Boolean) =
            Deinterlacer.deinterlace(method, a, b, c, s, topFieldFirst)
        val earlier: Deinterlacer.Plane
        val shown: Deinterlacer.Plane
        val later: Deinterlacer.Plane
        if (second) {
            earlier = base(prev, cur, next, false)
            shown = base(prev, cur, next, true)
            later = base(cur, next, after, false)
        } else {
            earlier = base(prevPrev, prev, cur, true)
            shown = base(prev, cur, next, false)
            later = base(prev, cur, next, true)
        }
        val smoothed = smooth(earlier, shown, later)
        return sharpen(smoothed, shown, cur, Deinterlacer.rebuiltParity(second, topFieldFirst))
    }

    /**
     * [shown] averaged with [earlier] and [later], each taken at the displacement whose 3 × 3 patch matches [shown]'s
     * best (the nearer displacement where two match as well), weighted by how well: [FULL_WEIGHT] for a perfect match,
     * falling to nothing at an average difference of [MATCH], against the shown pixel's [SELF_WEIGHT]; rounded.
     */
    fun smooth(earlier: Deinterlacer.Plane, shown: Deinterlacer.Plane, later: Deinterlacer.Plane): Deinterlacer.Plane {
        val w = shown.width
        val h = shown.height
        val out = IntArray(w * h)
        fun at(p: Deinterlacer.Plane, x: Int, y: Int) = p[x.coerceIn(0, w - 1), y.coerceIn(0, h - 1)]
        fun patch(p: Deinterlacer.Plane, x: Int, y: Int, dx: Int, dy: Int): Int {
            var sad = 0
            for (v in -1..1) for (u in -1..1) sad += abs(at(p, x + dx + u, y + dy + v) - at(shown, x + u, y + v))
            return sad
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = SELF_WEIGHT * shown[x, y]
                var count = SELF_WEIGHT
                for (p in arrayOf(earlier, later)) {
                    var best = Int.MAX_VALUE
                    var value = 0
                    for ((dx, dy) in CANDIDATES) {
                        val sad = patch(p, x, y, dx, dy)
                        if (sad < best) {
                            best = sad
                            value = at(p, x + dx, y + dy)
                        }
                    }
                    val weight = maxOf(0, FULL_WEIGHT - FULL_WEIGHT * best / (MATCH * 9))
                    sum += weight * value
                    count += weight
                }
                out[y * w + x] = (sum + count / 2) / count
            }
        }
        return Deinterlacer.Plane(w, h, out)
    }

    /**
     * [smoothed] sharpened by its difference from its own [1 2 1] blur, held within the lowest and highest of [base]'s
     * 3 × 3 around each pixel, and the rows of [cur] that came from the camera — those of parity [keptParity], 0 the
     * even ones — put back as they were.
     */
    fun sharpen(
        smoothed: Deinterlacer.Plane,
        base: Deinterlacer.Plane,
        cur: Deinterlacer.Plane,
        keptParity: Int,
    ): Deinterlacer.Plane {
        val w = smoothed.width
        val h = smoothed.height
        val out = IntArray(w * h)
        fun at(p: Deinterlacer.Plane, x: Int, y: Int) = p[x.coerceIn(0, w - 1), y.coerceIn(0, h - 1)]
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (y % 2 == keptParity) {
                    out[y * w + x] = cur[x, y]
                    continue
                }
                var blur = 0
                var low = 255
                var high = 0
                for (v in -1..1) {
                    for (u in -1..1) {
                        blur += at(smoothed, x + u, y + v) * (2 - abs(u)) * (2 - abs(v))
                        val b = at(base, x + u, y + v)
                        low = minOf(low, b)
                        high = maxOf(high, b)
                    }
                }
                // Contra-sharpening: the sharpening gives back at most what the smoothing took away, in its direction.
                val s = smoothed[x, y]
                val detail = s - ((blur + 8) shr 4)
                val taken = base[x, y] - s
                val back = if (detail > 0 == taken > 0) minOf(abs(detail), abs(taken)) * Integer.signum(taken) else 0
                out[y * w + x] = (s + back).coerceIn(low, high)
            }
        }
        return Deinterlacer.Plane(w, h, out)
    }

    /** The displacements searched, nearest first, so that of two equal matches the nearer one wins. */
    val CANDIDATES: List<Pair<Int, Int>> = (-SEARCH_Y..SEARCH_Y)
        .flatMap { dy -> (-SEARCH_X..SEARCH_X).map { it to dy } }
        .sortedWith(compareBy({ abs(it.first) + abs(it.second) }, { it.second }, { it.first }))
}
