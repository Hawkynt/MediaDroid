package com.hawkynt.mediadroid.player

import kotlin.math.abs

/**
 * Undoing 3:2 pulldown: film shot at 24 frames a second, spread over 30 interlaced frames for NTSC
 * television by repeating a field in every other frame. [FieldMatch] puts each film frame's two fields
 * back together — no combing and no deinterlacing blur — and [Decimate] drops the one repeated frame in
 * every five, leaving the film's own 24.
 *
 * Converted from FFmpeg's `vf_fieldmatch.c` and `vf_decimate.c` (LGPL-2.1-or-later, © 2012 Fredrik
 * Mellbin, 2013 Clément Bœsch, after VapourSynth's VFM and VDecimate; see `THIRD_PARTY_NOTICES.md`) with
 * their default settings — matching mode pc_n, comb matching on scene changes, a cycle of five — on one
 * plane, the luma, top or bottom field first. Pictures have an even height.
 */
object Telecine {

    /** Where a frame's other field comes from: the [PREVIOUS], [CURRENT] or [NEXT] frame, or the
     * opposite field of the previous ([BOTTOM]) or next ([UPPER]) one. FFmpeg's mP, mC, mN, mB, mU. */
    enum class Match { PREVIOUS, CURRENT, NEXT, BOTTOM, UPPER }

    /** One frame's match, with the comb scores worked out on the way (-1 where none was needed). */
    data class Matched(
        val match: Match,
        /** Still combed after matching: an interlaced frame a deinterlacer must take over. */
        val combed: Boolean,
        val sceneChange: Boolean,
        val combs: List<Int>,
    ) {
        /** FFmpeg's debug line for the frame: `SC:0 | COMBS:  -1  -1  -1  -1  -1 (combpel=80) match=1 combed=NO`. */
        val log: String
            get() = "SC:${if (sceneChange) 1 else 0} | COMBS: " +
                combs.joinToString(" ") { it.toString().padStart(3) } +
                " (combpel=$COMBPEL) match=${match.ordinal} combed=${if (combed) "YES" else "NO"}"
    }

    private const val CTHRESH = 9
    private const val BLOCKX = 16
    private const val BLOCKY = 16
    private const val COMBPEL = 80
    private const val SCTHRESH = 12.0

    /**
     * FFmpeg's fieldmatch: [add] judges each frame between its neighbours, as FFmpeg does once the next
     * frame is known. The field matched from follows the order, as FFmpeg's `field=auto` does.
     */
    class FieldMatch(topFieldFirst: Boolean = true) {
        /** FFmpeg's `field`: 1 for the top field, 0 for the bottom one. */
        val field = if (topFieldFirst) 1 else 0
        private var count = 0L
        private var lastN = 0L
        private var lastSceneDiff = 0L

        /** Matches [cur] between [prev] and [next]; returns the verdict and the woven, progressive frame. */
        fun add(
            prev: Deinterlacer.Plane,
            cur: Deinterlacer.Plane,
            next: Deinterlacer.Plane,
        ): Pair<Matched, Deinterlacer.Plane> {
            val window = Window(prev, cur, next)
            val n = count++
            val combs = IntArray(5) { -1 }
            val woven = arrayOfNulls<Deinterlacer.Plane>(5)
            woven[Match.CURRENT.ordinal] = cur

            var match = compareFields(window, Match.CURRENT, Match.PREVIOUS, field)

            val threshold = (cur.width * cur.height * 255.0 * SCTHRESH / 100.0).toLong()
            var sceneChange = if (lastN == n - 1) {
                lastSceneDiff > threshold
            } else {
                lumaAbsDiff(prev, cur) > threshold
            }
            if (!sceneChange) {
                lastN = n
                lastSceneDiff = lumaAbsDiff(cur, next)
                sceneChange = lastSceneDiff > threshold
            }

            if (sceneChange) {
                fun load(m: Match) {
                    if (combs[m.ordinal] >= 0) return
                    val frame = woven[m.ordinal] ?: weave(m, field, prev, cur, next).also { woven[m.ordinal] = it }
                    combs[m.ordinal] = combedScore(frame)
                }
                val m1 = match
                val m2 = Match.NEXT
                load(m1)
                load(m2)
                val c1 = combs[m1.ordinal]
                val c2 = combs[m2.ordinal]
                if ((c2 * 3 < c1 || (c2 * 2 < c1 && c1 > COMBPEL)) && abs(c2 - c1) >= 30 && c2 < COMBPEL) {
                    match = m2
                }
            }

            val combed = combs[match.ordinal] >= COMBPEL
            val frame = woven[match.ordinal] ?: weave(match, field, prev, cur, next)
            return Matched(match, combed, sceneChange, combs.toList()) to frame
        }
    }

