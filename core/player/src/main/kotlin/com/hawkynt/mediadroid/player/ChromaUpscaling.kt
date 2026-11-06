package com.hawkynt.mediadroid.player

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The colour of a 4:2:0 picture enlarged to its brightness's size — a quarter as many colour samples as pixels, so how
 * they are spread decides whether a red coat ends at its edge or bleeds a pixel or two past it. [Bilinear] blends the
 * nearest four (the YUV pass's own texture filtering); [spline36] weighs six by six with libplacebo's `spline36`
 * (mpv's `--cscale=spline36`), sharper; [krig] is Shiandow's KrigBilateral, which predicts each pixel's colour from
 * the nine colour samples around it, weighed by how alike their brightness is to the pixel's own — so a colour edge
 * follows the brightness edge it belongs to.
 *
 * The colour samples sit between the brightness pixels (centred, as the YUV pass reads them: MPEG-1 and JPEG's
 * siting). Converted from libplacebo 7.360's `spline36` (`src/filters.c`, LGPL-2.1-or-later) and KrigBilateral
 * (Shiandow, LGPL-3.0-or-later; see `THIRD_PARTY_NOTICES.md`) — the reference the GL passes
 * (glsl/video/chroma-*.frag) are written from, held to libplacebo's renderer running both by `ChromaUpscalingTest`.
 */
object ChromaUpscaling {

    /** A 4:2:0 picture: [y] is [width] × [height], [u] and [v] half that each way (rounded up), rows from the top. */
    class Planes(val width: Int, val height: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray) {
        val chromaWidth: Int get() = (width + 1) / 2
        val chromaHeight: Int get() = (height + 1) / 2

        init {
            require(y.size == width * height && u.size == chromaWidth * chromaHeight && v.size == u.size) {
                "not a $width × $height 4:2:0 picture"
            }
        }

        fun luma(x: Int, row: Int): Float = byte(y, x, row, width, height)
        fun cb(x: Int, row: Int): Float = byte(u, x, row, chromaWidth, chromaHeight)
        fun cr(x: Int, row: Int): Float = byte(v, x, row, chromaWidth, chromaHeight)

        // A sample, the edges clamped as a texture's are, on 0..1.
        private fun byte(plane: ByteArray, x: Int, row: Int, w: Int, h: Int): Float =
            (plane[row.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)].toInt() and 0xFF) / 255f
    }

    /** Cb and Cr at the brightness's size, each [Planes.width] × [Planes.height], 0..1. */
    class Chroma(val cb: FloatArray, val cr: FloatArray)

    /** Where luma pixel [i] of [size] falls among [chromaSize] samples, in sample units (0 at the first's centre). */
    private fun position(i: Int, size: Int, chromaSize: Int): Float = (i + 0.5f) * chromaSize / size - 0.5f

    /** The four nearest samples blended, as the YUV pass's texture filtering does. */
    fun bilinear(p: Planes): Chroma = separable(p, 1) { d -> max(0f, 1f - abs(d)) }

    /** libplacebo's spline36 over six by six samples, weights normalised to one. */
    fun spline36(p: Planes): Chroma = separable(p, 3, ::spline36Weight)

    /** libplacebo's `spline36` (`pl_filter_function_spline36`) at distance [d], radius 3. */
    fun spline36Weight(d: Float): Float {
        val x = abs(d)
        return when {
            x < 1f -> ((13f / 11f * x - 453f / 209f) * x - 3f / 209f) * x + 1f
            x < 2f -> ((-6f / 11f * (x - 1f) + 270f / 209f) * (x - 1f) - 156f / 209f) * (x - 1f)
            x < 3f -> ((1f / 11f * (x - 2f) - 45f / 209f) * (x - 2f) + 26f / 209f) * (x - 2f)
            else -> 0f
        }
    }

    // A separable filter of [radius] samples either side, the rows first, each tap's weight [weight] of its distance.
    private fun separable(p: Planes, radius: Int, weight: (Float) -> Float): Chroma {
        val w = p.width
        val h = p.height
        val cw = p.chromaWidth
        val ch = p.chromaHeight
        fun pass(sample: (Int, Int) -> Float): FloatArray {
            // Down the columns first: chroma columns at the full height.
            val columns = FloatArray(cw * h)
            for (row in 0 until h) {
                val at = position(row, h, ch)
                val base = floor(at).toInt()
                for (cx in 0 until cw) {
                    var sum = 0f
                    var total = 0f
                    for (k in base - radius + 1..base + radius) {
                        val wt = weight(k - at)
                        sum += wt * sample(cx, k)
                        total += wt
                    }
                    columns[row * cw + cx] = sum / total
                }
            }
            val out = FloatArray(w * h)
            for (x in 0 until w) {
                val at = position(x, w, cw)
                val base = floor(at).toInt()
                for (row in 0 until h) {
                    var sum = 0f
                    var total = 0f
                    for (k in base - radius + 1..base + radius) {
                        val wt = weight(k - at)
                        sum += wt * columns[row * cw + k.coerceIn(0, cw - 1)]
                        total += wt
                    }
                    out[row * w + x] = sum / total
                }
            }
            return out
        }
        return Chroma(pass(p::cb), pass(p::cr))
    }

    /** KrigBilateral's window: Blackman-like, over -1..1 (its `Kernel`). */
    private fun window(x: Float): Float =
        0.42659f - 0.49656f * cos(PI.toFloat() * (x + 1f)) + 0.076849f * cos(2f * PI.toFloat() * (x + 1f))

    /** KrigBilateral's noise floor: one 8-bit step squared, times 256 (`sigma_nsq`). */
    private const val NOISE = 256f / (255f * 255f)

    /** How far its covariance reaches, in chroma samples. */
    private const val RADIUS = 1.5f

    /** The eight samples around the centre, in KrigBilateral's order, then the centre itself. */
    private val AROUND = arrayOf(
        -1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1, 0 to 0,
    )

    /**
     * The brightness brought down to the colour's size, as its mean and variance under each colour sample: two passes,
     * down the columns then along the rows, of a window over the brightness pixels within one colour sample either way.
     * The second adds the first's variance, read between its two nearest columns as the texture's filtering does.
     */
    fun lowResolutionLuma(p: Planes): Pair<FloatArray, FloatArray> {
        val w = p.width
        val h = p.height
        val cw = p.chromaWidth
        val ch = p.chromaHeight
        // Pass 1: luma width, chroma height.
        val mean1 = FloatArray(w * ch)
        val var1 = FloatArray(w * ch)
        for (cy in 0 until ch) {
            val pos = (cy + 0.5f) / ch
            val low = ceil((pos - 1f / ch) * h - 0.5f).toInt()
            val high = floor((pos + 1f / ch) * h - 0.5f).toInt()
            for (x in 0 until w) {
                var total = 0f
                var m = 0f
                var sq = 0f
                for (k in low..high) {
                    val wt = window(((k + 0.5f) / h - pos) * ch)
                    val l = p.luma(x, k)
                    m += wt * l
                    sq += wt * l * l
                    total += wt
                }
                m /= total
                sq /= total
                mean1[cy * w + x] = m
                var1[cy * w + x] = abs(sq - m * m)
            }
        }
        // Pass 2: chroma width.
        val mean = FloatArray(cw * ch)
        val variance = FloatArray(cw * ch)
        for (cx in 0 until cw) {
            val pos = (cx + 0.5f) / cw
            val low = ceil((pos - 1f / cw) * w - 0.5f).toInt()
            val high = floor((pos + 1f / cw) * w - 0.5f).toInt()
            // Where the chroma sample's centre falls among pass 1's columns, read linearly.
            val at = pos * w - 0.5f
            val left = floor(at).toInt()
            val f = at - left
            for (cy in 0 until ch) {
                fun var1At(x: Int) = var1[cy * w + x.coerceIn(0, w - 1)]
                var total = 0f
                var m = 0f
                var sq = 0f
                for (k in low..high) {
                    val wt = window(((k + 0.5f) / w - pos) * cw)
                    val l = mean1[cy * w + k.coerceIn(0, w - 1)]
                    m += wt * l
                    sq += wt * l * l
                    total += wt
                }
                m /= total
                sq /= total
                mean[cy * cw + cx] = m
                variance[cy * cw + cx] = abs(sq - m * m) + var1At(left) * (1f - f) + var1At(left + 1) * f
            }
        }
        return mean to variance
    }

    /** Shiandow's KrigBilateral: each pixel's colour kriged from the nine samples around, guided by its brightness. */
    fun krig(p: Planes): Chroma {
        val w = p.width
        val h = p.height
        val cw = p.chromaWidth
        val ch = p.chromaHeight
        val (lowMean, lowVar) = lowResolutionLuma(p)
        val cb = FloatArray(w * h)
        val cr = FloatArray(w * h)
        val n = AROUND.size - 1
        val mean = FloatArray(n + 1)
        val variance = FloatArray(n + 1)
        val u = FloatArray(n + 1)
        val v = FloatArray(n + 1)
        val coordX = FloatArray(n + 1) { AROUND[it].first.toFloat() }
        val coordY = FloatArray(n + 1) { AROUND[it].second.toFloat() }
        val m = Array(n) { FloatArray(n) }
        val b = FloatArray(n)
        for (row in 0 until h) {
            val py = position(row, h, ch)
            val cy = py.roundToInt()
            val offY = py - cy
            for (x in 0 until w) {
                val px = position(x, w, cw)
                val cx = px.roundToInt()
                val offX = px - cx
                var tMean = 0f
                var tSquare = 0f
                var tVar = 0f
                var tWeight = 0f
                for (i in 0..n) {
                    val sx = (cx + AROUND[i].first).coerceIn(0, cw - 1)
                    val sy = (cy + AROUND[i].second).coerceIn(0, ch - 1)
                    mean[i] = lowMean[sy * cw + sx]
                    variance[i] = lowVar[sy * cw + sx]
                    u[i] = p.cb(sx, sy)
                    v[i] = p.cr(sx, sy)
                    val wt = (1.5f - abs(coordX[i])).coerceIn(0f, 1f) * (1.5f - abs(coordY[i])).coerceIn(0f, 1f)
                    tMean += wt * mean[i]
                    tSquare += wt * mean[i] * mean[i]
                    tVar += wt * variance[i]
                    tWeight += wt
                }
                tMean /= tWeight
                tSquare /= tWeight
                tVar /= tWeight
                val localVar = abs(tSquare - tMean * tMean) + NOISE
                val totalVar = localVar + tVar
                val luma = p.luma(x, row)
                fun cov(i: Int, j: Int): Float {
                    val dx = coordX[i] - coordX[j]
                    val dy = coordY[i] - coordY[j]
                    val dm = mean[i] - mean[j]
                    return (1f / sqrt(1f + (variance[i] + variance[j]) / totalVar)) *
                        exp(-0.5f * (dm * dm / (localVar + variance[i] + variance[j]) +
                            (dx * dx + dy * dy) / (RADIUS * RADIUS)))
                }
                fun toPixel(i: Int): Float {
                    val dx = coordX[i] - offX
                    val dy = coordY[i] - offY
                    val dm = mean[i] - luma
                    return (1f / sqrt(1f + variance[i] / totalVar)) *
                        exp(-0.5f * (dm * dm / (localVar + variance[i]) + (dx * dx + dy * dy) / (RADIUS * RADIUS)))
                }
                // The kriging system relative to the centre sample, solved by elimination without pivoting.
                val centre = cov(n, n)
                for (i in 0 until n) {
                    b[i] = toPixel(i) - toPixel(n) - cov(i, n) + centre
                    for (j in i until n) m[i][j] = cov(i, j) - cov(j, n) - cov(i, n) + centre
                }
                for (k in 0 until n) {
                    for (i in k + 1 until n) {
                        val f = m[k][i] / m[k][k]
                        b[i] -= b[k] * f
                        for (j in i until n) m[i][j] -= m[k][j] * f
                    }
                }
                var outU = u[n]
                var outV = v[n]
                for (i in n - 1 downTo 0) {
                    for (j in i + 1 until n) b[i] -= m[i][j] * b[j]
                    b[i] /= m[i][i]
                    outU += b[i] * (u[i] - u[n])
                    outV += b[i] * (v[i] - v[n])
                }
                cb[row * w + x] = outU
                cr[row * w + x] = outV
            }
        }
        return Chroma(cb, cr)
    }

    /** [p]'s colour enlarged by [filter]. */
    fun upscale(p: Planes, filter: PictureAdjustment.Chroma): Chroma = when (filter) {
        PictureAdjustment.Chroma.BILINEAR -> bilinear(p)
        PictureAdjustment.Chroma.SPLINE36 -> spline36(p)
        PictureAdjustment.Chroma.KRIG -> krig(p)
    }
}
