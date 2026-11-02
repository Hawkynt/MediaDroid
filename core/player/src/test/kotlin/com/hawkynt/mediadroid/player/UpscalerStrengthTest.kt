package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Test

class UpscalerStrengthTest {
    @Test
    fun `the upscaler shows alone by default`() {
        assertEquals(1f, PictureAdjustment.Neutral.upscalerStrength, 0f)
        assertEquals(0f, PictureAdjustment(upscaler = PictureAdjustment.Upscaler.FSR).plainShare, 0f)
    }

    @Test
    fun `below full strength the plain picture is mixed in, all of it at zero`() {
        val picture = PictureAdjustment(upscaler = PictureAdjustment.Upscaler.NIS, upscalerStrength = 0.25f)
        assertEquals(0.75f, picture.plainShare, 1e-6f)
        assertEquals(1f, picture.copy(upscalerStrength = 0f).plainShare, 0f)
        // Out of range is taken at its bound.
        assertEquals(0f, picture.copy(upscalerStrength = 1.5f).plainShare, 0f)
    }

    @Test
    fun `at zero strength the upscaler need not run`() {
        val off = PictureAdjustment(upscaler = PictureAdjustment.Upscaler.LANCZOS, upscalerStrength = 0f)
        assertEquals(false, off.upscales)
        assertEquals(true, off.copy(upscalerStrength = 0.1f).upscales)
        assertEquals(false, PictureAdjustment.Neutral.upscales)
    }
}