    private class Window(val prev: Deinterlacer.Plane, val cur: Deinterlacer.Plane, val next: Deinterlacer.Plane) {
        fun select(match: Match) = when (match) {
            Match.PREVIOUS, Match.BOTTOM -> prev
            Match.NEXT, Match.UPPER -> next
            Match.CURRENT -> cur
        }
    }

    private fun fieldBase(match: Match, field: Int) = if (match.ordinal < 3) 2 - field else 1 + field

    private fun lumaAbsDiff(a: Deinterlacer.Plane, b: Deinterlacer.Plane): Long {
        var sum = 0L
        for (i in a.pixels.indices) sum += abs(a.pixels[i] - b.pixels[i])
        return sum
    }

    /** Which of two matches leaves fewer combing artefacts, from the motion around their fields. */
    private fun compareFields(window: Window, match1: Match, match2: Match, field: Int): Match {
        val src = window.cur
        val width = src.width
        val height = src.height
        val map = IntArray(width * height)
        val startX = 8
        val stopX = width - startX

        val base1 = fieldBase(match1, field)
        val prev = window.select(match1)
        val base2 = fieldBase(match2, field)
        val next = window.select(match2)

        if ((match1.ordinal >= 3 && field == 1) || (match1.ordinal < 3 && field != 1)) {
            buildDiffMap(prev, base1, next, base2, map, base1, width, height)
        } else {
            buildDiffMap(prev, base1 + 2, next, base2 + 2, map, base1 + 2, width, height)
        }

        var accumPc = 0L
        var accumPm = 0L
        var accumPml = 0L
        var accumNc = 0L
        var accumNm = 0L
        var accumNml = 0L
        var k = 0
        var y = 2
        while (y < height - 2) {
            val srcF = base1 + 1 + 2 * k
            val prvPf = base1 + 2 * k
            val nxtPf = base2 + 2 * k
            val mapRow = base1 + 2 * k
            for (x in startX until stopX) {
                val m0 = map[mapRow * width + x]
                val m1 = map[(mapRow + 2) * width + x]
                if (m0 > 0 || m1 > 0) {
                    val temp1 = src[x, srcF - 2] + (src[x, srcF] shl 2) + src[x, srcF + 2]

                    var temp2 = abs(3 * (prev[x, prvPf] + prev[x, prvPf + 2]) - temp1)
                    if (temp2 > 23 && ((m0 and 1) != 0 || (m1 and 1) != 0)) accumPc += temp2
                    if (temp2 > 42) {
                        if ((m0 and 2) != 0 || (m1 and 2) != 0) accumPm += temp2
                        if ((m0 and 4) != 0 || (m1 and 4) != 0) accumPml += temp2
                    }

                    temp2 = abs(3 * (next[x, nxtPf] + next[x, nxtPf + 2]) - temp1)
                    if (temp2 > 23 && ((m0 and 1) != 0 || (m1 and 1) != 0)) accumNc += temp2
                    if (temp2 > 42) {
                        if ((m0 and 2) != 0 || (m1 and 2) != 0) accumNm += temp2
                        if ((m0 and 4) != 0 || (m1 and 4) != 0) accumNml += temp2
                    }
                }
            }
            y += 2
            k++
        }

        if (accumPm < 500 && accumNm < 500 && (accumPml >= 500 || accumNml >= 500) &&
            maxOf(accumPml, accumNml) > 3 * minOf(accumPml, accumNml)
        ) {
            accumPm = accumPml
            accumNm = accumNml
        }

        val norm1 = (accumPc.toFloat() / 6.0f + 0.5f).toInt()
        val norm2 = (accumNc.toFloat() / 6.0f + 0.5f).toInt()
        val mtn1 = (accumPm.toFloat() / 6.0f + 0.5f).toInt()
        val mtn2 = (accumNm.toFloat() / 6.0f + 0.5f).toInt()
        val c1 = maxOf(norm1, norm2).toFloat() / maxOf(minOf(norm1, norm2), 1).toFloat()
        val c2 = maxOf(mtn1, mtn2).toFloat() / maxOf(minOf(mtn1, mtn2), 1).toFloat()
        val mr = maxOf(mtn1, mtn2).toFloat() / maxOf(maxOf(norm1, norm2), 1).toFloat()
        return if (((mtn1 >= 500 || mtn2 >= 500) && (mtn1 * 2 < mtn2 || mtn2 * 2 < mtn1)) ||
            ((mtn1 >= 1000 || mtn2 >= 1000) && (mtn1 * 3 < mtn2 * 2 || mtn2 * 3 < mtn1 * 2)) ||
            ((mtn1 >= 2000 || mtn2 >= 2000) && (mtn1 * 5 < mtn2 * 4 || mtn2 * 5 < mtn1 * 4)) ||
            ((mtn1 >= 4000 || mtn2 >= 4000) && c2 > c1)
        ) {
            if (mtn1 > mtn2) match2 else match1
        } else if (mr.toDouble() > 0.005 && maxOf(mtn1, mtn2) > 150 && (mtn1 * 2 < mtn2 || mtn2 * 2 < mtn1)) {
            if (mtn1 > mtn2) match2 else match1
        } else {
            if (norm1 > norm2) match2 else match1
        }
    }

