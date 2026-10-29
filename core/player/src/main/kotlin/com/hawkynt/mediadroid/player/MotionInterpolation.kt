package com.hawkynt.mediadroid.player

import kotlin.math.abs

/**
 * Motion-compensated frame interpolation: pictures between two frames built from the motion between
 * them, so a pan or a moving object moves on the screen's refreshes instead of stepping on the film's.
 * Every 16-pixel block of the picture between gets one motion vector, found from both frames at once
 * ([Estimator]: bilateral search with the enhanced predictive zonal search), and each pixel between mixes
 * the frames along the vectors of the up to four blocks whose doubled windows cover it, weighted towards
 * their centres ([compensate]: overlapped block motion compensation). A cut is not bridged ([SceneChange]).
 *
 * Converted from FFmpeg's `vf_minterpolate.c` and `motion_estimation.c` (LGPL-2.1-or-later, © 2014-2015
 * Michael Niedermayer, 2016 Davinder Singh; see `THIRD_PARTY_NOTICES.md`) at their defaults — bilateral
 * motion estimation, EPZS, 16-pixel blocks, a search range of 32, OBMC, scene changes by frame difference
 * at 10 — on one plane.
 */
object MotionInterpolation {

    /** The mixing position's scale: 0 is the earlier frame, [ALPHA_MAX] the later. */
    const val ALPHA_MAX = 1024

    /** The block size and its base-2 logarithm. */
    const val BLOCK = 16
    private const val LOG2_BLOCK = 4
    private const val SEARCH = 32
    private const val COST_PRED_SCALE = 64
    private const val SCENE_THRESHOLD = 10.0

    private val DIAMOND = arrayOf(intArrayOf(-1, 0), intArrayOf(0, -1), intArrayOf(1, 0), intArrayOf(0, 1))

    /**
     * The bilateral motion search between two frames: for each block of the picture half-way between, the
     * half-vector (x, y) to the earlier frame — the later frame lies the same way back. Keeps the last two
     * pictures' vectors as the search's temporal predictors, as FFmpeg's mv_table does.
     */
    class Estimator(val width: Int, val height: Int) {
        val blocksWide = width shr LOG2_BLOCK
        val blocksHigh = height shr LOG2_BLOCK
        private val count = blocksWide * blocksHigh
        private val xMax = (blocksWide - 1) shl LOG2_BLOCK
        private val yMax = (blocksHigh - 1) shl LOG2_BLOCK

        // FFmpeg's mv_table[0..2]: this, the previous and the one before that picture's vectors.
        private val tables = Array(3) { IntArray(count * 2) }

        /** The latest search's half-vectors, x and y per block, row by row. */
        val vectors = IntArray(count * 2)

        private var predX = 0
        private var predY = 0

        init {
            require(blocksWide >= 2 && blocksHigh >= 2) { "at least two blocks each way" }
        }

        /** A new frame came in: the predictor tables move back one picture, as on every frame FFmpeg takes. */
        fun advance() {
            tables[1].copyInto(tables[2])
            tables[0].copyInto(tables[1])
        }

        /** Searches every block between [cur] (the earlier frame) and [ref] (the later one). */
        fun estimate(cur: Deinterlacer.Plane, ref: Deinterlacer.Plane) {
            vectors.fill(0)
            for (mbY in 0 until blocksHigh) {
                for (mbX in 0 until blocksWide) search(cur, ref, mbX, mbY)
            }
        }

