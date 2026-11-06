package com.hawkynt.mediadroid.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The reference temporal denoising against FFmpeg's own atadenoise filter, byte for byte: `resources/temporal-denoise/`
 * holds five grainy frames with a moving bar (`make-input.py`) and the middle one denoised against its neighbours by
 * FFmpeg's filter (`make-expected.sh`).
 */
class TemporalDenoiseTest {

    private fun ppm(name: String): ByteArray {
        val data = requireNotNull(javaClass.getResourceAsStream("/temporal-denoise/$name")) { name }
            .use { it.readBytes() }
        return data.copyOfRange(data.size - 64 * 48 * 3, data.size)
    }

    private val before = ppm("frame1.ppm")
    private val current = ppm("frame2.ppm")
    private val after = ppm("frame3.ppm")

    private fun check(a: String, b: String) {
        val expected = ppm("$a-$b.ppm")
        val changed = expected.indices.count { expected[it] != current[it] }
        assertTrue("FFmpeg changed only $changed values", changed > expected.size / 10)
        val params = TemporalDenoise.Params(a.toFloat(), b.toFloat())
        assertArrayEquals(expected, TemporalDenoise.apply(before, current, after, params))
    }

    @Test
    fun `FFmpeg's defaults agree`() = check("0.02", "0.04")

    @Test
    fun `four times the thresholds agree`() = check("0.08", "0.16")

    @Test
    fun `the moving bar is left alone, the still background averaged`() {
        val out = TemporalDenoise.apply(before, current, after, TemporalDenoise.Params(0.08f, 0.16f))
        // Frame 2's bar spans x 16..23, frame 1's 13..20: at x 22 the frame before shows the background, so no frame
        // is taken; in the background far from the bar most values move.
        for (c in 0..2) assertEquals(current[(10 * 64 + 22) * 3 + c], out[(10 * 64 + 22) * 3 + c])
        val background = (30 until 48).flatMap { y -> (40 until 64).map { x -> (y * 64 + x) * 3 } }
        assertTrue(background.count { out[it] != current[it] } > background.size / 3)
    }

    @Test
    fun `the slider runs from off through FFmpeg's defaults to four times their tolerance`() {
        assertNull(TemporalDenoise.params(0f))
        assertEquals(TemporalDenoise.Defaults, TemporalDenoise.params(0.25f))
        assertEquals(TemporalDenoise.Params(0.08f, 0.16f), TemporalDenoise.params(1f))
        assertEquals(4, TemporalDenoise.threshold(0.02f))
        assertEquals(9, TemporalDenoise.threshold(0.04f))
    }

    @Test
    fun `with the noise level known, threshold A is six sigmas times the strength and B twice that`() {
        val half = requireNotNull(TemporalDenoise.params(0.5f, 2.0))
        assertEquals(6, TemporalDenoise.threshold(half.a))
        assertEquals(12, TemporalDenoise.threshold(half.b))
        val clean = requireNotNull(TemporalDenoise.params(1f, 0.1))
        assertEquals(0, TemporalDenoise.threshold(clean.a))
        // A stays within FFmpeg's 0.3.
        val wild = requireNotNull(TemporalDenoise.params(1f, 40.0))
        assertEquals(75, TemporalDenoise.threshold(wild.a))
        assertTrue(wild.a <= 0.3f)
        assertNull(TemporalDenoise.params(0f, 2.0))
    }

    @Test
    fun `without the frame before, the frame after is judged alone`() {
        val params = TemporalDenoise.Params(0.08f, 0.16f)
        val current = byteArrayOf(100, 100)
        val after = byteArrayOf(104, 120)
        assertArrayEquals(byteArrayOf(102, 100), TemporalDenoise.apply(null, current, after, params))
        assertArrayEquals(current, TemporalDenoise.apply(null, current, null, params))
        // A frame before that is turned away closes the frame after too, as in FFmpeg's filter.
        assertArrayEquals(current, TemporalDenoise.apply(byteArrayOf(0, 0), current, after, params))
    }

    // Smooth waves moved [dx] x [dy] pixels, with Gaussian grain of [sigma] steps from [random] (none at 0).
    private fun waves(w: Int, h: Int, dx: Int, dy: Int, sigma: Double, random: Random) = ByteArray(w * h) { i ->
        val x = i % w + dx
        val y = i / w + dy
        val v = 128 + 80 * sin(x / 9.0) * cos(y / 7.0) + sigma * random.nextGaussian()
        v.roundToInt().coerceIn(0, 255).toByte()
    }

    private fun plane(w: Int, h: Int, bytes: ByteArray) =
        Deinterlacer.Plane(w, h, IntArray(w * h) { bytes[it].toInt() and 0xFF })