    /**
     * Marks where the two candidate fields differ a little (bit 0) or a lot (bits 1 and 2), on every
     * other row of [map] from [mapRow]; [prevRow] and [nextRow] are the fields' first rows.
     */
    private fun buildDiffMap(
        prev: Deinterlacer.Plane,
        prevRow: Int,
        next: Deinterlacer.Plane,
        nextRow: Int,
        map: IntArray,
        mapRow: Int,
        width: Int,
        height: Int,
    ) {
        // FFmpeg's tbuffer: row i holds the difference of the fields' rows from one row before the first.
        val rows = height shr 1
        val diff = IntArray(rows * width)
        for (i in 0 until rows) {
            for (x in 0 until width) {
                diff[i * width + x] = abs(prev[x, prevRow - 2 + 2 * i] - next[x, nextRow - 2 + 2 * i])
            }
        }
        fun dp(row: Int, x: Int) = diff[row * width + x]

        var k = 0
        var y = 2
        while (y < height - 2) {
            val row = k + 1
            val dst = (mapRow + 2 * k) * width
            for (x in 1 until width - 1) {
                val d = dp(row, x)
                if (d <= 3) continue
                var count = 0
                var u = x - 1
                while (u < x + 2 && count < 2) {
                    if (dp(row - 1, u) > 3) count++
                    if (dp(row, u) > 3) count++
                    if (dp(row + 1, u) > 3) count++
                    u++
                }
                if (count <= 1) continue
                map[dst + x] = 1
                if (d <= 19) continue
                var upper = false
                var lower = false
                count = 0
                u = x - 1
                while (u < x + 2 && count < 6) {
                    if (dp(row - 1, u) > 19) {
                        count++
                        upper = true
                    }
                    if (dp(row, u) > 19) count++
                    if (dp(row + 1, u) > 19) {
                        count++
                        lower = true
                    }
                    u++
                }
                if (count <= 3) continue
                if (upper && lower) {
                    map[dst + x] = map[dst + x] or 2
                } else {
                    var upper2 = false
                    var lower2 = false
                    for (v in maxOf(x - 4, 0) until minOf(x + 5, width)) {
                        if (y != 2 && dp(row - 2, v) > 19) upper2 = true
                        if (dp(row - 1, v) > 19) upper = true
                        if (dp(row + 1, v) > 19) lower = true
                        if (y != height - 4 && dp(row + 2, v) > 19) lower2 = true
                    }
                    if ((upper && (lower || upper2)) || (lower && (upper || lower2))) {
                        map[dst + x] = map[dst + x] or 2
                    } else if (count > 5) {
                        map[dst + x] = map[dst + x] or 4
                    }
                }
            }
            y += 2
            k++
        }
    }

