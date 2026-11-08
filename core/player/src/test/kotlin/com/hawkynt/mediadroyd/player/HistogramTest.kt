package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistogramTest {
    private fun picture(width: Int, height: Int, colour: (Int, Int) -> Int) = ByteArray(width * height * 4) { i ->
        val p = i / 4
        val c = colour(p % width, p / width)
        when (i % 4) {
            0 -> (c shr 16).toByte()
            1 -> (c shr 8).toByte()
            2 -> c.toByte()
            else -> -1
        }
    }

    @Test
    fun `greys fall on their own luma level, both colour differences in the middle`() {
        val rgba = picture(4, 2) { x, _ -> listOf(0x000000, 0x404040, 0x808080, 0xFFFFFF)[x] }
        val h = Histogram.of(rgba, 4, 2, step = 1)
        assertEquals(8, h.samples)
        assertEquals(2, h.luma[0])
        assertEquals(2, h.luma[0x40])
        assertEquals(2, h.luma[0x80])
        assertEquals(2, h.luma[255])
        assertEquals(8, h.cb[128])
        assertEquals(8, h.cr[128])
        assertEquals(0.25f, h.clippedBlack)
        assertEquals(0.25f, h.clippedWhite)
    }

    @Test
    fun `colours by BT 709's weights`() {
        // Pure red: Y' = 0.2126 · 255 = 54.2; Cr = (255 − 54.2) / 1.5748 + 128 = 255.5, at the top; Cb below 128.
        val red = Histogram.of(picture(1, 1) { _, _ -> 0xFF0000 }, 1, 1, step = 1)
        assertEquals(1, red.luma[54])
        assertEquals(1, red.cr[255])
        assertEquals(1, red.cb[99])
        // Pure blue: Y' = 18.4, Cb = (255 − 18.4) / 1.8556 + 128 = 255.5.
        val blue = Histogram.of(picture(1, 1) { _, _ -> 0x0000FF }, 1, 1, step = 1)
        assertEquals(1, blue.luma[18])
        assertEquals(1, blue.cb[255])
        // Green: Y' = 182.4; Cr = (0 − 182.4) / 1.5748 + 128 = 12.2.
        val green = Histogram.of(picture(1, 1) { _, _ -> 0x00FF00 }, 1, 1, step = 1)
        assertEquals(1, green.luma[182])
        assertEquals(1, green.cr[12])
    }

    @Test
    fun `a large picture is counted in steps, within the budget`() {
        assertEquals(1, Histogram.step(256, 256))
        val step = Histogram.step(3840, 2160)
        assertTrue((3840 / step) * (2160 / step) <= Histogram.MAX_SAMPLES)
        assertTrue((3840 / (step - 1)) * (2160 / (step - 1)) > Histogram.MAX_SAMPLES)
        val h = Histogram.of(ByteArray(1920 * 1080 * 4), 1920, 1080)
        assertTrue(h.samples <= Histogram.MAX_SAMPLES)
        assertEquals(h.samples, h.luma[0])
    }

    @Test
    fun `heights are logarithmic, a level that occurs at all at least one row`() {
        val counts = IntArray(256)
        counts[10] = 1000
        counts[20] = 31
        counts[30] = 1
        val heights = Histogram.heights(counts, 100)
        assertEquals(100, heights[10])
        assertEquals(0, heights[0])
        assertTrue(heights[30] >= 1)
        // ln(32) / ln(1001) of the rows: half, where a linear scale would give 3 %.
        assertEquals(50, heights[20])
        assertArrayEquals(IntArray(4), Histogram.heights(IntArray(4), 10))
    }
}