        private fun search(cur: Deinterlacer.Plane, ref: Deinterlacer.Plane, mbX: Int, mbY: Int) {
            val xMb = mbX shl LOG2_BLOCK
            val yMb = mbY shl LOG2_BLOCK
            val i = mbX + mbY * blocksWide
            val t0 = tables[0]
            val t1 = tables[1]
            val t2 = tables[2]
            val preds0 = ArrayList<IntArray>(5)
            val preds1 = ArrayList<IntArray>(5)
            preds0 += intArrayOf(0, 0)
            if (mbX > 0) preds0 += intArrayOf(t0[(i - 1) * 2], t0[(i - 1) * 2 + 1])
            if (mbY > 0) preds0 += intArrayOf(t0[(i - blocksWide) * 2], t0[(i - blocksWide) * 2 + 1])
            if (mbY > 0 && mbX + 1 < blocksWide) {
                preds0 += intArrayOf(t0[(i - blocksWide + 1) * 2], t0[(i - blocksWide + 1) * 2 + 1])
            }
            when (preds0.size) {
                4 -> {
                    predX = midPred(preds0[1][0], preds0[2][0], preds0[3][0])
                    predY = midPred(preds0[1][1], preds0[2][1], preds0[3][1])
                }
                3 -> {
                    predX = midPred(0, preds0[1][0], preds0[2][0])
                    predY = midPred(0, preds0[1][1], preds0[2][1])
                }
                2 -> {
                    predX = preds0[1][0]
                    predY = preds0[1][1]
                }
                else -> {
                    predX = 0
                    predY = 0
                }
            }
            preds0 += intArrayOf(t1[i * 2], t1[i * 2 + 1])
            preds1 += intArrayOf(t1[i * 2] + (t1[i * 2] - t2[i * 2]), t1[i * 2 + 1] + (t1[i * 2 + 1] - t2[i * 2 + 1]))
            if (mbX > 0) preds1 += intArrayOf(t1[(i - 1) * 2], t1[(i - 1) * 2 + 1])
            if (mbY > 0) preds1 += intArrayOf(t1[(i - blocksWide) * 2], t1[(i - blocksWide) * 2 + 1])
            if (mbX + 1 < blocksWide) preds1 += intArrayOf(t1[(i + 1) * 2], t1[(i + 1) * 2 + 1])
            if (mbY + 1 < blocksHigh) preds1 += intArrayOf(t1[(i + blocksWide) * 2], t1[(i + blocksWide) * 2 + 1])

            // motion_estimation.c ff_me_search_epzs.
            val xMin = maxOf(0, xMb - SEARCH)
            val yMin = maxOf(0, yMb - SEARCH)
            val xMaxS = minOf(xMb + SEARCH, xMax)
            val yMaxS = minOf(yMb + SEARCH, yMax)
            var costMin = Long.MAX_VALUE
            var mvX = xMb
            var mvY = yMb
            fun cost(x: Int, y: Int) {
                if (x < xMin || x > xMaxS || y < yMin || y > yMaxS) return
                val c = sbadOb(cur, ref, xMb, yMb, x, y, costMin)
                if (c < costMin) {
                    costMin = c
                    mvX = x
                    mvY = y
                }
            }
            cost(xMb + predX, yMb + predY)
            for (p in preds0) cost(xMb + p[0], yMb + p[1])
            for (p in preds1) cost(xMb + p[0], yMb + p[1])
            do {
                val x = mvX
                val y = mvY
                for (d in DIAMOND) cost(x + d[0], y + d[1])
            } while (x != mvX || y != mvY)

            t0[i * 2] = mvX - xMb
            t0[i * 2 + 1] = mvY - yMb
            vectors[i * 2] = mvX - xMb
            vectors[i * 2 + 1] = mvY - yMb
        }

            /**
         * vf_minterpolate.c get_sbad_ob: the bilateral difference over the block's doubled window. The sum stops
         * once it reaches [bound], the best cost so far, as the candidate can no longer win: the search
         * chooses exactly as FFmpeg's, sooner.
         */
        private fun sbadOb(
            cur: Deinterlacer.Plane,
            ref: Deinterlacer.Plane,
            xIn: Int,
            yIn: Int,
            xMv: Int,
            yMv: Int,
            bound: Long,
        ): Long {
            val half = BLOCK / 2
            val xLo = half
            val xHi = xMax - half
            val yLo = half
            val yHi = yMax - half
            val mvX1 = xMv - xIn
            val mvY1 = yMv - yIn
            val x = xIn.coerceIn(xLo, xHi)
            val y = yIn.coerceIn(yLo, yHi)
            val reachX = minOf(x - xLo, xHi - x)
            val reachY = minOf(y - yLo, yHi - y)
            val mvX = (xMv - x).coerceIn(-reachX, reachX)
            val mvY = (yMv - y).coerceIn(-reachY, reachY)
            val penalty = (abs(mvX1 - predX) + abs(mvY1 - predY)).toLong() * COST_PRED_SCALE
            val a = cur.pixels
            val b = ref.pixels
            val w = cur.width
            var sbad = 0L
            for (j in -half until BLOCK * 3 / 2) {
                var p = (y + mvY + j) * w + x + mvX - half
                var q = (y - mvY + j) * w + x - mvX - half
                var row = 0
                repeat(BLOCK * 2) {
                    row += abs(a[p++] - b[q++])
                }
                sbad += row
                if (sbad + penalty >= bound) return sbad + penalty
            }
            return sbad + penalty
        }
    }