    /** The frame [match] makes: [cur] with one field taken from its neighbour. */
    fun weave(
        match: Match,
        field: Int,
        prev: Deinterlacer.Plane,
        cur: Deinterlacer.Plane,
        next: Deinterlacer.Plane,
    ): Deinterlacer.Plane {
        if (match == Match.CURRENT) return cur
        val (other, otherField) = when (match) {
            Match.PREVIOUS -> prev to field
            Match.NEXT -> next to field
            Match.BOTTOM -> prev to 1 - field
            Match.UPPER -> next to 1 - field
            Match.CURRENT -> error("unreachable")
        }
        val out = cur.pixels.copyOf()
        for (y in otherField until cur.height step 2) {
            System.arraycopy(other.pixels, y * cur.width, out, y * cur.width, cur.width)
        }
        return Deinterlacer.Plane(cur.width, cur.height, out)
    }

    /** How combed [frame] is: the most combed pixels in any half-overlapping 16 × 16 block. */
    fun combedScore(frame: Deinterlacer.Plane): Int {
        val width = frame.width
        val height = frame.height
        val mask = BooleanArray(width * height)
        val cthresh6 = CTHRESH * 6
        fun p(x: Int, y: Int) = frame[x, y]
        // The [1 -3 4 -3 1] vertical filter, with FFmpeg's mirrored rows at the edges.
        fun combs(x: Int, y: Int, m2: Int, m1: Int, p1: Int, p2: Int) =
            abs(4 * p(x, y) - 3 * (p(x, y + m1) + p(x, y + p1)) + (p(x, y + m2) + p(x, y + p2))) > cthresh6

        for (y in 0 until height) {
            for (x in 0 until width) {
                val s1 = if (y == 0) abs(p(x, 0) - p(x, 1)) else abs(p(x, y) - p(x, y - 1))
                val s2 = if (y == 0 || y == height - 1) Int.MAX_VALUE else abs(p(x, y) - p(x, y + 1))
                if (s1 <= CTHRESH || s2 <= CTHRESH) continue
                val combed = when (y) {
                    0 -> combs(x, y, 2, 1, 1, 2)
                    1 -> combs(x, y, 2, -1, 1, 2)
                    height - 2 -> combs(x, y, -2, -1, 1, -2)
                    height - 1 -> combs(x, y, -2, -1, -1, -2)
                    else -> combs(x, y, -2, -1, 1, 2)
                }
                if (combed) mask[y * width + x] = true
            }
        }

        val xHalf = BLOCKX / 2
        val yHalf = BLOCKY / 2
        val xBlocks = (width + xHalf) / BLOCKX + 1
        val xBlocks4 = xBlocks shl 2
        val yBlocks = (height + yHalf) / BLOCKY + 1
        val counts = IntArray(xBlocks * yBlocks * 4)
        var heightA = (height / yHalf) * yHalf
        val widthA = (width / xHalf) * xHalf
        if (heightA == height) heightA = height - yHalf
        fun tripled(x: Int, y: Int) =
            mask[(y - 1) * width + x] && mask[y * width + x] && mask[(y + 1) * width + x]
        fun add(x: Int, y: Int, v: Int) {
            val temp1 = (y / BLOCKY) * xBlocks4
            val temp2 = ((y + yHalf) / BLOCKY) * xBlocks4
            val box1 = (x / BLOCKX) * 4
            val box2 = ((x + xHalf) / BLOCKX) * 4
            counts[temp1 + box1] += v
            counts[temp1 + box2 + 1] += v
            counts[temp2 + box1 + 2] += v
            counts[temp2 + box2 + 3] += v
        }
        fun verticalHalf(from: Int, to: Int) {
            for (y in from until to) {
                for (x in 0 until width) if (tripled(x, y)) add(x, y, 1)
            }
        }

        verticalHalf(1, yHalf)
        var y = yHalf
        while (y < heightA) {
            var x = 0
            while (x < widthA) {
                var sum = 0
                for (u in 0 until yHalf) for (v in 0 until xHalf) if (tripled(x + v, y + u)) sum++
                if (sum != 0) add(x, y, sum)
                x += xHalf
            }
            for (column in widthA until width) {
                var sum = 0
                for (u in 0 until yHalf) if (tripled(column, y + u)) sum++
                if (sum != 0) add(column, y, sum)
            }
            y += yHalf
        }
        verticalHalf(heightA, height - 1)
        return counts.maxOrNull() ?: 0
    }

