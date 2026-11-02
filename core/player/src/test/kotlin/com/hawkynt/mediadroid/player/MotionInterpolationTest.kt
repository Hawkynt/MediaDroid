package com.hawkynt.mediadroid.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * Motion-compensated interpolation against FFmpeg 6.0's `minterpolate` at its defaults, doubling 25 fps
 * to 50: every output frame byte for byte. `resources/minterpolate/` holds the clip, written by
 * `make-input.py` there, and FFmpeg's output, made with the command in its header. The driver below plays
 * FFmpeg's filter_frame: a window of four frames, the first taken twice, vectors searched once three
 * are in, and output frames while their time has not passed the later frame of the pair.
 */
class MotionInterpolationTest {

    private val width = 128
    private val height = 96
    private val size = width * height

    private fun frames(name: String): List<Deinterlacer.Plane> {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/minterpolate/$name"))
            .use { GZIPInputStream(it).readBytes() }
        return (0 until bytes.size / size).map { k ->
            Deinterlacer.Plane(width, height, IntArray(size) { bytes[k * size + it].toInt() and 0xFF })
        }
    }

    private fun interpolated(input: List<Deinterlacer.Plane>): List<Deinterlacer.Plane> {
        val estimator = MotionInterpolation.Estimator(width, height)
        val scene = MotionInterpolation.SceneChange()
        // The window: frame and its time in input frames (25 fps), oldest first.
        val window = arrayOfNulls<Pair<Deinterlacer.Plane, Int>>(4)
        val out = ArrayList<Deinterlacer.Plane>()
        var outTime = 0 // in output frames (50 fps)
        fun inject(frame: Deinterlacer.Plane, time: Int) {
            window.copyInto(window, 0, 1, 4)
            window[3] = frame to time
            estimator.advance()
            if (window[0] != null) estimator.estimate(window[1]!!.first, window[2]!!.first)
        }
        input.forEachIndexed { time, frame ->
            if (window[3] == null) inject(frame, time)
            inject(frame, time)
            if (window[0] == null) return@forEachIndexed
            val (prev, prevTime) = window[1]!!
            val (next, nextTime) = window[2]!!
            val cut = scene.check(prev, next)
            while (outTime <= nextTime * 2) {
                val pts = outTime * MotionInterpolation.ALPHA_MAX / 2
                val alpha = ((pts - prevTime * MotionInterpolation.ALPHA_MAX) / (nextTime - prevTime))
                    .coerceIn(0, MotionInterpolation.ALPHA_MAX)
                out += when {
                    alpha == 0 -> prev
                    alpha == MotionInterpolation.ALPHA_MAX -> next
                    cut -> if (alpha > MotionInterpolation.ALPHA_MAX / 2) next else prev
                    else -> MotionInterpolation.compensate(
                        prev, next, estimator.vectors, estimator.blocksWide, estimator.blocksHigh, alpha,
                    )
                }
                outTime++
            }
        }
        return out
    }

    @Test
    fun `every frame between agrees with FFmpeg's minterpolate`() {
        val expected = frames("expected.gray.gz")
        val actual = interpolated(frames("input.gray.gz"))
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertArrayEquals("frame $it", expected[it].pixels, actual[it].pixels) }
    }

    @Test
    fun `half way between two still frames is the same frame`() {
        val still = Deinterlacer.Plane(32, 32, IntArray(32 * 32) { (it * 7) % 251 })
        val estimator = MotionInterpolation.Estimator(32, 32)
        estimator.estimate(still, still)
        val between = MotionInterpolation.compensate(still, still, estimator.vectors, 2, 2, 512)
        assertArrayEquals(still.pixels, between.pixels)
    }

    @Test
    fun `the search runs on a picture of half the size, every other row and column`() {
        val plane = Deinterlacer.Plane(4, 4, IntArray(16) { it })
        val half = MotionInterpolation.halve(plane)
        assertEquals(2, half.width)
        assertArrayEquals(intArrayOf(0, 2, 8, 10), half.pixels)
    }

    @Test
    fun `motion runs through the whole interval, the strengths blend around its middle`() {
        val motion = PictureAdjustment.Interpolation.MOTION
        assertEquals(true, motion.isMotionCompensated)
        assertEquals(false, PictureAdjustment.Interpolation.HIGH.isMotionCompensated)
        assertEquals(0.25f, motion.weight(0.25f), 0f)
        assertEquals(1f, motion.weight(1.2f), 0f)
    }
}
