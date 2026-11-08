package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureFitTest {
    private val wide = 16f / 9f
    private val tv = 4f / 3f

    @Test
    fun `fit keeps the whole picture, bars where the shapes differ`() {
        assertEquals(1440 to 1080, PictureLayout.size(PictureFit.FIT, tv, 1920, 1080))
        assertEquals(1920 to 817, PictureLayout.size(PictureFit.FIT, 2.35f, 1920, 1080))
    }

    @Test
    fun `fill covers the screen, the overhang cut`() {
        assertEquals(1920 to 1440, PictureLayout.size(PictureFit.FILL, tv, 1920, 1080))
        assertEquals(2538 to 1080, PictureLayout.size(PictureFit.FILL, 2.35f, 1920, 1080))
    }

    @Test
    fun `stretch takes the screen's size whatever the picture's shape`() {
        assertEquals(1920 to 1080, PictureLayout.size(PictureFit.STRETCH, tv, 1920, 1080))
    }

    @Test
    fun `panoramic widens a 4-3 picture to fill a 16-9 screen and no further`() {
        assertEquals(4f / 3f, PictureLayout.panorama(tv, wide), 1e-6f)
        assertEquals(1920 to 1080, PictureLayout.size(PictureFit.PANORAMIC, tv, 1920, 1080))
        // A picture already as wide as the screen, or wider, is fitted as it is.
        assertEquals(1f, PictureLayout.panorama(wide, wide), 0f)
        assertEquals(1f, PictureLayout.panorama(2.35f, wide), 0f)
        assertEquals(1920 to 817, PictureLayout.size(PictureFit.PANORAMIC, 2.35f, 1920, 1080))
        // 4:3 on a 21:9 screen: widened by a third at most, the rest left as bars.
        assertEquals(PictureLayout.MAX_PANORAMA, PictureLayout.panorama(tv, 21f / 9f), 0f)
        assertEquals(1920 to 1080, PictureLayout.size(PictureFit.PANORAMIC, tv, 2520, 1080))
        // 16:10 on 16:9: just the little that fills it.
        assertEquals(wide / 1.6f, PictureLayout.panorama(1.6f, wide), 1e-6f)
    }

    @Test
    fun `the panorama keeps its edges and its middle where they were`() {
        for (ratio in listOf(1.0, 1.1, 4.0 / 3.0)) {
            assertEquals(0.0, Panorama.source(0.0, ratio), 1e-9)
            assertEquals(0.5, Panorama.source(0.5, ratio), 1e-9)
            assertEquals(1.0, Panorama.source(1.0, ratio), 1e-9)
        }
    }

    @Test
    fun `without widening the panorama changes nothing`() {
        for (x in listOf(0.1, 0.25, 0.7)) assertEquals(x, Panorama.source(x, 1.0), 1e-12)
    }

    @Test
    fun `the middle stays in shape and the sides take the stretch`() {
        val ratio = 4.0 / 3.0
        val h = 1e-6
        fun slope(x: Double) = (Panorama.source(x + h, ratio) - Panorama.source(x - h, ratio)) / (2 * h)
        // In the middle one step across the screen is as much of the picture as its shape asks: ratio times the
        // even share, so nothing is widened there.
        assertEquals(ratio, slope(0.5), 1e-5)
        assertEquals(1.0, Panorama.stretchAt(0.5, ratio), 1e-12)
        // At the edges 4:3 on 16:9 is stretched twice as wide as the middle.
        assertEquals(ratio / 2.0, slope(1e-5), 1e-4)
        assertEquals(2.0, Panorama.stretchAt(0.0, ratio), 1e-12)
        assertEquals(2.0, Panorama.stretchAt(1.0, ratio), 1e-12)
    }

    @Test
    fun `the panorama runs one way, mirrored about the middle`() {
        val ratio = 4.0 / 3.0
        var last = -1.0
        for (i in 0..1000) {
            val x = i / 1000.0
            val s = Panorama.source(x, ratio)
            assertTrue(s > last)
            last = s
            assertEquals(1.0 - s, Panorama.source(1.0 - x, ratio), 1e-9)
        }
    }

    @Test
    fun `the way back lands where the stretch put it`() {
        val ratio = 1.25
        val c = 3 * (ratio - 1)
        for (i in 0..20) {
            val s = -1.0 + i / 10.0
            val shownAt = (s + c * s * s * s / 3) / ratio
            assertEquals((s + 1) / 2, Panorama.source((shownAt + 1) / 2, ratio), 1e-9)
        }
    }
}
