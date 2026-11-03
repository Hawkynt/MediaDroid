package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralChoiceTest {
    @Test
    fun `Real-ESRGAN is an upscaler choice, and a filter the stage runs`() {
        assertEquals("Real-ESRGAN", PictureAdjustment.Upscaler.NEURAL.label)
        assertTrue(PictureAdjustment(upscaler = PictureAdjustment.Upscaler.NEURAL).hasFilters)
        assertTrue(PictureAdjustment(upscaler = PictureAdjustment.Upscaler.NEURAL).upscales)
    }
}
