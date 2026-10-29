package com.hawkynt.mediadroid.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStatisticsTest {

    private val ms = 1_000_000L

    @Test
    fun `frames arriving evenly have no jitter`() {
        val stats = PlaybackStatistics()
        repeat(11) { stats.frameArrived(it * 40 * ms) }
        val s = stats.snapshot()
        assertEquals(10, s.frameTimesMs.size)
        assertEquals(40f, s.frameTimesMs.average().toFloat(), 1e-3f)
        assertEquals(25f, s.framesPerSecond, 1e-3f)
        assertEquals(0f, s.jitterMs, 1e-3f)
    }

    @Test
    fun `jitter is how far the frame times spread around their mean`() {
        val stats = PlaybackStatistics()
        var t = 0L
        stats.frameArrived(t)
        repeat(10) {
            t += if (it % 2 == 0) 30 * ms else 50 * ms
            stats.frameArrived(t)
        }
        assertEquals(10f, stats.snapshot().jitterMs, 1e-3f)
    }

    @Test
    fun `judder is how unevenly pictures stay on screen, against how long they stay`() {
        val stats = PlaybackStatistics()
        var t = 0L
        stats.pictureShown(t)
        // 3:2 on a 60 Hz screen: 50 and 33.3 ms in turn.
        repeat(10) {
            t += if (it % 2 == 0) 50 * ms else 33_333_333L
            stats.pictureShown(t)
        }
        val s = stats.snapshot()
        assertEquals(24f, s.shownPerSecond, 0.01f)
        assertEquals(20f, s.judderPercent, 0.01f)
    }

    @Test
    fun `work, scales and the late and dropped frames are kept`() {
        val stats = PlaybackStatistics()
        stats.work(3 * ms)
        stats.work(5 * ms)
        stats.searchScale(2)
        stats.searchScale(4)
        stats.late()
        stats.dropped()
        stats.dropped()
        val s = stats.snapshot()
        assertArrayEquals(floatArrayOf(3f, 5f), s.workMs, 1e-6f)
        assertArrayEquals(intArrayOf(2, 4), s.scales)
        assertEquals(1, s.late)
        assertEquals(2, s.dropped)
    }

    @Test
    fun `only the latest samples are kept`() {
        val stats = PlaybackStatistics(capacity = 4)
        repeat(10) { stats.work(it * ms) }
        assertArrayEquals(floatArrayOf(6f, 7f, 8f, 9f), stats.snapshot().workMs, 1e-6f)
    }

    @Test
    fun `starting afresh forgets everything`() {
        val stats = PlaybackStatistics()
        stats.frameArrived(0)
        stats.frameArrived(40 * ms)
        stats.late()
        stats.clear()
        val s = stats.snapshot()
        assertEquals(0, s.frameTimesMs.size)
        assertEquals(0, s.late)
        assertEquals(0f, s.framesPerSecond, 0f)
    }

    @Test
    fun `the graphics thread's time per frame is kept beside the processor's`() {
        val stats = PlaybackStatistics()
        stats.graphics(12 * ms)
        stats.graphics(30 * ms)
        assertArrayEquals(floatArrayOf(12f, 30f), stats.snapshot().graphicsMs, 1e-6f)
    }
}
