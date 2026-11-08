package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Anime4kChoiceTest {
    @Test
    fun `Anime4K is an upscaler choice, and a filter the stage runs`() {
        assertEquals("Anime4K", PictureAdjustment.Upscaler.ANIME4K.label)
        assertTrue(PictureAdjustment(upscaler = PictureAdjustment.Upscaler.ANIME4K).hasFilters)
    }
}
