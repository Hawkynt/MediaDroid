package com.hawkynt.mediadroid.imaging

import kotlin.math.sqrt

/**
 * xBRZ, Zenju's "scale by rules" for pixel art and cartoons, at 2× to 6×: every picture pixel becomes a block of
 * n × n, and where two pixels meet along an edge the block takes some of its neighbour's colour, so diagonals and
 * curves come out smooth instead of stair-stepped while flat areas and hard edges stay as they were.
 *
 * Written from a specification of the algorithm worked out against the reference implementation (GPL-3.0, so used
 * as an oracle only, see XbrzTest); the tolerances and the blend patterns are its own numbers.
 *
 * It runs in three steps, which the graphics chip's version in the app follows as passes:
 *
 * 1. [corners]: at every point where four pixels meet — `E F` above `H I` — the colour differences along the two
 *    diagonals, weighed over the 4 × 4 pixels around, decide whether one of them is an edge; if so, the corner of
 *    each pixel on the other side of it is marked to take a neighbour's colour, with a [Strength].
 * 2. [decisions]: for each of a pixel's four corners, turned so the corner being looked at is the bottom right one,
 *    which [Pattern] to draw there, if any, and with which neighbour's colour.
 * 3. [scale]: the block filled with the pixel's own colour, then the four corners' patterns laid over it in turn.
 */
object Xbrz {

    /** Colours closer than this count as the same. */
    const val EQUAL_COLOR_TOLERANCE = 30.0

    /** How much more the difference across the corner itself counts than the ones beside it. */
    const val CENTER_DIRECTION_BIAS = 4.0

    /** An edge this many times clearer than the other diagonal is [Strength.DOMINANT]. */
    const val DOMINANT_DIRECTION_THRESHOLD = 3.6

    /** A line this many times closer along one side than the other runs shallow or steep rather than diagonal. */
    const val STEEP_DIRECTION_THRESHOLD = 2.4

    /** How strongly a pixel's corner is to take a neighbour's colour. */
    enum class Strength { NONE, NORMAL, DOMINANT }

    /**
     * What is drawn into a block's bottom right corner: a line of the neighbour's colour running shallow (more
     * across than down), steep, both, at 45°, or only the rounded-off corner itself.
     */
    enum class Pattern { SHALLOW, STEEP, STEEP_AND_SHALLOW, DIAGONAL, CORNER }

    /** One cell of a pattern: row and column in the block, and the share [m] / [n] of the neighbour's colour. */
    private class Cell(val row: Int, val col: Int, val m: Int, val n: Int)

    private fun cells(vararg values: Int) = values.toList().chunked(4).map { (r, c, m, n) -> Cell(r, c, m, n) }

