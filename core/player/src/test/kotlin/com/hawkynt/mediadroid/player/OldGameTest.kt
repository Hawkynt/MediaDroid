package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * Old-console mode on a woven 240p clip (`resources/oldgame/`, written by `make-input.py` there): each field stretched
 * as FFmpeg's `field` and bilinear `scale` filters stretch it (n9.0.2, the commands in that script's header), a still
 * field averaged with the one before, a scrolling one not.
 */
class OldGameTest {

    private val width = 256
    private val height = 448

    private fun frames(name: String): List<Deinterlacer.Plane> {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/oldgame/$name"))
            .use { GZIPInputStream(it).readBytes() }
        val size = width * height
        return (0 until bytes.size / size).map { k ->
            Deinterlacer.Plane(width, height, IntArray(size) { bytes[k * size + it].toInt() and 0xFF })
        }
    }

    private val clip = frames("console.gray.gz")

    @Test
    fun `a still field is averaged with the one before, a scrolling one is not`() {
        val averaged = (1 until clip.size).map { k ->
            listOf(false, true).map { second -> OldGame.averages(clip[k - 1], clip[k], second, true) }
        }
        // Frame 6's first field is the first to scroll.
        assertEquals(List(5) { listOf(true, true) } + List(6) { listOf(false, false) }, averaged)
        assertFalse(OldGame.averages(null, clip[0], second = false, topFieldFirst = true))
    }

    @Test
    fun `each field is stretched back to the full height as FFmpeg's bilinear scaling stretches it`() {
        val top = frames("top.gray.gz")
        val bottom = frames("bottom.gray.gz")
        for (k in 1 until clip.size) {
            for (second in listOf(false, true)) {
                val averaged = OldGame.averages(clip[k - 1], clip[k], second, true)
                val out = OldGame.picture(clip[k - 1], clip[k], second, true, averaged)
                val expected = if (second) bottom[k] else top[k]
                val worst = out.pixels.indices.maxOf { abs(out.pixels[it] - expected.pixels[it]) }
                assertTrue("frame $k, second $second: off by $worst", worst <= 1)
            }
        }
    }

    @Test
    fun `bottom field first, the bottom field is the newest of a frame's first moment`() {
        val out = OldGame.picture(clip[0], clip[1], second = false, topFieldFirst = false, averaged = false)
        val expected = frames("bottom.gray.gz")[1]
        assertTrue(out.pixels.indices.all { abs(out.pixels[it] - expected.pixels[it]) <= 1 })
    }

    @Test
    fun `the stretch has no steps a line high, where bob would double each line`() {
        // A field whose lines rise by 8 each: stretched, every row rises by 4 from the one above, but at the edges.
        val ramp = Deinterlacer.Plane(16, 40, IntArray(16 * 40) { (it / 16 / 2) * 8 })
        val out = OldGame.picture(ramp, ramp, second = false, topFieldFirst = true, averaged = false)
        val steps = (2 until 38).map { out[0, it] - out[0, it - 1] }
        assertEquals(List(36) { 4 }, steps)
    }

    @Test
    fun `averaging drops the newest value's lowest bit and rounds up, as DScaler's does`() {
        assertEquals(100, OldGame.average(100, 100))
        assertEquals(101, OldGame.average(101, 101))
        assertEquals(101, OldGame.average(101, 102))
        assertEquals(51, OldGame.average(0, 101))
        assertEquals(255, OldGame.average(255, 255))
    }
}
