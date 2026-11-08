package com.hawkynt.mediadroyd.player

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Colour for black-and-white video by DDColor (piddnad/DDColor, Apache-2.0; see NeuralModels.DDCOLOR): the network
 * sees the picture's lightness at 256 × 256 and gives the a and b of Lab there; the frame keeps its own full-resolution
 * lightness, only that chroma, enlarged smoothly, is laid under it. Colour changes far slower than detail, so the
 * network runs every few frames — as often as the device allows — and its results are smoothed over time ([Smoother]),
 * which holds back the flicker per-frame colourisers show, and started afresh at a cut ([cut]). The pre- and
 * post-processing follow DDColor's own inference (and PhotoManager's, which uses it): the lightness as a grey RGB in,
 * the colour in Lab, its conversions OpenCV's for floats.
 */
object Colorizing {
    /** The network's side: it works at 256 × 256. */
    const val SIZE = 256

    /** What one run may take: the colour refreshed at least once a second, the smoothing covering the rest. */
    const val BUDGET_MS = 1000.0

    /**
     * How far the self-test's colours may be from the expected ones (NeuralChecks.colorizer), in Lab's a and b
     * averaged over 16 × 16 blocks: a unit running the network right lands within about one.
     */
    const val MAX_ERROR = 4.0

    /** The share of a new result in the smoothed colour: about the last four runs count. */
    const val SMOOTHING = 0.3f

    /** Whether a run of [ms] keeps the colour current. */
    fun fastEnough(ms: Double): Boolean = ms <= BUDGET_MS

    /** Whether colours off by [error] (see [MAX_ERROR]) mean the unit runs the network right. */
    fun rightEnough(error: Double): Boolean = error <= MAX_ERROR

    /**
     * What a refusal (the self-test found the device too slow or wrong for DDColor) is kept under: the app's version
     * [appVersion] and the model's files' digests ([model]) — an update to either tries the device afresh.
     */
    fun refusalKey(appVersion: Long, model: NeuralModels.Download = NeuralModels.DDCOLOR): String =
        "$appVersion|" + model.files.joinToString(",") { it.sha256 }

    /** Whether the refusal kept as [kept] (empty: none) still holds for this app version [appVersion]. */
    fun refused(kept: String, appVersion: Long): Boolean = kept.isNotEmpty() && kept == refusalKey(appVersion)

    /**
     * The colourisers the household is offered: DDColor where its files can be had ([available]: in place already, or
     * reachable in the model repository, see ModelFolder.reachable), else none — so its chip appears by itself once
     * the files are published.
     */
    fun offered(available: Boolean): List<PictureAdjustment.Colorize> =
        if (available) PictureAdjustment.Colorize.entries else listOf(PictureAdjustment.Colorize.OFF)

    private fun linear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun encoded(c: Float): Float =
        (if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f).coerceIn(0f, 1f)

    private fun f(t: Float): Float = if (t > 0.008856f) cbrt(t) else 7.787f * t + 16f / 116f

    /** The Lab of sRGB [r], [g], [b] (0..1), as OpenCV's COLOR_RGB2Lab for floats: L 0..100, a and b about ±127. */
    fun lab(r: Float, g: Float, b: Float): FloatArray {
        val lr = linear(r)
        val lg = linear(g)
        val lb = linear(b)
        val x = (0.412453f * lr + 0.357580f * lg + 0.180423f * lb) / 0.950456f
        val y = 0.212671f * lr + 0.715160f * lg + 0.072169f * lb
        val z = (0.019334f * lr + 0.119193f * lg + 0.950227f * lb) / 1.088754f
        val fy = f(y)
        val l = if (y > 0.008856f) 116f * fy - 16f else 903.3f * y
        return floatArrayOf(l, 500f * (f(x) - fy), 200f * (fy - f(z)))
    }

    /** The lightness alone of sRGB [r], [g], [b]: Lab's L. */
    fun lightness(r: Float, g: Float, b: Float): Float {
        val y = 0.212671f * linear(r) + 0.715160f * linear(g) + 0.072169f * linear(b)
        return if (y > 0.008856f) 116f * cbrt(y) - 16f else 903.3f * y
    }

    /** The sRGB (0..1) of [l], [a], [b], as OpenCV's COLOR_Lab2RGB for floats. */
    fun rgb(l: Float, a: Float, b: Float): FloatArray {
        val y: Float
        val fy: Float
        if (l <= 8f) {
            y = l / 903.3f
            fy = 7.787f * y + 16f / 116f
        } else {
            fy = (l + 16f) / 116f
            y = fy * fy * fy
        }
        fun back(t: Float) = if (t > 0.206893f) t * t * t else (t - 16f / 116f) / 7.787f
        val x = back(fy + a / 500f) * 0.950456f
        val z = back(fy - b / 200f) * 1.088754f
        return floatArrayOf(
            encoded(3.240479f * x - 1.53715f * y - 0.498535f * z),
            encoded(-0.969256f * x + 1.875991f * y + 0.041556f * z),
            encoded(0.055648f * x - 0.204043f * y + 1.057311f * z),
        )
    }

    private fun ByteBuffer.unit(at: Int): Float = (get(at).toInt() and 0xFF) / 255f