    // The patterns per factor, bottom right corner of an n × n block, rows downwards; 1 / 1 is the neighbour's
    // colour outright. The reference's numbers, cell for cell.
    private val patterns: Map<Int, Map<Pattern, List<Cell>>> = mapOf(
        2 to mapOf(
            Pattern.SHALLOW to cells(1, 0, 1, 4, 1, 1, 3, 4),
            Pattern.STEEP to cells(0, 1, 1, 4, 1, 1, 3, 4),
            Pattern.STEEP_AND_SHALLOW to cells(1, 0, 1, 4, 0, 1, 1, 4, 1, 1, 5, 6),
            Pattern.DIAGONAL to cells(1, 1, 1, 2),
            Pattern.CORNER to cells(1, 1, 21, 100),
        ),
        3 to mapOf(
            Pattern.SHALLOW to cells(2, 0, 1, 4, 1, 2, 1, 4, 2, 1, 3, 4, 2, 2, 1, 1),
            Pattern.STEEP to cells(0, 2, 1, 4, 2, 1, 1, 4, 1, 2, 3, 4, 2, 2, 1, 1),
            Pattern.STEEP_AND_SHALLOW to cells(2, 0, 1, 4, 0, 2, 1, 4, 2, 1, 3, 4, 1, 2, 3, 4, 2, 2, 1, 1),
            Pattern.DIAGONAL to cells(1, 2, 1, 8, 2, 1, 1, 8, 2, 2, 7, 8),
            Pattern.CORNER to cells(2, 2, 45, 100),
        ),
        4 to mapOf(
            Pattern.SHALLOW to cells(3, 0, 1, 4, 2, 2, 1, 4, 3, 1, 3, 4, 2, 3, 3, 4, 3, 2, 1, 1, 3, 3, 1, 1),
            Pattern.STEEP to cells(0, 3, 1, 4, 2, 2, 1, 4, 1, 3, 3, 4, 3, 2, 3, 4, 2, 3, 1, 1, 3, 3, 1, 1),
            Pattern.STEEP_AND_SHALLOW to cells(
                3, 1, 3, 4, 1, 3, 3, 4, 3, 0, 1, 4, 0, 3, 1, 4, 2, 2, 1, 3, 3, 3, 1, 1, 3, 2, 1, 1, 2, 3, 1, 1,
            ),
            Pattern.DIAGONAL to cells(3, 2, 1, 2, 2, 3, 1, 2, 3, 3, 1, 1),
            Pattern.CORNER to cells(3, 3, 68, 100, 3, 2, 9, 100, 2, 3, 9, 100),
        ),
        5 to mapOf(
            Pattern.SHALLOW to cells(
                4, 0, 1, 4, 3, 2, 1, 4, 2, 4, 1, 4, 4, 1, 3, 4, 3, 3, 3, 4,
                4, 2, 1, 1, 4, 3, 1, 1, 4, 4, 1, 1, 3, 4, 1, 1,
            ),
            Pattern.STEEP to cells(
                0, 4, 1, 4, 2, 3, 1, 4, 4, 2, 1, 4, 1, 4, 3, 4, 3, 3, 3, 4,
                2, 4, 1, 1, 3, 4, 1, 1, 4, 4, 1, 1, 4, 3, 1, 1,
            ),
            Pattern.STEEP_AND_SHALLOW to cells(
                0, 4, 1, 4, 2, 3, 1, 4, 1, 4, 3, 4, 4, 0, 1, 4, 3, 2, 1, 4, 4, 1, 3, 4, 3, 3, 2, 3,
                2, 4, 1, 1, 3, 4, 1, 1, 4, 4, 1, 1, 4, 2, 1, 1, 4, 3, 1, 1,
            ),
            Pattern.DIAGONAL to cells(4, 2, 1, 8, 3, 3, 1, 8, 2, 4, 1, 8, 4, 3, 7, 8, 3, 4, 7, 8, 4, 4, 1, 1),
            Pattern.CORNER to cells(4, 4, 86, 100, 4, 3, 23, 100, 3, 4, 23, 100),
        ),
        6 to mapOf(
            Pattern.SHALLOW to cells(
                5, 0, 1, 4, 4, 2, 1, 4, 3, 4, 1, 4, 5, 1, 3, 4, 4, 3, 3, 4, 3, 5, 3, 4,
                5, 2, 1, 1, 5, 3, 1, 1, 5, 4, 1, 1, 5, 5, 1, 1, 4, 4, 1, 1, 4, 5, 1, 1,
            ),
            Pattern.STEEP to cells(
                0, 5, 1, 4, 2, 4, 1, 4, 4, 3, 1, 4, 1, 5, 3, 4, 3, 4, 3, 4, 5, 3, 3, 4,
                2, 5, 1, 1, 3, 5, 1, 1, 4, 5, 1, 1, 5, 5, 1, 1, 4, 4, 1, 1, 5, 4, 1, 1,
            ),
            Pattern.STEEP_AND_SHALLOW to cells(
                0, 5, 1, 4, 2, 4, 1, 4, 1, 5, 3, 4, 3, 4, 3, 4, 5, 0, 1, 4, 4, 2, 1, 4, 5, 1, 3, 4, 4, 3, 3, 4,
                2, 5, 1, 1, 3, 5, 1, 1, 4, 5, 1, 1, 5, 5, 1, 1, 4, 4, 1, 1, 5, 4, 1, 1, 5, 2, 1, 1, 5, 3, 1, 1,
            ),
            Pattern.DIAGONAL to cells(
                5, 3, 1, 2, 4, 4, 1, 2, 3, 5, 1, 2, 4, 5, 1, 1, 5, 5, 1, 1, 5, 4, 1, 1,
            ),
            Pattern.CORNER to cells(5, 5, 97, 100, 4, 5, 42, 100, 5, 4, 42, 100, 5, 3, 6, 100, 3, 5, 6, 100),
        ),
    )

