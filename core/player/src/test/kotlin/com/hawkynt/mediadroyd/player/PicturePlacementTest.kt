package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PicturePlacementTest {

    @Test
    fun `a picture drawn at its shown size is centred in the window`() {
        assertArrayEquals(
            intArrayOf(160, 0, 1600, 900),
            PicturePlacement.shown(1920, 900, 1600, 900, 1600, 900, 0, 0),
        )
    }

    @Test
    fun `a picture with no shown size is fitted by its own shape`() {
        // 1280 × 720 into 1920 × 1200: scaled by 1.5 to 1920 × 1080, 60 pixels above and below.
        assertArrayEquals(
            intArrayOf(0, 60, 1920, 1080),
            PicturePlacement.shown(1920, 1200, 0, 0, 1280, 720, 0, 0),
        )
    }

    @Test
    fun `a filled picture overhangs the window evenly`() {
        assertArrayEquals(
            intArrayOf(-160, 0, 2240, 1080),
            PicturePlacement.shown(1920, 1080, 2240, 1080, 2240, 1080, 0, 0),
        )
    }

    @Test
    fun `burn-in drift moves the picture`() {
        assertArrayEquals(
            intArrayOf(163, -2, 1600, 900),
            PicturePlacement.shown(1920, 900, 1600, 900, 1600, 900, 3, -2),
        )
    }

    @Test
    fun `the overlay covers the window's content, the picture window its pixels`() {
        // A window at 100, 50, 1280 × 800 with a 30-pixel title bar and 1-pixel borders, at scale 1.5.
        val overlay =
            PicturePlacement.overlay(100, 50, 1280, 800, top = 30, left = 1, bottom = 1, right = 1, scale = 1.5)
        assertEquals(PicturePlacement.Overlay(101, 80, 1278, 769, 1917, 1154), overlay)
    }

    @Test
    fun `a full-screen window has no insets`() {
        val overlay = PicturePlacement.overlay(0, 0, 3840, 2160, 0, 0, 0, 0, scale = 1.0)
        assertEquals(PicturePlacement.Overlay(0, 0, 3840, 2160, 3840, 2160), overlay)
    }

    @Test
    fun `a window smaller than its insets covers nothing`() {
        val overlay = PicturePlacement.overlay(0, 0, 10, 10, 30, 1, 1, 1, scale = 2.0)
        assertEquals(8, overlay.width)
        assertEquals(0, overlay.height)
        assertEquals(0, overlay.pixelHeight)
        assertTrue(overlay.isEmpty)
        assertFalse(PicturePlacement.overlay(0, 0, 10, 40, 30, 1, 1, 1, 1.0).isEmpty)
    }
}
