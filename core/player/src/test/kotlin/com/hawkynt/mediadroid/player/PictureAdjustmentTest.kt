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
    fun `filters are neutral at zero and need the GL stage only when set`() {
        assertEquals(false, PictureAdjustment.Neutral.hasFilters)
        assertEquals(true, PictureAdjustment.Neutral.isColorNeutral)
        val sharp = PictureAdjustment(sharpness = 0.5f)
        assertEquals(true, sharp.hasFilters)
        assertEquals(true, sharp.isColorNeutral)
        assertEquals(false, sharp.isNeutral)
        assertEquals(true, PictureAdjustment(denoise = 2).hasFilters)
        assertEquals(true, PictureAdjustment(clarity = 0.2f).hasFilters)
        assertEquals(true, PictureAdjustment(deinterlace = PictureAdjustment.Deinterlace.AUTO).hasFilters)
        assertEquals(PictureAdjustment.Deinterlace.OFF, PictureAdjustment.Neutral.deinterlace)
        assertEquals(PictureAdjustment.Upscaler.STANDARD, PictureAdjustment.Neutral.upscaler)
        assertEquals(true, PictureAdjustment(upscaler = PictureAdjustment.Upscaler.FSR).hasFilters)
        assertEquals(true, PictureAdjustment(upscaler = PictureAdjustment.Upscaler.LANCZOS).hasFilters)
        // Colour and filters are separate: brightness alone needs no GL stage.
        assertEquals(false, PictureAdjustment(brightness = 0.3f).hasFilters)
        assertEquals(false, PictureAdjustment(brightness = 0.3f).isColorNeutral)
    }

    @Test
    fun `frame blending weighs the next frame by how far the clock is between the two`() {
        val off = PictureAdjustment.Interpolation.OFF
        val high = PictureAdjustment.Interpolation.HIGH
        val low = PictureAdjustment.Interpolation.LOW
        assertEquals(true, PictureAdjustment(interpolation = high).hasFilters)
        // Off: the frame changes at once, half-way.
        assertEquals(0f, off.weight(0.4f), 0f)
        assertEquals(1f, off.weight(0.6f), 0f)
        // High: a blend across the whole interval, even at its middle.
        assertEquals(0f, high.weight(0f), 1e-6f)
        assertEquals(0.5f, high.weight(0.5f), 1e-6f)
        assertEquals(1f, high.weight(1f), 1e-6f)
        // Low: only a quarter of the interval around the change blends.
        assertEquals(0f, low.weight(0.3f), 1e-6f)
        assertEquals(0.5f, low.weight(0.5f), 1e-6f)
        assertEquals(1f, low.weight(0.7f), 1e-6f)
        // Outside the interval, the frames themselves.
        assertEquals(0f, high.weight(-1f), 0f)
        assertEquals(1f, high.weight(2f), 0f)
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
    fun `full brightness lifts every channel by half the range`() {
        val m = PictureAdjustment(brightness = 1f).colorMatrix()
        assertEquals(128f, m[4], 1e-4f)
        assertEquals(128f, m[9], 1e-4f)
        assertEquals(128f, m[14], 1e-4f)
        assertEquals(0f, m[19], 1e-6f)
    }

    @Test
    fun `gamma above one brightens and below one darkens, like every video player`() {
        assertTrue(PictureAdjustment(gamma = 1.5f).colorMatrix()[4] > 0f)
        assertTrue(PictureAdjustment(gamma = 0.5f).colorMatrix()[4] < 0f)
    }

    @Test
    fun `matrix gamma lifts mid grey where the true curve puts it`() {
        // 0.5 ^ (1 / 4) = 0.8409: mid grey moves from 127.5 to 214.4.
        assertEquals(255f * (0.8409f - 0.5f), PictureAdjustment(gamma = 4f).colorMatrix()[4], 0.1f)
    }

    @Test
    fun `shader uniforms carry the same correction in unit range`() {
        val u = PictureAdjustment(brightness = 0.5f, gamma = 2f, contrast = 3f, saturation = 0f).shaderUniforms()
        assertEquals(64f / 255f, u.brightness, 1e-5f)
        assertEquals(0.5f, u.inverseGamma, 1e-6f)
        assertEquals(3f, u.contrast, 1e-6f)
        assertEquals(0f, u.saturation, 1e-6f)
    }

    @Test
    fun `any changed value is not neutral`() {
        assertFalse(PictureAdjustment(gamma = 1.2f).isNeutral)
        assertFalse(PictureAdjustment(brightness = -0.1f).isNeutral)
    }
}
