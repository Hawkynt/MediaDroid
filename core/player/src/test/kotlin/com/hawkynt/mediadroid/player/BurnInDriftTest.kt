package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BurnInDriftTest {
    @Test
    fun `a few pixels, more on a sharper screen, two at the least`() {
        assertEquals(4, BurnInDrift.amplitude(1080))
        assertEquals(8, BurnInDrift.amplitude(2160))
        assertEquals(3, BurnInDrift.amplitude(720))
        assertEquals(2, BurnInDrift.amplitude(480))
    }

    @Test
    fun `it starts in the middle and stays put for a step`() {
        assertEquals(0 to 0, BurnInDrift.offset(0, 4))
        assertEquals(0 to 0, BurnInDrift.offset(BurnInDrift.STEP_MS - 1, 4))
        assertTrue(BurnInDrift.offset(BurnInDrift.STEP_MS, 4) != (0 to 0))
    }

    @Test
    fun `every step is to a neighbouring pixel, within the square`() {
        val a = 4
        var last = BurnInDrift.offset(0, a)
        for (i in 1..1000) {
            val now = BurnInDrift.offset(i * BurnInDrift.STEP_MS, a)
            assertTrue(abs(now.first) <= a && abs(now.second) <= a)
            assertEquals("step $i from $last to $now", 1, abs(now.first - last.first) + abs(now.second - last.second))
            last = now
        }
    }

    @Test
    fun `every offset in the square is visited, and the drift comes round`() {
        val a = 3
        val period = BurnInDrift.period(a)
        assertEquals(2L * 48 * BurnInDrift.STEP_MS, period)
        val seen = (0 until period / BurnInDrift.STEP_MS).map { BurnInDrift.offset(it * BurnInDrift.STEP_MS, a) }
        assertEquals(49, seen.toSet().size)
        assertEquals(BurnInDrift.offset(12_345, a), BurnInDrift.offset(12_345 + period, a))
    }

    @Test
    fun `no drift without an amplitude`() {
        assertEquals(0 to 0, BurnInDrift.offset(999_999, 0))
    }
}