    /** The overlapped-block weight at [index] of the 32 × 32 window (row by row), for the graphics pass. */
    fun obmcWeight(index: Int): Int = OBMC[index]

    /** [plane] at half the size, every other row and column: what the search runs on in the player. */
    fun halve(plane: Deinterlacer.Plane): Deinterlacer.Plane {
        val w = plane.width / 2
        val h = plane.height / 2
        return Deinterlacer.Plane(w, h, IntArray(w * h) { plane[(it % w) * 2, (it / w) * 2] })
    }

    /** libavcodec's mid_pred: the median of three. */
    private fun midPred(a: Int, b: Int, c: Int): Int {
        var m = b
        if (a > b) {
            if (c > b) m = if (c > a) a else c
        } else {
            if (b > c) m = if (c > a) c else a
        }
        return m
    }

    /** Whether two frames are a cut apart: their mean difference jumped past the threshold. */
    class SceneChange {
        private var previous = 0.0

        /** Compares [a] and [b], the next pair in order, and remembers the difference for the next call. */
        fun check(a: Deinterlacer.Plane, b: Deinterlacer.Plane): Boolean {
            var sad = 0L
            for (k in a.pixels.indices) sad += abs(a.pixels[k] - b.pixels[k])
            val mafd = sad.toDouble() * 100.0 / (a.height * a.width) / (1 shl 8)
            val diff = abs(mafd - previous)
            val result = minOf(mafd, diff).coerceIn(0.0, 100.0)
            previous = mafd
            return result >= SCENE_THRESHOLD
        }
    }

    /**
     * The picture at [alpha] (0..[ALPHA_MAX]) between [prev] and [next] along the half-vectors [vectors] of
     * a [blocksWide]-wide grid: vf_minterpolate.c bilateral_obmc and set_frame_data for every block.
     */
    fun compensate(
        prev: Deinterlacer.Plane,
        next: Deinterlacer.Plane,
        vectors: IntArray,
        blocksWide: Int,
        blocksHigh: Int,
        alpha: Int,
    ): Deinterlacer.Plane {
        val width = prev.width
        val height = prev.height
        val sum = LongArray(width * height)
        val weights = LongArray(width * height)
        for (mbY in 0 until blocksHigh) {
            for (mbX in 0 until blocksWide) {
                val b = mbX + mbY * blocksWide
                val mvX = vectors[b * 2] * 2
                val mvY = vectors[b * 2 + 1] * 2
                val startX = (mbX shl LOG2_BLOCK) - BLOCK / 2
                val startY = (mbY shl LOG2_BLOCK) - BLOCK / 2
                val endX = (startX + (2 shl LOG2_BLOCK)).coerceIn(0, width - 1)
                val endY = (startY + (2 shl LOG2_BLOCK)).coerceIn(0, height - 1)
                for (y in startY.coerceIn(0, height - 1) until endY) {
                    for (x in startX.coerceIn(0, width - 1) until endX) {
                        val w = OBMC[(x - startX) + ((y - startY) shl (LOG2_BLOCK + 1))]
                        if (w == 0) continue
                        val x1 = ((mvX * alpha) / ALPHA_MAX).coerceIn(-x, width - x - 1)
                        val y1 = ((mvY * alpha) / ALPHA_MAX).coerceIn(-y, height - y - 1)
                        val x2 = (-mvX * (ALPHA_MAX - alpha) / ALPHA_MAX).coerceIn(-x, width - x - 1)
                        val y2 = (-mvY * (ALPHA_MAX - alpha) / ALPHA_MAX).coerceIn(-y, height - y - 1)
                        val k = x + y * width
                        val w1 = w.toLong() * (ALPHA_MAX - alpha)
                        val w2 = w.toLong() * alpha
                        sum[k] += w1 * prev[x + x1, y + y1] + w2 * next[x + x2, y + y2]
                        weights[k] += w1 + w2
                    }
                }
            }
        }
        val out = IntArray(width * height) { k ->
            if (weights[k] == 0L) {
                // No block reaches the pixel: the plain mix of the two frames.
                val v = (ALPHA_MAX - alpha).toLong() * prev.pixels[k] + alpha.toLong() * next.pixels[k]
                ((v + ALPHA_MAX / 2) / ALPHA_MAX).toInt()
            } else {
                ((sum[k] + weights[k] / 2) / weights[k]).toInt()
            }
        }
        return Deinterlacer.Plane(width, height, out)
    }

