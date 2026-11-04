package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralEnlargingTest {
    @Test
    fun `waifu2x and Real-CUGAN are upscalers made by networks, carried in the app`() {
        val enlargers = PictureAdjustment.Upscaler.entries.filter { it.isEnlarger }
        assertEquals(listOf(PictureAdjustment.Upscaler.WAIFU2X, PictureAdjustment.Upscaler.REALCUGAN), enlargers)
        assertEquals("waifu2x", PictureAdjustment.Upscaler.WAIFU2X.label)
        assertEquals("Real-CUGAN", PictureAdjustment.Upscaler.REALCUGAN.label)
        assertEquals(2, NeuralEnlarging.SCALE)
    }

    @Test
    fun `a unit runs an upscaler right where its output shrunk back keeps close to its input`() {
        // Measured on the desktop: waifu2x 1.28, Real-CUGAN 6.47 (halos at hard edges); a shifted or swapped
        // picture is off by tens.
        assertTrue(NeuralEnlarging.rightEnough(1.28))
        assertTrue(NeuralEnlarging.rightEnough(6.47))
        assertFalse(NeuralEnlarging.rightEnough(25.0))
    }

    @Test
    fun `a 576p picture enlarged within a 25 fps frame keeps up`() {
        assertTrue(NeuralEnlarging.fastEnough(39.0))
        assertFalse(NeuralEnlarging.fastEnough(41.0))
    }
}