    @Test
    fun `the noise estimate finds the grain that was added`() {
        val random = Random(11)
        for (sigma in listOf(1.5, 3.0, 6.0)) {
            val flat = ByteArray(160 * 120) { (100 + sigma * random.nextGaussian()).roundToInt().toByte() }
            val estimate = TemporalDenoise.noiseSigma(plane(160, 120, flat))
            assertTrue("$sigma on flat grey read $estimate", abs(estimate - sigma) < 0.08 * sigma + 0.1)
            val wavy = waves(160, 120, 0, 0, sigma, random)
            val onWaves = TemporalDenoise.noiseSigma(plane(160, 120, wavy))
            assertTrue("$sigma on waves read $onWaves", abs(onWaves - sigma) < 0.12 * sigma + 0.2)
        }
        // A clean picture reads near nothing: the smooth waves leave only their rounding.
        val clean = TemporalDenoise.noiseSigma(plane(160, 120, waves(160, 120, 0, 0, 0.0, random)))
        assertTrue("clean read $clean", clean < 0.5)
        val level = TemporalDenoise.NoiseLevel()
        assertEquals(4.0, level.add(4.0), 0.0)
        assertEquals(3.5, level.add(0.0), 1e-9)
    }

    @Test
    fun `without motion a frame stays where it is`() {
        val random = Random(3)
        val frame = waves(64, 48, 0, 0, 2.0, random)
        val still = TemporalDenoise.Motion(4, 3, 1, IntArray(4 * 3 * 2))
        for (side in TemporalDenoise.Side.entries) {
            assertArrayEquals(frame, TemporalDenoise.aligned(frame, 64, 48, still, side))
        }
        // At twice the scale the grid covers a picture twice as large.
        val big = waves(128, 96, 0, 0, 2.0, random)
        val stillBig = TemporalDenoise.Motion(4, 3, 2, IntArray(4 * 3 * 2))
        assertArrayEquals(big, TemporalDenoise.aligned(big, 128, 96, stillBig, TemporalDenoise.Side.BEFORE))
    }

    // Three grainy frames of waves panning [step] pixels a frame, the middle one's clean truth, and the motion
    // MotionInterpolation's search finds between each pair, on the grainy frames as the player has them.
    private inner class Pan(val w: Int, val h: Int, step: Int, sigma: Double) {
        private val random = Random(5)
        val frames = (0..2).map { waves(w, h, it * step, 0, sigma, random) }
        val truth = waves(w, h, step, 0, 0.0, random)
        private val estimator = MotionInterpolation.Estimator(w, h)

        private fun search(a: ByteArray, b: ByteArray): TemporalDenoise.Motion {
            estimator.advance()
            estimator.estimate(plane(w, h, a), plane(w, h, b))
            return TemporalDenoise.Motion(estimator.blocksWide, estimator.blocksHigh, 1, estimator.vectors.copyOf())
        }

        val before = search(frames[0], frames[1])
        val after = search(frames[1], frames[2])
    }

    private fun error(a: ByteArray, b: ByteArray, w: Int, h: Int, margin: Int): Double {
        var sum = 0.0
        var n = 0
        for (y in margin until h - margin) for (x in margin until w - margin) {
            sum += abs((a[y * w + x].toInt() and 0xFF) - (b[y * w + x].toInt() and 0xFF))
            n++
        }
        return sum / n
    }

    @Test
    fun `moved along the motion, the frames either side line up with the one shown`() {
        val pan = Pan(128, 96, 4, 0.0)
        val before = TemporalDenoise.aligned(pan.frames[0], 128, 96, pan.before, TemporalDenoise.Side.BEFORE)
        val after = TemporalDenoise.aligned(pan.frames[2], 128, 96, pan.after, TemporalDenoise.Side.AFTER)
        val plain = error(pan.frames[0], pan.frames[1], 128, 96, 16)
        val beforeError = error(before, pan.frames[1], 128, 96, 16)
        val afterError = error(after, pan.frames[1], 128, 96, 16)
        val report = "before $beforeError, after $afterError, unmoved $plain"
        assertTrue(report, maxOf(beforeError, afterError) < plain / 10)
    }

    @Test
    fun `along the motion a pan is denoised like a still picture, where alone it is left as motion`() {
        val w = 128
        val h = 96
        val pan = Pan(w, h, 4, 3.0)
        val sigma = TemporalDenoise.noiseSigma(plane(w, h, pan.frames[1]))
        val params = requireNotNull(TemporalDenoise.params(0.5f, sigma))
        val plain = TemporalDenoise.apply(pan.frames[0], pan.frames[1], pan.frames[2], params)
        val before = TemporalDenoise.aligned(pan.frames[0], w, h, pan.before, TemporalDenoise.Side.BEFORE)
        val after = TemporalDenoise.aligned(pan.frames[2], w, h, pan.after, TemporalDenoise.Side.AFTER)
        val moved = TemporalDenoise.apply(before, pan.frames[1], after, params)
        val grain = error(pan.frames[1], pan.truth, w, h, 16)
        val plainError = error(plain, pan.truth, w, h, 16)
        val movedError = error(moved, pan.truth, w, h, 16)
        val report = "grain $grain, alone $plainError, along the motion $movedError"
        assertTrue(report, movedError < 0.75 * grain)
        assertTrue(report, movedError < 0.8 * plainError)
    }
}