    /** The factors xBRZ scales by. */
    val FACTORS = 2..6

    /**
     * A pattern as the share of the neighbour's colour in each cell of an n × n block, rows downwards — 0 where it
     * leaves the cell alone, 1 where it fills it. The graphics chip's version reads its patterns from this.
     */
    fun alphas(factor: Int, pattern: Pattern): DoubleArray {
        require(factor in FACTORS) { "xBRZ scales by 2 to 6, not $factor" }
        val shares = DoubleArray(factor * factor)
        for (cell in patterns.getValue(factor).getValue(pattern)) {
            shares[cell.row * factor + cell.col] = cell.m.toDouble() / cell.n
        }
        return shares
    }

    /**
     * A pattern's shares as whole fractions — m and n for each cell of the n × n block, rows downwards, 0 / 1 where
     * it leaves the cell alone — so the graphics chip's version can mix by the same numbers.
     */
    fun weights(factor: Int, pattern: Pattern): IntArray {
        require(factor in FACTORS) { "xBRZ scales by 2 to 6, not $factor" }
        val weights = IntArray(factor * factor * 2) { if (it % 2 == 0) 0 else 1 }
        for (cell in patterns.getValue(factor).getValue(pattern)) {
            weights[(cell.row * factor + cell.col) * 2] = cell.m
            weights[(cell.row * factor + cell.col) * 2 + 1] = cell.n
        }
        return weights
    }

    private fun red(p: Int) = p shr 16 and 0xFF
    private fun green(p: Int) = p shr 8 and 0xFF
    private fun blue(p: Int) = p and 0xFF

    private const val K_R = 0.2627
    private const val K_B = 0.0593
    private const val K_G = 1 - K_B - K_R
    private const val SCALE_B = 0.5 / (1 - K_B)
    private const val SCALE_R = 0.5 / (1 - K_R)

    /**
     * How far apart two colours are: the length of their difference in Y'CbCr after BT.2020, the differences first
     * brought to even steps of two and the result kept to a float, as the reference's lookup table holds it.
     */
    fun distance(p: Int, q: Int): Double {
        val r = (red(p) - red(q)) / 2 * 2
        val g = (green(p) - green(q)) / 2 * 2
        val b = (blue(p) - blue(q)) / 2 * 2
        val y = K_R * r + K_G * g + K_B * b
        val cb = SCALE_B * (b - y)
        val cr = SCALE_R * (r - y)
        return sqrt(y * y + cb * cb + cr * cr).toFloat().toDouble()
    }

    private fun same(p: Int, q: Int) = distance(p, q) < EQUAL_COLOR_TOLERANCE

    /** Corner indices, clockwise from the top left. */
    const val TOP_LEFT = 0
    const val TOP_RIGHT = 1
    const val BOTTOM_RIGHT = 2
    const val BOTTOM_LEFT = 3

    /** A picture read with its edge pixels repeated outwards. */
    private class Picture(val pixels: IntArray, val width: Int, val height: Int) {
        operator fun get(x: Int, y: Int) = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
    }

