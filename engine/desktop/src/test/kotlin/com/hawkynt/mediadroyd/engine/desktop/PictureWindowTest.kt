package com.hawkynt.mediadroyd.engine.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PictureWindowTest {

    @Test
    fun `the viewport counts from the window's bottom`() {
        // 1600 × 900, 160 pixels in from the left of a 1920 × 1080 window and 50 below its top: 130 above its foot.
        assertArrayEquals(
            intArrayOf(160, 130, 1600, 900),
            PictureWindow.viewport(intArrayOf(160, 50, 1600, 900), 1080),
        )
    }

    @Test
    fun `a picture overhanging the window keeps its overhang`() {
        assertArrayEquals(
            intArrayOf(-160, 0, 2240, 1080),
            PictureWindow.viewport(intArrayOf(-160, 0, 2240, 1080), 1080),
        )
    }
}
