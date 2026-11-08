package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Test

class FramePacerTest {
    private val ms = 1_000_000L

    /** Feeds arrivals at [times] (ms) and shows each picture as soon as the pacer allows; returns when, in ms. */
    private fun shownTimes(pacer: FramePacer, times: List<Long>): List<Long> {
        val shown = ArrayList<Long>()
        var queued = 0
        var clock = 0L
        var next = 0
        while (next < times.size || queued > 0) {
            while (next < times.size && times[next] * ms <= clock) {
                pacer.arrived(times[next] * ms)
                queued++
                next++
            }
            if (queued > 0 && pacer.dueAt(clock, queued) <= clock) {
                pacer.shown(clock)
                shown += clock / ms
                queued--
            }
            clock += ms
        }
        return shown
    }

    @Test
    fun `pictures handed over in pairs are shown evenly`() {
        // 25 a second, but every other one arrives 3 ms after the one before.
        val arrivals = (0 until 40).map { i -> (i / 2) * 80L + if (i % 2 == 0) 0L else 3L }
        val shown = shownTimes(FramePacer(), arrivals)
        // Half a second to settle on the interval, then every gap within a few milliseconds of 40.
        val gaps = shown.zipWithNext { a, b -> b - a }.drop(12)
        gaps.forEach { assertEquals("gaps $gaps", 40.0, it.toDouble(), 6.0) }
    }

    @Test
    fun `evenly handed pictures are shown as they come`() {
        val arrivals = (0 until 20).map { it * 40L }
        assertEquals(arrivals, shownTimes(FramePacer(), arrivals))
    }

    @Test
    fun `after a pause the first picture is shown at once`() {
        val pacer = FramePacer()
        listOf(0L, 40L, 80L).forEach {
            pacer.arrived(it * ms)
            pacer.shown(it * ms)
        }
        pacer.arrived(2_000 * ms)
        assertEquals(2_000 * ms, pacer.dueAt(2_000 * ms, 1))
    }

    @Test
    fun `a queue that grows is caught up at once`() {
        val pacer = FramePacer()
        listOf(0L, 40L, 80L).forEach {
            pacer.arrived(it * ms)
            pacer.shown(it * ms)
        }
        assertEquals(81 * ms, pacer.dueAt(81 * ms, FramePacer.MAX_QUEUED))
    }
}
