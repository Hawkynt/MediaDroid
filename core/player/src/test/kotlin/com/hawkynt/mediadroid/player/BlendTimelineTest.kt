package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlendTimelineTest {

    private val refresh = 16_666_667L
    private val frame = 22_857_143L // 25 fps at 1.75x

    /** Plays [arrivals] (ns) and returns the timeline position (frame number plus phase) at every refresh. */
    private fun positions(arrivals: List<Long>, until: Long): List<Double> {
        val timeline = BlendTimeline()
        val steady = ArrayDeque<Long>()
        val numbers = ArrayDeque<Int>()
        var next = 0
        val out = mutableListOf<Double>()
        var now = 0L
        while (now < until) {
            while (next < arrivals.size && arrivals[next] <= now) {
                steady.addLast(timeline.arrived(arrivals[next]))
                numbers.addLast(next)
                if (steady.size > 3) { steady.removeFirst(); numbers.removeFirst() }
                next++
            }
            if (steady.size >= 2) {
                val (earlier, phase) = timeline.position(now, steady.toLongArray())
                out += numbers[earlier] + phase.toDouble()
            }
            now += refresh
        }
        return out.drop(30)
    }

    @Test
    fun `even arrivals move the picture on by the same step every refresh`() {
        val steps = positions(List(400) { it * frame }, 400 * frame).zipWithNext { a, b -> b - a }
        steps.forEach { assertEquals(refresh.toDouble() / frame, it, 0.02) }
    }

    @Test
    fun `arrivals a few milliseconds early or late never stop the picture`() {
        // Media3 hands frames over unevenly: up to 6 ms either way, in a fixed pattern.
        val wobble = listOf(0L, 5L, -4L, 6L, -6L, 2L, -1L, 4L).map { it * 1_000_000L }
        val arrivals = List(400) { it * frame + wobble[it % wobble.size] }
        val steps = positions(arrivals, 400 * frame).zipWithNext { a, b -> b - a }
        assertTrue("a refresh repeated a picture: ${steps.minOrNull()}", steps.all { it > 0.4 })
        assertTrue("a refresh jumped: ${steps.maxOrNull()}", steps.all { it < 1.1 })
    }

    @Test
    fun `the timeline runs a frame and a quarter behind`() {
        val timeline = BlendTimeline()
        var t = 0L
        repeat(50) { timeline.arrived(t); t += frame }
        assertEquals((frame * 5 / 4).toDouble(), timeline.delayNs.toDouble(), (frame / 50).toDouble())
    }

    @Test
    fun `with a frame more to wait for, for a neural in-between, it runs two frames and a quarter behind`() {
        val timeline = BlendTimeline()
        timeline.extraFrames = 1
        var t = 0L
        repeat(50) { timeline.arrived(t); t += frame }
        assertEquals((frame * 9 / 4).toDouble(), timeline.delayNs.toDouble(), (frame / 50).toDouble())
        val steady = LongArray(3) { t - frame * (3 - it) }
        val (earlier, phase) = timeline.position(t, steady)
        assertEquals(0, earlier)
        assertEquals(0.75f, phase, 0.05f)
    }

    @Test
    fun `after a pause the timeline starts afresh`() {
        val timeline = BlendTimeline()
        var t = 0L
        repeat(20) { timeline.arrived(t); t += frame }
        val resumed = t + 2_000_000_000L
        assertEquals(resumed, timeline.arrived(resumed))
    }
}