    /** vf_minterpolate.c `obmc_linear32`: the 32 × 32 weights for 16-pixel blocks, a row over two lines. */
    private val OBMC = intArrayOf(
          0,   0,   0,   0,   4,   4,   4,   4,   4,   4,   4,   4,   8,   8,   8,   8,
          8,   8,   8,   8,   4,   4,   4,   4,   4,   4,   4,   4,   0,   0,   0,   0,
          0,   4,   4,   4,   8,   8,   8,  12,  12,  16,  16,  16,  20,  20,  20,  24,
         24,  20,  20,  20,  16,  16,  16,  12,  12,   8,   8,   8,   4,   4,   4,   0,
          0,   4,   8,   8,  12,  12,  16,  20,  20,  24,  28,  28,  32,  32,  36,  40,
         40,  36,  32,  32,  28,  28,  24,  20,  20,  16,  12,  12,   8,   8,   4,   0,
          0,   4,   8,  12,  16,  20,  24,  28,  28,  32,  36,  40,  44,  48,  52,  56,
         56,  52,  48,  44,  40,  36,  32,  28,  28,  24,  20,  16,  12,   8,   4,   0,
          4,   8,  12,  16,  20,  24,  28,  32,  40,  44,  48,  52,  56,  60,  64,  68,
         68,  64,  60,  56,  52,  48,  44,  40,  32,  28,  24,  20,  16,  12,   8,   4,
          4,   8,  12,  20,  24,  32,  36,  40,  48,  52,  56,  64,  68,  76,  80,  84,
         84,  80,  76,  68,  64,  56,  52,  48,  40,  36,  32,  24,  20,  12,   8,   4,
          4,   8,  16,  24,  28,  36,  44,  48,  56,  60,  68,  76,  80,  88,  96, 100,
        100,  96,  88,  80,  76,  68,  60,  56,  48,  44,  36,  28,  24,  16,   8,   4,
          4,  12,  20,  28,  32,  40,  48,  56,  64,  72,  80,  88,  92, 100, 108, 116,
        116, 108, 100,  92,  88,  80,  72,  64,  56,  48,  40,  32,  28,  20,  12,   4,
          4,  12,  20,  28,  40,  48,  56,  64,  72,  80,  88,  96, 108, 116, 124, 132,
        132, 124, 116, 108,  96,  88,  80,  72,  64,  56,  48,  40,  28,  20,  12,   4,
          4,  16,  24,  32,  44,  52,  60,  72,  80,  92, 100, 108, 120, 128, 136, 148,
        148, 136, 128, 120, 108, 100,  92,  80,  72,  60,  52,  44,  32,  24,  16,   4,
          4,  16,  28,  36,  48,  56,  68,  80,  88, 100, 112, 120, 132, 140, 152, 164,
        164, 152, 140, 132, 120, 112, 100,  88,  80,  68,  56,  48,  36,  28,  16,   4,
          4,  16,  28,  40,  52,  64,  76,  88,  96, 108, 120, 132, 144, 156, 168, 180,
        180, 168, 156, 144, 132, 120, 108,  96,  88,  76,  64,  52,  40,  28,  16,   4,
          8,  20,  32,  44,  56,  68,  80,  92, 108, 120, 132, 144, 156, 168, 180, 192,
        192, 180, 168, 156, 144, 132, 120, 108,  92,  80,  68,  56,  44,  32,  20,   8,
          8,  20,  32,  48,  60,  76,  88, 100, 116, 128, 140, 156, 168, 184, 196, 208,
        208, 196, 184, 168, 156, 140, 128, 116, 100,  88,  76,  60,  48,  32,  20,   8,
          8,  20,  36,  52,  64,  80,  96, 108, 124, 136, 152, 168, 180, 196, 212, 224,
        224, 212, 196, 180, 168, 152, 136, 124, 108,  96,  80,  64,  52,  36,  20,   8,
          8,  24,  40,  56,  68,  84, 100, 116, 132, 148, 164, 180, 192, 208, 224, 240,
        240, 224, 208, 192, 180, 164, 148, 132, 116, 100,  84,  68,  56,  40,  24,   8,
          8,  24,  40,  56,  68,  84, 100, 116, 132, 148, 164, 180, 192, 208, 224, 240,
        240, 224, 208, 192, 180, 164, 148, 132, 116, 100,  84,  68,  56,  40,  24,   8,
          8,  20,  36,  52,  64,  80,  96, 108, 124, 136, 152, 168, 180, 196, 212, 224,
        224, 212, 196, 180, 168, 152, 136, 124, 108,  96,  80,  64,  52,  36,  20,   8,
          8,  20,  32,  48,  60,  76,  88, 100, 116, 128, 140, 156, 168, 184, 196, 208,
        208, 196, 184, 168, 156, 140, 128, 116, 100,  88,  76,  60,  48,  32,  20,   8,
          8,  20,  32,  44,  56,  68,  80,  92, 108, 120, 132, 144, 156, 168, 180, 192,
        192, 180, 168, 156, 144, 132, 120, 108,  92,  80,  68,  56,  44,  32,  20,   8,
          4,  16,  28,  40,  52,  64,  76,  88,  96, 108, 120, 132, 144, 156, 168, 180,
        180, 168, 156, 144, 132, 120, 108,  96,  88,  76,  64,  52,  40,  28,  16,   4,
          4,  16,  28,  36,  48,  56,  68,  80,  88, 100, 112, 120, 132, 140, 152, 164,
        164, 152, 140, 132, 120, 112, 100,  88,  80,  68,  56,  48,  36,  28,  16,   4,
          4,  16,  24,  32,  44,  52,  60,  72,  80,  92, 100, 108, 120, 128, 136, 148,
        148, 136, 128, 120, 108, 100,  92,  80,  72,  60,  52,  44,  32,  24,  16,   4,
          4,  12,  20,  28,  40,  48,  56,  64,  72,  80,  88,  96, 108, 116, 124, 132,
        132, 124, 116, 108,  96,  88,  80,  72,  64,  56,  48,  40,  28,  20,  12,   4,
          4,  12,  20,  28,  32,  40,  48,  56,  64,  72,  80,  88,  92, 100, 108, 116,
        116, 108, 100,  92,  88,  80,  72,  64,  56,  48,  40,  32,  28,  20,  12,   4,
          4,   8,  16,  24,  28,  36,  44,  48,  56,  60,  68,  76,  80,  88,  96, 100,
        100,  96,  88,  80,  76,  68,  60,  56,  48,  44,  36,  28,  24,  16,   8,   4,
          4,   8,  12,  20,  24,  32,  36,  40,  48,  52,  56,  64,  68,  76,  80,  84,
         84,  80,  76,  68,  64,  56,  52,  48,  40,  36,  32,  24,  20,  12,   8,   4,
          4,   8,  12,  16,  20,  24,  28,  32,  40,  44,  48,  52,  56,  60,  64,  68,
         68,  64,  60,  56,  52,  48,  44,  40,  32,  28,  24,  20,  16,  12,   8,   4,
          0,   4,   8,  12,  16,  20,  24,  28,  28,  32,  36,  40,  44,  48,  52,  56,
         56,  52,  48,  44,  40,  36,  32,  28,  28,  24,  20,  16,  12,   8,   4,   0,
          0,   4,   8,   8,  12,  12,  16,  20,  20,  24,  28,  28,  32,  32,  36,  40,
         40,  36,  32,  32,  28,  28,  24,  20,  20,  16,  12,  12,   8,   8,   4,   0,
          0,   4,   4,   4,   8,   8,   8,  12,  12,  16,  16,  16,  20,  20,  20,  24,
         24,  20,  20,  20,  16,  16,  16,  12,  12,   8,   8,   8,   4,   4,   4,   0,
          0,   0,   0,   0,   4,   4,   4,   4,   4,   4,   4,   4,   8,   8,   8,   8,
          8,   8,   8,   8,   4,   4,   4,   4,   4,   4,   4,   4,   0,   0,   0,   0,
    )
}
