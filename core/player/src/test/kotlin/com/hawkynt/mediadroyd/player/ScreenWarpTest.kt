package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenWarpTest {
    private fun assertAt(expected: Pair<Double, Double>, actual: Pair<Double, Double>?) {
        assertNotNull(actual)
        assertEquals(expected.first, actual!!.first, 1e-6)
        assertEquals(expected.second, actual.second, 1e-6)
    }

    @Test
    fun `an ordinary screen bends nothing`() {
        val warp = ScreenWarp.of(ScreenSetup.Default)
        assertTrue(warp.isIdentity)
        assertFalse(warp.wholeScreen)
        assertTrue(ScreenWarp.None.isIdentity)
        assertAt(0.3 to 0.7, warp.source(0.3, 0.7))
        // The same as GLSL's mat3 takes it: the identity, column by column.
        assertEquals(listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), warp.matrix.toList())
    }

    @Test
    fun `rear projection mirrors, a ceiling mount turns the picture upside down, both turn it round`() {
        assertAt(0.8 to 0.3, ScreenWarp.of(ScreenSetup(mirror = true)).source(0.2, 0.3))
        assertAt(0.2 to 0.7, ScreenWarp.of(ScreenSetup(flip = true)).source(0.2, 0.3))
        assertAt(0.8 to 0.7, ScreenWarp.of(ScreenSetup(mirror = true, flip = true)).source(0.2, 0.3))
        assertFalse(ScreenWarp.of(ScreenSetup(mirror = true)).isIdentity)
        assertFalse(ScreenWarp.of(ScreenSetup(flip = true)).wholeScreen)
    }

    @Test
    fun `a vertical keystone narrows the top edge about the middle, straight lines staying straight`() {
        val warp = ScreenWarp.of(ScreenSetup(keystoneVertical = 0.2f))
        assertTrue(warp.wholeScreen)
        // The bottom edge whole, the top edge 80 % of the width, centred.
        assertAt(0.0 to 0.0, warp.source(0.0, 0.0))
        assertAt(1.0 to 0.0, warp.source(1.0, 0.0))
        assertAt(0.0 to 1.0, warp.source(0.1, 1.0))
        assertAt(1.0 to 1.0, warp.source(0.9, 1.0))
        assertAt(0.5 to 1.0, warp.source(0.5, 1.0))
        // Beside the trapezoid there is no picture.
        assertNull(warp.source(0.05, 1.0))
        assertNull(warp.source(0.95, 0.999))
        // The picture's middle column stays the screen's middle column.
        for (y in listOf(0.1, 0.5, 0.9)) assertEquals(0.5, warp.source(0.5, y)!!.first, 1e-9)
    }

    @Test
    fun `a negative vertical keystone narrows the bottom, a horizontal one shortens a side`() {
        val bottom = ScreenWarp.of(ScreenSetup(keystoneVertical = -0.2f))
        assertAt(0.0 to 0.0, bottom.source(0.1, 0.0))
        assertAt(0.0 to 1.0, bottom.source(0.0, 1.0))
        val right = ScreenWarp.of(ScreenSetup(keystoneHorizontal = 0.3f))
        assertAt(1.0 to 0.0, right.source(1.0, 0.15))
        assertAt(1.0 to 1.0, right.source(1.0, 0.85))
        assertAt(0.0 to 0.0, right.source(0.0, 0.0))
        val left = ScreenWarp.of(ScreenSetup(keystoneHorizontal = -0.3f))
        assertAt(0.0 to 0.0, left.source(0.0, 0.15))
        assertAt(1.0 to 1.0, left.source(1.0, 1.0))
    }

    @Test
    fun `both keystones at once still land the picture's corners on the quadrilateral's`() {
        val corners = Keystone.corners(0.25f, -0.1f)
        val warp = ScreenWarp.of(ScreenSetup(keystoneVertical = 0.25f, keystoneHorizontal = -0.1f))
        val square = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0)
        for (i in 0..3) assertAt(square[i], warp.source(corners[2 * i], corners[2 * i + 1]))
        // A projective map: the middle of the picture is where the quadrilateral's diagonals cross.
        val m = Keystone.squareToQuad(corners)
        val w = m[6] * 0.5 + m[7] * 0.5 + m[8]
        assertAt(0.5 to 0.5, warp.source((m[0] * 0.5 + m[1] * 0.5 + m[2]) / w, (m[3] * 0.5 + m[4] * 0.5 + m[5]) / w))
    }

    @Test
    fun `the keystone takes the picture's place on the screen into account`() {
        // A 4:3 picture fitted on a 16:9 screen keystoned: it fills the middle three quarters.
        val place = ScreenRect.centred(1440, 1080, 1920, 1080)
        assertEquals(0.125f, place.x, 1e-6f)
        val warp = ScreenWarp.of(ScreenSetup(keystoneVertical = 0.2f), place)
        // The bottom edge, untouched, has the picture where it was; the bars beside it show nothing.
        assertAt(0.0 to 0.0, warp.source(0.125, 0.0))
        assertAt(1.0 to 0.0, warp.source(0.875, 0.0))
        assertNull(warp.source(0.1, 0.0))
        // At the top the screen's whole width is narrowed by a fifth about the middle, the picture with it.
        assertAt(0.0 to 1.0, warp.source(0.1 + 0.8 * 0.125, 1.0))
        // Without a keystone the picture's place is the view's to give: the warp leaves it alone.
        assertTrue(ScreenWarp.of(ScreenSetup.Default, place).isIdentity)
    }

    @Test
    fun `the panorama bends the picture after the map`() {
        val warp = ScreenWarp.of(ScreenSetup(mirror = true), panorama = 4f / 3f)
        val (u, _) = warp.source(0.2, 0.5)!!
        assertEquals(Panorama.source(0.8, (4f / 3f).toDouble()), u, 1e-9)
        assertFalse(ScreenWarp.of(ScreenSetup.Default, panorama = 4f / 3f).isIdentity)
    }

    @Test
    fun `the matrix is the map column by column, as the shader multiplies it`() {
        val warp = ScreenWarp.of(ScreenSetup(keystoneVertical = 0.3f, keystoneHorizontal = 0.1f, flip = true))
        val m = warp.matrix
        for ((x, y) in listOf(0.3 to 0.4, 0.5 to 0.5, 0.7 to 0.2)) {
            val w = m[2] * x + m[5] * y + m[8]
            val u = (m[0] * x + m[3] * y + m[6]) / w
            val v = (m[1] * x + m[4] * y + m[7]) / w
            val expected = warp.source(x.toDouble(), y.toDouble())!!
            assertEquals(expected.first, u.toDouble(), 1e-5)
            assertEquals(expected.second, v.toDouble(), 1e-5)
        }
    }

    @Test
    fun `a setup read back keeps its keystones within the limit`() {
        val setup = ScreenSetup.of("AUTO", mirror = true, keystoneVertical = 0.9f, keystoneHorizontal = -2f)
        assertEquals(Keystone.LIMIT, setup.keystoneVertical, 0f)
        assertEquals(-Keystone.LIMIT, setup.keystoneHorizontal, 0f)
        assertTrue(setup.mirror)
    }

    @Test
    fun `a keystone nudged by the remote moves half a percent a press, and no further than the limit`() {
        assertEquals(0.005f, Keystone.nudge(0f, 1), 1e-6f)
        assertEquals(-0.015f, Keystone.nudge(0f, -3), 1e-6f)
        assertEquals(0.1f, Keystone.nudge(0.095f, 1), 1e-6f)
        assertEquals(0.1f, Keystone.nudge(0.1003f, 0), 1e-6f)
        assertEquals(Keystone.LIMIT, Keystone.nudge(Keystone.LIMIT, 1), 1e-6f)
        assertEquals(-Keystone.LIMIT, Keystone.nudge(-0.398f, -5), 1e-6f)
    }
}