    // The source position of [d] of [dst] across [src], as OpenCV's linear resize: centres aligned, edges held.
    private inline fun sample(d: Int, src: Int, dst: Int, use: (Int, Int, Float) -> Unit) {
        val s = ((d + 0.5f) * src / dst - 0.5f).coerceIn(0f, (src - 1).toFloat())
        val i = s.toInt().coerceAtMost(src - 1)
        use(i, minOf(i + 1, src - 1), s - i)
    }

    /**
     * What the network is given for the RGBA [picture] ([width] × [height], rows as stored): the picture at 256 × 256
     * (OpenCV's linear resize), each pixel's lightness as a grey RGB — R, G and B planes, 0..1 — as DDColor's inference
     * makes it.
     */
    fun input(picture: ByteBuffer, width: Int, height: Int): FloatArray {
        val out = FloatArray(3 * SIZE * SIZE)
        val plane = SIZE * SIZE
        for (dy in 0 until SIZE) sample(dy, height, SIZE) { y0, y1, fy ->
            for (dx in 0 until SIZE) sample(dx, width, SIZE) { x0, x1, fx ->
                val rgb = FloatArray(3)
                for (c in 0 until 3) {
                    fun at(y: Int, x: Int) = picture.unit((y * width + x) * 4 + c)
                    val top = at(y0, x0) * (1 - fx) + at(y0, x1) * fx
                    val bottom = at(y1, x0) * (1 - fx) + at(y1, x1) * fx
                    rgb[c] = top * (1 - fy) + bottom * fy
                }
                val grey = rgb(lightness(rgb[0], rgb[1], rgb[2]), 0f, 0f)[0]
                val at = dy * SIZE + dx
                out[at] = grey
                out[plane + at] = grey
                out[2 * plane + at] = grey
            }
        }
        return out
    }

    /**
     * Whether the RGBA [picture] is grey already — its chroma near neutral throughout, as black-and-white material is
     * (a sepia tint is colour of its own): then colour is offered.
     */
    fun isGrey(picture: ByteBuffer, width: Int, height: Int): Boolean {
        var sum = 0.0
        var n = 0
        val step = maxOf(1, maxOf(width, height) / 64)
        for (y in 0 until height step step) for (x in 0 until width step step) {
            val at = (y * width + x) * 4
            val lab = lab(picture.unit(at), picture.unit(at + 1), picture.unit(at + 2))
            sum += sqrt(lab[1] * lab[1] + lab[2] * lab[2])
            n++
        }
        return n > 0 && sum / n < GREY_CHROMA
    }

    private const val GREY_CHROMA = 3.0

    /**
     * Whether the network's input [next] shows another scene than [previous] (both one plane of [input], or more): the
     * lightness changed throughout, not where something moved. None before is a cut too.
     */
    fun cut(previous: FloatArray?, next: FloatArray): Boolean {
        if (previous == null) return true
        var sum = 0.0
        val n = minOf(previous.size, next.size, SIZE * SIZE)
        for (i in 0 until n) sum += abs(previous[i] - next[i])
        return sum / n > CUT
    }

    private const val CUT = 0.12

    /** The colour (a and b planes at 256 × 256) smoothed over the network's runs, [weight] the share of a new one. */
    class Smoother(private val weight: Float = SMOOTHING) {
        /** The smoothed a and b planes; null before the first run, or after [clear]. */
        var current: FloatArray? = null
            private set

        /** Takes the network's [ab]; at a [cut], or the first time, as it is. */
        fun add(ab: FloatArray, cut: Boolean) {
            val now = current
            current = if (cut || now == null || now.size != ab.size) {
                ab.copyOf()
            } else {
                FloatArray(ab.size) { now[it] + (ab[it] - now[it]) * weight }
            }
        }

        /** Forgets the colour: a cut came, and the new scene's has not. */
        fun clear() {
            current = null
        }
    }

    /**
     * The RGBA [frame] ([width] × [height]) with the colour [ab] (a and b planes at 256 × 256) laid under its own
     * lightness, the chroma enlarged linearly: RGBA bytes, alpha kept. What the stage's colorize pass does on the
     * graphics chip, here for the checks.
     */
    fun colorize(frame: ByteBuffer, width: Int, height: Int, ab: FloatArray): ByteArray {
        val out = ByteArray(width * height * 4)
        val plane = SIZE * SIZE
        for (y in 0 until height) sample(y, SIZE, height) { y0, y1, fy ->
            for (x in 0 until width) sample(x, SIZE, width) { x0, x1, fx ->
                fun at(p: Int): Float {
                    val top = ab[p + y0 * SIZE + x0] * (1 - fx) + ab[p + y0 * SIZE + x1] * fx
                    val bottom = ab[p + y1 * SIZE + x0] * (1 - fx) + ab[p + y1 * SIZE + x1] * fx
                    return top * (1 - fy) + bottom * fy
                }
                val i = (y * width + x) * 4
                val l = lightness(frame.unit(i), frame.unit(i + 1), frame.unit(i + 2))
                val rgb = rgb(l, at(0), at(plane))
                for (c in 0 until 3) out[i + c] = (rgb[c] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
                out[i + 3] = frame.get(i + 3)
            }
        }
        return out
    }
}
