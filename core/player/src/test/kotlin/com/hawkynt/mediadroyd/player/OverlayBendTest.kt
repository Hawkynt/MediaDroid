package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayBendTest {
    private fun apply(m: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val w = m[6] * x + m[7] * y + m[8]
        return (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
    }

    private fun assertAt(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 1e-3f)
        assertEquals(expected.second, actual.second, 1e-3f)
    }

    @Test
    fun `an ordinary screen bends no overlay, nor does the panorama`() {
        assertFalse(ScreenWarp.None.bendsOverlays)
        assertFalse(ScreenWarp.of(ScreenSetup.Default, panorama = 4f / 3f).bendsOverlays)
        assertEquals(
            listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            ScreenWarp.None.overlayMatrix(1920f, 1080f).toList(),
        )
    }

    @Test
    fun `rear projection mirrors the overlays across, a ceiling mount turns them upside down`() {
        val mirror = ScreenWarp.of(ScreenSetup(mirror = true)).overlayMatrix(1920f, 1080f)
        assertAt(1820f to 900f, apply(mirror, 100f, 900f))
        val flip = ScreenWarp.of(ScreenSetup(flip = true)).overlayMatrix(1920f, 1080f)
        assertAt(100f to 180f, apply(flip, 100f, 900f))
    }

    @Test
    fun `a keystone takes the overlays into the trapezoid the picture is drawn into`() {
        // The top edge narrowed by a fifth: an overlay at the screen's top left corner lands a tenth of the width in.
        val warp = ScreenWarp.of(ScreenSetup(keystoneVertical = 0.2f), ScreenRect.centred(1440, 1080, 1920, 1080))
        assertTrue(warp.bendsOverlays)
        val m = warp.overlayMatrix(1920f, 1080f)
        assertAt(192f to 0f, apply(m, 0f, 0f))
        assertAt(1920f to 1080f, apply(m, 1920f, 1080f))
        // There and back again.
        val back = warp.overlayBackMatrix(1920f, 1080f)
        val (x, y) = apply(m, 700f, 300f)
        assertAt(700f to 300f, apply(back, x, y))
        // Where the picture's own map sends that point is where the overlay laid out for it now lands: on the picture.
        val (u, v) = warp.source((x / 1920.0), 1.0 - y / 1080.0)!!
        assertEquals((700.0 / 1920 - 0.125) / 0.75, u, 1e-3)
        assertEquals(1.0 - 300.0 / 1080, v, 1e-3)
    }

    @Test
    fun `libass's subtitles are bent as the picture, read the right way round on a rear-projection screen`() {
        // Two pixels side by side at 10 and 11 of a 100-wide frame: red, then blue.
        val picture = AssPicture(10, 5, 2, 1, intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()))
        val bent = picture.bent(ScreenWarp.of(ScreenSetup(mirror = true)), 100, 20)
        assertEquals(88, bent.x)
        assertEquals(5, bent.y)
        assertEquals(2, bent.width)
        assertEquals(listOf(0xFF0000FF.toInt(), 0xFFFF0000.toInt()), bent.pixels.toList())
        val flipped = picture.bent(ScreenWarp.of(ScreenSetup(flip = true)), 100, 20)
        assertEquals(14, flipped.y)
        assertEquals(10, flipped.x)
        // Nothing to bend: the very same picture.
        assertSame(picture, picture.bent(ScreenWarp.None, 100, 20))
        assertSame(AssPicture.EMPTY, AssPicture.EMPTY.bent(ScreenWarp.of(ScreenSetup(mirror = true)), 100, 20))
    }

    @Test
    fun `keystoned subtitles keep their colour, only their shape follows the trapezoid`() {
        val white = AssPicture(0, 15, 100, 5, IntArray(500) { 0xFFFFFFFF.toInt() })
        val bent = white.bent(ScreenWarp.of(ScreenSetup(keystoneVertical = 0.2f)), 100, 20)
        // Near the bottom the picture is nearly whole: the line spans it, its middle white as it was.
        assertEquals(0xFFFFFFFF.toInt(), bent.pixels[(17 - bent.y) * bent.width + (50 - bent.x)])
        assertTrue(bent.x <= 2)
    }
}
