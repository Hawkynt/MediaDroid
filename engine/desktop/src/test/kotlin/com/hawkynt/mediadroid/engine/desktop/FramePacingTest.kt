package com.hawkynt.mediadroid.engine.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class FramePacingTest {

    private val period = 1_000_000_000L / 60

    @Test
    fun `costs are summed up as mean, 95th percentile and worst`() {
        val ms = (1..100).map { it * 1_000_000L }.shuffled(java.util.Random(7)).toLongArray()
        val costs = FramePacing.Costs.of(ms)
        assertEquals(50.5, costs.meanMs, 1e-9)
        assertEquals(95.0, costs.p95Ms, 1e-9)
        assertEquals(100.0, costs.maxMs, 1e-9)
    }

    @Test
    fun `one cost is its own mean, percentile and worst`() {
        val costs = FramePacing.Costs.of(longArrayOf(2_500_000L))
        assertEquals(2.5, costs.meanMs, 1e-9)
        assertEquals(2.5, costs.p95Ms, 1e-9)
        assertEquals(2.5, costs.maxMs, 1e-9)
    }

    @Test
    fun `no costs are nothing`() {
        val costs = FramePacing.Costs.of(LongArray(0))
        assertEquals(0.0, costs.maxMs, 0.0)
    }

    @Test
    fun `every picture on time, one period apart, is neither dropped nor doubled`() {
        val pacing = FramePacing.of(IntArray(10) { it }, LongArray(10) { it * period }, period)
        assertEquals(0, pacing.dropped)
        assertEquals(0, pacing.doubled)
    }

    @Test
    fun `a picture skipped by the clock is dropped`() {
        val content = intArrayOf(0, 1, 2, 4, 5, 8)
        val shown = LongArray(content.size) { content[it] * period }
        val pacing = FramePacing.of(content, shown, period)
        assertEquals(3, pacing.dropped)
        assertEquals(0, pacing.doubled)
    }

    @Test
    fun `a picture held a period longer than its own is doubled`() {
        // The third picture comes a period late: the second stays on screen twice.
        val shown = longArrayOf(0, period, 3 * period, 4 * period, 7 * period)
        val pacing = FramePacing.of(intArrayOf(0, 1, 2, 3, 4), shown, period)
        assertEquals(0, pacing.dropped)
        assertEquals(3, pacing.doubled)
    }

    @Test
    fun `a faster screen's half-period jitter is neither`() {
        // A 120 Hz screen: a 60 Hz picture lands on a refresh either side of its due time.
        val half = period / 2
        val shown = longArrayOf(0, period + half, 2 * period, 3 * period + half, 4 * period - 1)
        val pacing = FramePacing.of(intArrayOf(0, 1, 2, 3, 4), shown, period)
        assertEquals(0, pacing.dropped)
        assertEquals(0, pacing.doubled)
    }
}
