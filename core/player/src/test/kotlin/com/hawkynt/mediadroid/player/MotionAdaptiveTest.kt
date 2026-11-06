package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adaptive switch on pictures whose motion is known: DScaler's Adaptive weaves after 100 still fields — two to a
 * frame, so the 50th frame after the first — and leaves weaving with the first field that moves.
 */
class MotionAdaptiveTest {

    private val width = 96
    private val height = 80

    // A bar 8 pixels wide at [position] on a mid grey: moving it 4 pixels moves 8 columns of every row by 160.
    private fun picture(position: Int) = Deinterlacer.Plane(
        width,
        height,
        IntArray(width * height) { if ((it % width - position) in 0 until 8) 220 else 60 },
    )

    @Test
    fun `a still picture is woven from its hundredth still field on`() {
        val adaptive = MotionAdaptive()
        val still = picture(40)
        assertFalse(adaptive.add(null, still))
        val verdicts = (1..60).map { adaptive.add(still, still) }
        assertEquals(List(49) { false } + List(11) { true }, verdicts)
    }

    @Test
    fun `the first moving field ends weaving at once, and the count starts over`() {
        val adaptive = MotionAdaptive()
        var last = picture(40)
        repeat(50) { adaptive.add(last, last) }
        assertTrue(adaptive.weave)
        val moved = picture(44)
        assertFalse(adaptive.add(last, moved))
        last = moved
        val verdicts = (1..50).map { adaptive.add(last, last) }
        assertEquals(List(49) { false } + true, verdicts)
    }

    @Test
    fun `noise under the threshold is still, and a picture read every fourth column counts four times as much`() {
        val adaptive = MotionAdaptive()
        // One pixel off by 100 in a field: (100² >> 12) = 2, under DScaler's 15; four times that is still under it.
        val base = picture(40)
        val noisy = Deinterlacer.Plane(width, height, base.pixels.copyOf().also { it[34 * width + 30] += 100 })
        repeat(49) { adaptive.add(base, base) }
        assertTrue(adaptive.add(base, noisy, columnStep = 4))
        // Eight such pixels in one field are 16, which moves.
        val moving = Deinterlacer.Plane(
            width,
            height,
            base.pixels.copyOf().also { p -> (0 until 8).forEach { p[(32 + 2 * it) * width + 30] += 100 } },
        )
        assertFalse(adaptive.add(base, moving))
    }
}