    /**
     * Step 1: the four corners' [Strength] of every pixel, four entries a pixel in [TOP_LEFT] … [BOTTOM_LEFT] order.
     */
    fun corners(pixels: IntArray, width: Int, height: Int): Array<Strength> {
        val pic = Picture(pixels, width, height)
        val result = Array(width * height * 4) { Strength.NONE }
        fun mark(x: Int, y: Int, corner: Int, strength: Strength) {
            if (x in 0 until width && y in 0 until height) result[(y * width + x) * 4 + corner] = strength
        }
        // The point below and right of pixel (x, y), for every point that touches the picture.
        for (y in -1 until height) for (x in -1 until width) {
            val e = pic[x, y]
            val f = pic[x + 1, y]
            val h = pic[x, y + 1]
            val i = pic[x + 1, y + 1]
            if ((e == f && h == i) || (e == h && f == i)) continue
            fun d(ax: Int, ay: Int, bx: Int, by: Int) = distance(pic[x + ax, y + ay], pic[x + bx, y + by])
            // Across H–F (the anti-diagonal) and across E–I (the diagonal), each with the pairs beside it.
            val hf = d(-1, 1, 0, 0) + d(0, 0, 1, -1) + d(0, 2, 1, 1) + d(1, 1, 2, 0) +
                CENTER_DIRECTION_BIAS * d(0, 1, 1, 0)
            val ei = d(-1, 0, 0, 1) + d(0, 1, 1, 2) + d(0, -1, 1, 0) + d(1, 0, 2, 1) +
                CENTER_DIRECTION_BIAS * d(0, 0, 1, 1)
            if (hf < ei) {
                val strength = if (DOMINANT_DIRECTION_THRESHOLD * hf < ei) Strength.DOMINANT else Strength.NORMAL
                if (e != f && e != h) mark(x, y, BOTTOM_RIGHT, strength)
                if (i != h && i != f) mark(x + 1, y + 1, TOP_LEFT, strength)
            } else if (ei < hf) {
                val strength = if (DOMINANT_DIRECTION_THRESHOLD * ei < hf) Strength.DOMINANT else Strength.NORMAL
                if (h != e && h != i) mark(x, y + 1, TOP_RIGHT, strength)
                if (f != e && f != i) mark(x + 1, y, BOTTOM_LEFT, strength)
            }
        }
        return result
    }

    /** What one corner of a pixel draws: its [pattern] in the neighbour's colour at offset [dx], [dy]. */
    data class Decision(val pattern: Pattern, val dx: Int, val dy: Int)

    /**
     * An offset turned [turns] quarter turns: the neighbour that, seen with the picture turned clockwise that
     * often, sits at ([x], [y]) — y downwards.
     */
    fun turn(x: Int, y: Int, turns: Int): Pair<Int, Int> {
        var tx = x
        var ty = y
        repeat(turns) { val old = tx; tx = ty; ty = -old }
        return tx to ty
    }

    /** The corner of a pixel towards the diagonal neighbour at ([dx], [dy]). */
    private fun cornerTowards(dx: Int, dy: Int) = when {
        dx < 0 && dy < 0 -> TOP_LEFT
        dy < 0 -> TOP_RIGHT
        dx > 0 -> BOTTOM_RIGHT
        else -> BOTTOM_LEFT
    }

    /** The corner that, with the picture turned [turns] quarter turns clockwise, is the one towards ([dx], [dy]). */
    fun cornerAt(turns: Int, dx: Int = 1, dy: Int = 1) = turn(dx, dy, turns).let { (x, y) -> cornerTowards(x, y) }

