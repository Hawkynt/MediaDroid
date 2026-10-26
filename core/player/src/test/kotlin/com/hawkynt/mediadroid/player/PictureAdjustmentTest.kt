package com.hawkynt.mediadroid.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureAdjustmentTest {

    private val identity = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    @Test
    fun `neutral picture leaves every pixel alone`() {
        assertTrue(PictureAdjustment.Neutral.isNeutral)
        assertArrayEquals(identity, PictureAdjustment.Neutral.colorMatrix(), 1e-5f)
    }

    @Test
    fun `zero saturation turns every row into the same luma weights`() {
        val m = PictureAdjustment(saturation = 0f).colorMatrix()
        val luma = floatArrayOf(0.213f, 0.715f, 0.072f)
        for (row in 0..2) {
            assertArrayEquals(luma, m.copyOfRange(row * 5, row * 5 + 3), 1e-5f)
        }
    }

    @Test
    fun `contrast scales around mid grey`() {
        val m = PictureAdjustment(contrast = 2f).colorMatrix()
        // 127.5 stays 127.5: 2 * 127.5 + offset.
        assertEquals(2f, m[0], 1e-5f)
        assertEquals(-127.5f, m[4], 1e-4f)
    }

    @Test
    fun `brightness lifts every channel by the same offset`() {
        val m = PictureAdjustment(brightness = 1f).colorMatrix()
        assertEquals(80f, m[4], 1e-4f)
        assertEquals(80f, m[9], 1e-4f)
        assertEquals(80f, m[14], 1e-4f)
        assertEquals(0f, m[19], 1e-6f)
    }

    @Test
    fun `gamma above one darkens and below one lifts`() {
        assertTrue(PictureAdjustment(gamma = 1.5f).colorMatrix()[4] < 0f)
        assertTrue(PictureAdjustment(gamma = 0.5f).colorMatrix()[4] > 0f)
    }

    @Test
    fun `any changed value is not neutral`() {
        assertFalse(PictureAdjustment(gamma = 1.2f).isNeutral)
        assertFalse(PictureAdjustment(brightness = -0.1f).isNeutral)
    }
}
