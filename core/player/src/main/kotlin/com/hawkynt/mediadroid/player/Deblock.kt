package com.hawkynt.mediadroid.player

import kotlin.math.abs

/**
 * Deblocking: the edges of the 8 × 8 blocks a low-bitrate MPEG picture was coded in smoothed away where the step
 * across them is small — a block edge, not a real one — and the pixels either side lie flat. [Filter.WEAK] moves two
 * pixels on each side, [Filter.STRONG] three; the four thresholds, fractions of full scale, decide what counts as a
 * block edge (the step across it) and as flat (the steps beside it).
 *
 * Converted from FFmpeg's `libavfilter/vf_deblock.c` (Paul B Mahol, after "A Simple and Efficient Deblocking Algorithm
 * for Low Bit-Rate Video Coding"; LGPL-2.1-or-later, see `THIRD_PARTY_NOTICES.md`) with one difference: FFmpeg's
 * filters along a vertical edge move to the next row only after a row they filtered, so the first row they leave alone
 * stops them for the rest of the block; here every row is judged, as along the horizontal edges. FFmpeg runs it in
 * place, edge after edge; here it runs as three passes over the whole picture, which the GL pass
 * (glsl/video/deblock-es3.frag) does too and which come to the same, byte for byte (`DeblockTest`, against FFmpeg's own
 * filters with that one line moved): each band of block rows has its top edge filtered between the edges across its
 * rows — before them for the rows the top edge reaches below it, after them for those it reaches above.
 */
object Deblock {

    /** FFmpeg's two filters: two pixels moved each side of an edge, or three. */
    enum class Filter { WEAK, STRONG }

    /**
     * FFmpeg's settings: the [filter], the [block] size, and the thresholds as fractions of full scale — [alpha] for
     * the step across the edge, [beta] and [gamma] for the steps beside it, [delta] (the strong filter's) for the next.
     */
    data class Params(
        val filter: Filter,
        val alpha: Float,
        val beta: Float,
        val gamma: Float,
        val delta: Float,
        val block: Int = 8,
    )

    /** FFmpeg's defaults: the strong filter, alpha 0.098, the others 0.05, blocks of 8. */
    val Defaults = Params(Filter.STRONG, 0.098f, 0.05f, 0.05f, 0.05f)

    /**
     * The settings for the slider's [strength], 0 to 1, or null at 0 (off): the weak filter below the middle, the
     * strong one from it, the thresholds FFmpeg's own times twice the strength — so its defaults in the middle and
     * twice as far reaching at the top.
     */
    fun params(strength: Float): Params? {
        if (strength <= 0f) return null
        val s = strength.coerceAtMost(1f)
        val scale = 2f * s
        return Params(
            if (s < 0.5f) Filter.WEAK else Filter.STRONG,
            Defaults.alpha * scale,
            Defaults.beta * scale,
            Defaults.gamma * scale,
            Defaults.delta * scale,
        )
    }

    /**
     * Where the block grid starts in a picture cut [left] and [top] pixels into the decoded one (its black bars or a 3D
     * eye cropped): the first block edge across and down, 0 to [block] - 1 — the deblocking's and deringing's origin.
     */
    fun gridOrigin(left: Int, top: Int, block: Int = 8): Pair<Int, Int> =
        Math.floorMod(-left, block) to Math.floorMod(-top, block)

    /** A threshold as FFmpeg keeps it: the fraction times 255, in floats, cut to an integer. */
    fun threshold(fraction: Float): Int = (fraction * 255f).toInt()

    /**
     * One channel ([plane], [width] × [height], rows from the top) deblocked as FFmpeg does it, its block grid starting
     * [originX] and [originY] pixels in (where the picture's own blocks start, a crop having cut into them).
     */
    fun apply(
        plane: ByteArray,
        width: Int,
        height: Int,
        params: Params,
        originX: Int = 0,
        originY: Int = 0,
    ): ByteArray {
        require(plane.size == width * height) { "not $width × $height" }
        val out = IntArray(plane.size) { plane[it].toInt() and 0xFF }
        val block = params.block
        val reach = if (params.filter == Filter.STRONG) 3 else 2
        val ath = threshold(params.alpha)
        val bth = threshold(params.beta)
        val gth = threshold(params.gamma)
        val dth = threshold(params.delta)
        // The edges across the rows (block columns) and down the columns (block rows), as FFmpeg finds them: every
        // block boundary past the first block, with room for the filter either side; the last band's top edge left.
        val across = generateSequence(originX + block) { it + block }.takeWhile { it < width }
            .filter { it - reach >= 0 && it + reach - 1 < width }.toList()
        val down = generateSequence(originY + block) { it + block }.takeWhile { it < height - block }
            .filter { it - reach >= 0 }.toList()

        // Filters four (weak) or six (strong) values at [at], [step] apart, the edge between the middle two.
        fun edge(at: Int, step: Int) {
            val d = out[at] - out[at - step]
            if (abs(d) >= ath || abs(out[at - step] - out[at - 2 * step]) >= bth) return
            if (params.filter == Filter.WEAK) {
                if (abs(out[at] - out[at + step]) >= gth) return
                out[at - 2 * step] = (out[at - 2 * step] + d / 8).coerceIn(0, 255)
                out[at - step] = (out[at - step] + d / 2).coerceIn(0, 255)
                out[at] = (out[at] - d / 2).coerceIn(0, 255)
                out[at + step] = (out[at + step] - d / 8).coerceIn(0, 255)
            } else {
                if (abs(out[at + step] - out[at + 2 * step]) >= gth || abs(out[at] - out[at + step]) >= dth) return
                out[at - 3 * step] = (out[at - 3 * step] + d / 8).coerceIn(0, 255)
                out[at - 2 * step] = (out[at - 2 * step] + d / 4).coerceIn(0, 255)
                out[at - step] = (out[at - step] + d / 2).coerceIn(0, 255)
                out[at] = (out[at] - d / 2).coerceIn(0, 255)
                out[at + step] = (out[at + step] - d / 4).coerceIn(0, 255)
                out[at + 2 * step] = (out[at + 2 * step] - d / 8).coerceIn(0, 255)
            }
        }

        // A row's position in its band of block rows: the first three are reached by the band's top edge first.
        fun early(row: Int) = Math.floorMod(row - originY, block) < reach
        fun acrossRows(first: Boolean) {
            for (row in 0 until height) if (early(row) == first) for (x in across) edge(row * width + x, 1)
        }
        acrossRows(first = false)
        for (y in down) for (x in 0 until width) edge(y * width + x, width)
        acrossRows(first = true)
        return ByteArray(out.size) { out[it].toByte() }
    }
}
