package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Motion-compensated temporal smoothing against ground truth: progressive pictures that move, one for every field's
 * moment, split into interlaced frames (top field first) — with a little noise on the fields, as a camera's, or
 * without. Each field's rebuilt lines are compared with the clean original of its moment, as a mean error per pixel
 * away from the edges, and have to come nearer it than BWDIF's (the smoothing's own base) by the committed margins.
 * The field's own lines are the camera's for every method and left out.
 */
class TemporalSmoothingTest {

    private val width = 128
    private val height = 96

    // Soft waves, fine diagonal detail and blocks a little brighter, moving [dx] across and [dy] down a field.
    private fun scene(t: Int, dx: Int, dy: Int): IntArray = IntArray(width * height) { i ->
        val x = i % width + dx * t
        val y = i / width + dy * t
        var v = 120.0 + 45 * sin(x / 6.0) * cos(y / 8.0) + 25 * sin((x + 2 * y) / 3.5)
        if ((x / 20 + y / 16) % 3 == 0) v += 40
        v.toInt().coerceIn(16, 235)
    }

    // A fixed noise of up to [amplitude] either way, different for every field and pixel.
    private fun noise(t: Int, i: Int, amplitude: Int): Int {
        if (amplitude == 0) return 0
        var h = t * 73856093 xor i * 19349663
        h = (h xor (h ushr 13)) * 0x5bd1e995
        h = h xor (h ushr 15)
        return ((h and 0xFFFF) % (2 * amplitude + 1)) - amplitude
    }

    /** The mean errors of BWDIF and of the smoothing on the rebuilt lines, over the clip's inner frames' fields. */
    private fun errors(dx: Int, dy: Int, amplitude: Int): Pair<Double, Double> {
        val frames = (0 until 10).map { k ->
            val top = scene(2 * k, dx, dy)
            val bottom = scene(2 * k + 1, dx, dy)
            Deinterlacer.Plane(width, height, IntArray(width * height) { i ->
                val field = if ((i / width) % 2 == 0) 2 * k else 2 * k + 1
                ((if (field == 2 * k) top[i] else bottom[i]) + noise(field, i, amplitude)).coerceIn(0, 255)
            })
        }
        fun error(out: Deinterlacer.Plane, truth: IntArray, rows: Int): Double {
            var sum = 0L
            var n = 0
            for (y in 8 until height - 8) {
                if (y % 2 != rows) continue
                for (x in 8 until width - 8) {
                    sum += abs(out[x, y] - truth[y * width + x])
                    n++
                }
            }
            return sum.toDouble() / n
        }
        var bwdif = 0.0
        var smooth = 0.0
        var n = 0
        for (k in 2 until frames.size - 2) {
            for (second in listOf(false, true)) {
                val truth = scene(2 * k + if (second) 1 else 0, dx, dy)
                val rows = 1 - Deinterlacer.rebuiltParity(second, true)
                val base = Deinterlacer.deinterlace(
                    Deinterlacer.Method.BWDIF, frames[k - 1], frames[k], frames[k + 1], second, true,
                )
                bwdif += error(base, truth, rows)
                val out = TemporalSmoothing.deinterlace(
                    frames[k - 2], frames[k - 1], frames[k], frames[k + 1], frames[k + 2], second, true,
                )
                smooth += error(out, truth, rows)
                n++
            }
        }
        return bwdif / n to smooth / n
    }

    private fun check(dx: Int, dy: Int, amplitude: Int, margin: Double) {
        val (bwdif, smooth) = errors(dx, dy, amplitude)
        assertTrue("moving $dx, $dy, noise $amplitude: BWDIF $bwdif, smoothed $smooth", smooth <= bwdif * margin)
    }

    // Measured: BWDIF 4.99 / smoothed 4.39 (noisy pan), 3.82 / 3.22 (clean pan), 2.99 / 2.78 (noisy still picture),
    // 4.67 / 4.60 (noisy, an odd line down a field), 3.38 / 3.38 (clean, an odd line down a field).

    @Test
    fun `a noisy picture panning an even number of lines a field comes out nearer the truth than BWDIF's`() =
        check(3, 2, 6, 0.9)

    @Test
    fun `a clean panning picture comes out nearer the truth than BWDIF's`() = check(3, 2, 0, 0.9)

    @Test
    fun `a noisy still picture comes out nearer the truth than BWDIF's`() = check(0, 0, 6, 0.95)

    @Test
    fun `moving an odd number of lines a field, where the fields either side hold only guesses, it is no worse`() {
        check(2, 1, 6, 1.0)
        check(2, 1, 0, 1.0)
    }

    @Test
    fun `the field's own lines come out exactly as the camera took them`() {
        val p = Deinterlacer.Plane(width, height, IntArray(width * height) { (it * 7919) % 251 })
        val q = Deinterlacer.Plane(width, height, IntArray(width * height) { (it * 104729) % 241 })
        val out = TemporalSmoothing.deinterlace(p, q, p, q, p, false, true)
        for (y in 0 until height step 2) for (x in 0 until width) assertEquals(p[x, y], out[x, y])
    }

    @Test
    fun `the search tries the nearest displacements first`() {
        assertEquals(0 to 0, TemporalSmoothing.CANDIDATES.first())
        assertEquals(35, TemporalSmoothing.CANDIDATES.size)
    }
}
