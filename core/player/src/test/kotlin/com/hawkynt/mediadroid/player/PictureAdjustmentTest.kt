package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureAdjustmentTest {

    @Test
    fun `neutral picture leaves every pixel alone`() {
        assertTrue(PictureAdjustment.Neutral.isNeutral)
        assertTrue(PictureAdjustment.Neutral.isColorNeutral)
    }

    @Test
    fun `filters are neutral at zero and need the GL stage only when set`() {
        assertEquals(false, PictureAdjustment.Neutral.hasFilters)
        assertEquals(true, PictureAdjustment.Neutral.isColorNeutral)
        val sharp = PictureAdjustment(sharpness = 0.5f)
        assertEquals(true, sharp.hasFilters)
        assertEquals(true, sharp.isColorNeutral)
        assertEquals(false, sharp.isNeutral)
        assertEquals(true, PictureAdjustment(noise = -0.5f).hasFilters)
        assertEquals(true, PictureAdjustment(noise = 0.3f).hasFilters)
        assertEquals(true, PictureAdjustment(clarity = 0.2f).hasFilters)
        assertEquals(true, PictureAdjustment(deinterlace = PictureAdjustment.Deinterlace.AUTO).hasFilters)
        assertEquals(PictureAdjustment.Deinterlace.OFF, PictureAdjustment.Neutral.deinterlace)
        assertEquals(PictureAdjustment.Upscaler.STANDARD, PictureAdjustment.Neutral.upscaler)
        assertEquals(true, PictureAdjustment(upscaler = PictureAdjustment.Upscaler.FSR).hasFilters)
        assertEquals(true, PictureAdjustment(upscaler = PictureAdjustment.Upscaler.LANCZOS).hasFilters)
        // The video's own view no longer colours it (a SurfaceView cannot be): brightness alone needs the stage.
        assertEquals(true, PictureAdjustment(brightness = 0.3f).hasFilters)
        assertEquals(true, PictureAdjustment(gamma = 1.2f).hasFilters)
        assertEquals(false, PictureAdjustment(brightness = 0.3f).isColorNeutral)
        // The bars are found in the stage's own samples of the picture and cropped in its first pass.
        assertEquals(true, PictureAdjustment(cropBlackBars = true).hasFilters)
        assertEquals(false, PictureAdjustment.Neutral.cropBlackBars)
    }

    @Test
    fun `Snapdragon GSR is a one-pass shader upscaler, no network to fetch`() {
        val gsr = PictureAdjustment.Upscaler.GSR
        assertEquals("GSR", gsr.label)
        assertFalse(gsr.isEnlarger)
        assertEquals(null, NeuralModels.download(gsr))
        assertTrue(PictureAdjustment(upscaler = gsr).hasFilters)
    }

    @Test
    fun `ACNet is a shader network like Anime4K, carried in the app`() {
        val acnet = PictureAdjustment.Upscaler.ACNET
        assertEquals("ACNet", acnet.label)
        assertFalse(acnet.isEnlarger)
        assertEquals(null, NeuralModels.download(acnet))
        assertTrue(PictureAdjustment(upscaler = acnet).hasFilters)
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
    fun `one noise slider removes noise to the left and adds grain to the right`() {
        val clean = PictureAdjustment(noise = -1f)
        assertEquals(2f, clean.denoiseKernel()!!.radius, 0f)
        assertEquals(0.16f, clean.denoiseKernel()!!.similarity, 1e-6f)
        val light = PictureAdjustment(noise = -0.2f).denoiseKernel()!!
        assertEquals(1f, light.radius, 0f)
        assertEquals(0.064f, light.similarity, 1e-6f)
        assertEquals(0f, clean.grain, 0f)
        assertEquals(null, PictureAdjustment(noise = 0.5f).denoiseKernel())
        assertEquals(0.04f, PictureAdjustment(noise = 0.5f).grain, 1e-6f)
        assertEquals(null, PictureAdjustment.Neutral.denoiseKernel())
        // The three old denoise steps land on the slider's left third, two thirds and end.
        assertEquals(-1f / 3f, PictureAdjustment.noiseOfLevel(1), 1e-6f)
        assertEquals(-1f, PictureAdjustment.noiseOfLevel(3), 1e-6f)
        assertEquals(0f, PictureAdjustment.noiseOfLevel(0), 0f)
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
