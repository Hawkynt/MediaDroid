package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XbrzChoiceTest {
    @Test
    fun `xBRZ is an upscaler choice, and a filter the stage runs`() {
        assertEquals("xBRZ", PictureAdjustment.Upscaler.XBRZ.label)
        assertTrue(PictureAdjustment(upscaler = PictureAdjustment.Upscaler.XBRZ).hasFilters)
        assertTrue(PictureAdjustment(upscaler = PictureAdjustment.Upscaler.XBRZ).upscales)
    }
}
