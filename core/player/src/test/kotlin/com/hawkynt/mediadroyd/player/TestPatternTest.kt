package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestPatternTest {
    private val w = 1920
    private val h = 1080

    private fun TestPattern.at(x: Int, y: Int) = colour(x, y, w, h)

    @Test
    fun `levels are given in percent of the full range`() {
        assertEquals(0, TestPattern.level(0.0))
        assertEquals(5, TestPattern.level(2.0))
        assertEquals(10, TestPattern.level(4.0))
        assertEquals(191, TestPattern.level(75.0))
        assertEquals(255, TestPattern.level(100.0))
        // Below black has no code in full range: black.
        assertEquals(0, TestPattern.level(-2.0))
        assertEquals(0x666666, TestPattern.grey(40.0))
    }

    @Test
    fun `black and white levels put 2 and 4 percent on black, 96 and 98 percent on white`() {
        val p = TestPattern.LEVELS
        assertEquals(0, p.at(10, 540))
        assertEquals(TestPattern.grey(2.0), p.at(300, 540))
        assertEquals(TestPattern.grey(4.0), p.at(540, 540))
        assertEquals(TestPattern.grey(100.0), p.at(1250, 300))
        assertEquals(TestPattern.grey(96.0), p.at(1380, 540))
        assertEquals(TestPattern.grey(98.0), p.at(1620, 540))
    }

    @Test
    fun `grey steps run from black to white in tens, the ramp beneath smooth`() {
        val p = TestPattern.GREYS
        for (step in 0..10) {
            val x = (step * w + w / 2) / 11
            assertEquals(TestPattern.grey(step * 10.0), p.at(x, 100))
        }
        assertEquals(0, p.at(0, h - 1))
        assertEquals(0xFFFFFF, p.at(w - 1, h - 1))
        assertEquals(TestPattern.grey(50.0 * 2 * 960 / (w - 1)), p.at(960, h - 1))
    }

    @Test
    fun `SD bars are EBU 100-0-75-0`() {
        val bars = (0 until 8).map { TestPattern.BARS_SD.at(it * w / 8 + 10, h / 2) }
        assertEquals(
            listOf(0xFFFFFF, 0xBFBF00, 0x00BFBF, 0x00BF00, 0xBF00BF, 0xBF0000, 0x0000BF, 0x000000),
            bars,
        )
    }

    @Test
    fun `HD bars follow RP 219's rows`() {
        val p = TestPattern.BARS_HD
        // Pattern 1: 40 % grey sides, 75 % bars.
        assertEquals(TestPattern.grey(40.0), p.at(10, 100))
        assertEquals(TestPattern.grey(75.0), p.at(300, 100))
        assertEquals(0x0000BF, p.at(w - 250, 100))
        // Pattern 2: 100 % cyan and blue at the sides, 75 % white between.
        assertEquals(0x00FFFF, p.at(10, h * 15 / 24))
        assertEquals(0x0000FF, p.at(w - 10, h * 15 / 24))
        // Pattern 3: 100 % yellow and red at the sides, a ramp between.
        assertEquals(0xFFFF00, p.at(10, h * 17 / 24))
        assertEquals(0xFF0000, p.at(w - 10, h * 17 / 24))
        assertTrue(p.at(400, h * 17 / 24) < p.at(1500, h * 17 / 24))
        // Pattern 4: 15 % grey, black, 100 % white, and the PLUGE's +2 % and +4 %.
        val bottom = h - 10
        assertEquals(TestPattern.grey(15.0), p.at(10, bottom))
        assertEquals(0xFFFFFF, p.at(240 + 206 * 2, bottom))
        val bar = (w - 480) / 7.0
        // RP 219: after the white, black for 5/6 of a bar, then −2 % (black here), black, +2 %, black, +4 %, a third
        // of a bar each, then black.
        assertEquals(0, p.at((240 + bar * 4.5).toInt(), bottom))
        assertEquals(TestPattern.grey(2.0), p.at((240 + bar * 5.2).toInt(), bottom))
        assertEquals(0, p.at((240 + bar * 5.5).toInt(), bottom))
        assertEquals(TestPattern.grey(4.0), p.at((240 + bar * 5.8).toInt(), bottom))
        assertEquals(0, p.at((240 + bar * 6.5).toInt(), bottom))
    }

    @Test
    fun `the grid frames the screen, crosses its middle and has square cells and a round circle`() {
        val p = TestPattern.GRID
        assertEquals(0xFFFFFF, p.at(0, 500))
        assertEquals(0xFFFFFF, p.at(w - 1, 500))
        assertEquals(0xFFFFFF, p.at(500, 0))
        assertEquals(0xFFFFFF, p.at(500, h - 1))
        // The middle cross; and a line every twelfth of the height, both ways, from the middle.
        assertEquals(0xFFFFFF, p.at(w / 2, h / 2 + 10))
        val cell = h / 12
        assertTrue(p.at(w / 2 + cell, 300) != 0)
        assertTrue(p.at(300, h / 2 + cell) != 0)
        assertEquals(0, p.at(w / 2 + cell / 2, h / 2 + cell / 3))
        // The circle: its radius 0.45 of the height, the same across and up.
        val r = h * 0.45
        for (degrees in listOf(20, 45, 70, 200, 300)) {
            val a = Math.toRadians(degrees.toDouble())
            val x = ((w - 1) / 2.0 + r * kotlin.math.cos(a)).toInt()
            val y = ((h - 1) / 2.0 + r * kotlin.math.sin(a)).toInt()
            val near = (-1..1).flatMap { dx -> (-1..1).map { dy -> p.at(x + dx, y + dy) } }
            assertTrue("circle at $degrees°", 0xFFFFFF in near)
        }
        // Symmetric about the middle.
        for ((x, y) in listOf(100 to 37, 811 to 402, 1203 to 77)) {
            assertEquals(p.at(x, y), p.at(w - 1 - x, h - 1 - y))
        }
    }

    @Test
    fun `gamma bands hold half the light in lines beside the grey each gamma shows as half`() {
        val p = TestPattern.GAMMA
        // 255 · 0.5^(1/γ), rounded.
        assertEquals(listOf(174, 180, 186, 191, 195), TestPattern.GAMMAS.map {
            p.at(w - 10, ((TestPattern.GAMMAS.indexOf(it) + 0.5) * h / 5).toInt()) and 255
        })
        assertEquals(0xFFFFFF, p.at(10, 100))
        assertEquals(0, p.at(10, 101))
    }

    @Test
    fun `a pattern renders as RGBA rows from the top, opaque`() {
        val rgba = TestPattern.BARS_SD.render(16, 4)
        assertEquals(16 * 4 * 4, rgba.size)
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), rgba.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0, 0, 0, -1), rgba.copyOfRange(15 * 4, 16 * 4))
        assertTrue((0 until 16 * 4).all { rgba[it * 4 + 3] == (-1).toByte() })
    }

    @Test
    fun `patterns show with the household's colour, not with what would change the pattern`() {
        val household = PictureAdjustment(
            brightness = 0.1f, contrast = 1.2f, sharpness = 0.3f,
            deinterlace = PictureAdjustment.Deinterlace.BLEND, cropBlackBars = true,
            interpolation = PictureAdjustment.Interpolation.MEDIUM, aspect = AspectOverride.FOUR_THREE,
            logo = LogoMask(LogoMask.Corner.TOP_RIGHT, 0.1f, LogoMask.Mode.BLUR),
        )
        val shown = TestPattern.pictureFor(household)
        assertEquals(0.1f, shown.brightness)
        assertEquals(1.2f, shown.contrast)
        assertEquals(0.3f, shown.sharpness)
        assertEquals(PictureAdjustment.Deinterlace.OFF, shown.deinterlace)
        assertFalse(shown.cropBlackBars)
        assertEquals(PictureAdjustment.Interpolation.OFF, shown.interpolation)
        assertEquals(AspectOverride.AUTO, shown.aspect)
        assertEquals(null, shown.logo)
    }

    @Test
    fun `a pattern is a still picture, so nothing that weighs frames before or after it, nothing that colours it in`() {
        val household = PictureAdjustment(
            temporalDenoise = 0.6f,
            dotCrawl = 0.8f,
            colorize = PictureAdjustment.Colorize.entries.last(),
        )
        val shown = TestPattern.pictureFor(household)
        assertEquals(0f, shown.temporalDenoise)
        assertEquals(0f, shown.dotCrawl)
        assertEquals(PictureAdjustment.Colorize.OFF, shown.colorize)
    }
}