    /**
     * FFmpeg's decimate with a cycle of five: [add] takes each matched frame; once five have come it
     * says which of them to drop — the one most like the frame before it, or a scene change's. [mixed]
     * is FFmpeg's `mixed=1`, for video that is only partly film: a cycle with no repeated frame drops none
     * (its [Cycle.drop] is -1). [duplicatePercent] is FFmpeg's `dupthresh`: how unlike its predecessor a
     * frame may be, in percent of full scale over a 32 × 32 block, and still count as repeated.
     */
    class Decimate(private val mixed: Boolean = false, private val duplicatePercent: Double = DUPTHRESH) {
        data class Metrics(val totDiff: Long, val maxBlockDiff: Long)

        data class Cycle(
            val metrics: List<Metrics>,
            val drop: Int,
            val sceneChange: Int,
            val duplicate: Int,
            val lowest: Int,
        ) {
            /** FFmpeg's debug lines for the cycle. */
            val log: List<String>
                get() = listOf("1/$CYCLE frame drop:") + metrics.mapIndexed { i, m ->
                    "  #${i + 1}: totdiff=${hex(m.totDiff)} maxbdiff=${hex(m.maxBlockDiff)}" +
                        (if (i == sceneChange) " sc" else "") + (if (i == duplicate) " dup" else "") +
                        (if (i == lowest) " lowest" else "") + (if (i == drop) " [DROP]" else "")
                }

            private fun hex(value: Long) = java.lang.Long.toHexString(value).padStart(8, '0')
        }

        private var last: Deinterlacer.Plane? = null
        private val queue = ArrayList<Pair<Deinterlacer.Plane, Metrics>>()

        /** Adds the next frame; at the end of each cycle returns its verdict, otherwise null. */
        fun add(frame: Deinterlacer.Plane): Cycle? {
            val prev = queue.lastOrNull()?.first ?: last
            val metrics = if (prev == null) Metrics(Long.MAX_VALUE, Long.MAX_VALUE) else diffs(prev, frame)
            queue += frame to metrics
            if (queue.size < CYCLE) return null
            last = frame
            var lowest = 0
            var sceneChange = -1
            val sceneThreshold = (255L * frame.width * frame.height * DECIMATE_SCTHRESH / 100).toLong()
            val dupThreshold = (255L * DECIMATE_BLOCK * DECIMATE_BLOCK * duplicatePercent / 100).toLong()
            for (i in 0 until CYCLE) {
                if (queue[i].second.totDiff > sceneThreshold) sceneChange = i
                if (queue[i].second.maxBlockDiff < queue[lowest].second.maxBlockDiff) lowest = i
            }
            val duplicate = if (queue[lowest].second.maxBlockDiff < dupThreshold) lowest else -1
            val drop = when {
                mixed && duplicate < 0 -> -1
                sceneChange >= 0 && duplicate < 0 -> sceneChange
                else -> lowest
            }
            val cycle = Cycle(queue.map { it.second }, drop, sceneChange, duplicate, lowest)
            queue.clear()
            return cycle
        }

        private fun diffs(a: Deinterlacer.Plane, b: Deinterlacer.Plane): Metrics {
            val half = DECIMATE_BLOCK / 2
            val xBlocks = (a.width + half - 1) / half
            val yBlocks = (a.height + half - 1) / half
            val blocks = LongArray(xBlocks * yBlocks)
            for (y in 0 until a.height) {
                for (x in 0 until a.width) {
                    blocks[(y / half) * xBlocks + x / half] += abs(a[x, y] - b[x, y]).toLong()
                }
            }
            var max = -1L
            for (i in 0 until yBlocks - 1) {
                for (j in 0 until xBlocks - 1) {
                    val sum = blocks[i * xBlocks + j] + blocks[i * xBlocks + j + 1] +
                        blocks[(i + 1) * xBlocks + j] + blocks[(i + 1) * xBlocks + j + 1]
                    if (sum > max) max = sum
                }
            }
            return Metrics(blocks.sum(), max)
        }
    }

    const val CYCLE = 5
    private const val DECIMATE_BLOCK = 32
    private const val DUPTHRESH = 1.1
    private const val DECIMATE_SCTHRESH = 15.0
}