    /**
     * Step 2: for each pixel and each of its four corners in turn — bottom right, top right, top left, bottom left —
     * what it draws there, or null; four entries a pixel.
     */
    fun decisions(pixels: IntArray, width: Int, height: Int, corners: Array<Strength>): Array<Decision?> {
        val pic = Picture(pixels, width, height)
        val result = arrayOfNulls<Decision>(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) for (t in 0..3) {
            val base = (y * width + x) * 4
            val strength = corners[base + cornerAt(t)]
            if (strength == Strength.NONE) continue
            // The 3 × 3 around the pixel, turned: a b c / d e f / g h i.
            fun k(dx: Int, dy: Int) = turn(dx, dy, t).let { (tx, ty) -> pic[x + tx, y + ty] }
            val b = k(0, -1)
            val c = k(1, -1)
            val d = k(-1, 0)
            val e = k(0, 0)
            val f = k(1, 0)
            val g = k(-1, 1)
            val h = k(0, 1)
            val i = k(1, 1)
            val topRight = corners[base + cornerAt(t, 1, -1)]
            val bottomLeft = corners[base + cornerAt(t, -1, 1)]
            val line = strength == Strength.DOMINANT || !(
                (topRight != Strength.NONE && !same(e, g)) ||
                    (bottomLeft != Strength.NONE && !same(e, c)) ||
                    (!same(e, i) && same(g, h) && same(h, i) && same(i, f) && same(f, c))
                )
            val towardsF = distance(e, f) <= distance(e, h)
            val pattern = if (line) {
                val fg = distance(f, g)
                val hc = distance(h, c)
                val shallow = STEEP_DIRECTION_THRESHOLD * fg <= hc && e != g && d != g
                val steep = STEEP_DIRECTION_THRESHOLD * hc <= fg && e != c && b != c
                when {
                    shallow && steep -> Pattern.STEEP_AND_SHALLOW
                    shallow -> Pattern.SHALLOW
                    steep -> Pattern.STEEP
                    else -> Pattern.DIAGONAL
                }
            } else {
                Pattern.CORNER
            }
            val (dx, dy) = if (towardsF) turn(1, 0, t) else turn(0, 1, t)
            result[base + t] = Decision(pattern, dx, dy)
        }
        return result
    }

    /** [front] over [back] by [m] / [n], each channel rounded half up. */
    private fun mix(front: Int, back: Int, m: Int, n: Int): Int {
        if (m == n) return front
        fun channel(shift: Int) = ((front shr shift and 0xFF) * m + (back shr shift and 0xFF) * (n - m) + n / 2) / n
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /**
     * The picture — RGB in the low 24 bits of each pixel, rows downwards — scaled [factor] times by xBRZ.
     */
    fun scale(pixels: IntArray, width: Int, height: Int, factor: Int): IntArray {
        require(factor in FACTORS) { "xBRZ scales by 2 to 6, not $factor" }
        val pic = Picture(pixels, width, height)
        val decisions = decisions(pixels, width, height, corners(pixels, width, height))
        val outWidth = width * factor
        val out = IntArray(outWidth * height * factor)
        for (y in 0 until height) for (x in 0 until width) {
            val block = IntArray(factor * factor) { pic[x, y] }
            for (t in 0..3) {
                val decision = decisions[(y * width + x) * 4 + t] ?: continue
                val colour = pic[x + decision.dx, y + decision.dy]
                for (cell in patterns.getValue(factor).getValue(decision.pattern)) {
                    val (col, row) = cellTurned(cell.col, cell.row, t, factor)
                    block[row * factor + col] = mix(colour, block[row * factor + col], cell.m, cell.n)
                }
            }
            for (row in 0 until factor) {
                block.copyInto(out, (y * factor + row) * outWidth + x * factor, row * factor, (row + 1) * factor)
            }
        }
        return out
    }

    /** Where a cell ([col], [row]) of the turned block lies in the block itself. */
    fun cellTurned(col: Int, row: Int, turns: Int, factor: Int): Pair<Int, Int> {
        var c = col
        var r = row
        repeat(turns) { val old = c; c = r; r = factor - 1 - old }
        return c to r
    }
}
