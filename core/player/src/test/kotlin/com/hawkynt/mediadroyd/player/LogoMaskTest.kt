package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logo masking's Blur against FFmpeg's own delogo filter (GPL, run as an oracle), byte for byte: `resources/delogo/`
 * holds a picture with a half-transparent box in its corner and FFmpeg's output for several rectangles
 * (`make-expected.sh`), as three planes each filtered alike.
 */
class LogoMaskTest {

    private val w = 96
    private val h = 64

    private fun planes(name: String): List<ByteArray> {
        val data = requireNotNull(javaClass.getResourceAsStream("/delogo/$name")) { name }.use { it.readBytes() }
        return (0..2).map { data.copyOfRange(it * w * h, (it + 1) * w * h) }
    }

    private val input = planes("input.yuv")

    private fun check(x: Int, y: Int, width: Int, height: Int) {
        val expected = planes("$x-$y-$width-$height.yuv")
        val rect = PictureCrop(x, y, width, height)
        assertTrue("FFmpeg left the picture as it was", (0..2).any { !expected[it].contentEquals(input[it]) })
        for (p in 0..2) {
            assertArrayEquals("plane $p", expected[p], LogoMask.apply(input[p], w, h, rect, LogoMask.Mode.BLUR))
        }
    }

    @Test
    fun `the logo's own rectangle agrees with FFmpeg's delogo`() = check(69, 5, 20, 12)

    @Test
    fun `a rectangle one pixel in from the top left corner agrees`() = check(1, 1, 30, 18)

    @Test
    fun `a rectangle reaching the right and bottom edges' last pixel but one agrees`() = check(60, 40, 35, 23)

    @Test
    fun `a tiny rectangle agrees`() = check(40, 20, 3, 2)

    @Test
    fun `Grey fills the rectangle with the middle grey, Dim keeps it no brighter than Blur fills it`() {
        val rect = PictureCrop(69, 5, 20, 12)
        val grey = LogoMask.apply(input[0], w, h, rect, LogoMask.Mode.GREY)
        val blur = LogoMask.apply(input[0], w, h, rect, LogoMask.Mode.BLUR)
        val dim = LogoMask.apply(input[0], w, h, rect, LogoMask.Mode.DIM)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val inside = x in 69 until 89 && y in 5 until 17
            if (!inside) {
                assertEquals(input[0][i], grey[i])
                assertEquals(input[0][i], dim[i])
            } else {
                assertEquals(LogoMask.GREY.toByte(), grey[i])
                val v = input[0][i].toInt() and 0xFF
                assertEquals(minOf(v, blur[i].toInt() and 0xFF), dim[i].toInt() and 0xFF)
            }
        }
    }

    @Test
    fun `the rectangle sits in its corner, a pixel inside the picture`() {
        val mask = LogoMask(LogoMask.Corner.TOP_RIGHT, 0.2f, LogoMask.Mode.BLUR)
        assertEquals(PictureCrop(1920 - 1 - 384, 1, 384, 230), mask.rectangle(1920, 1080))
        val bottomLeft = mask.copy(corner = LogoMask.Corner.BOTTOM_LEFT).rectangle(720, 576)
        assertEquals(1, bottomLeft.left)
        assertEquals(576 - 1, bottomLeft.top + bottomLeft.height)
    }
}
