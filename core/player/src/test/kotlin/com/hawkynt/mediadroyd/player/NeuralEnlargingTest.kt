package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralEnlargingTest {
    @Test
    fun `waifu2x, Real-CUGAN and RealSR are upscalers made by networks, RealSR four times the size`() {
        val enlargers = PictureAdjustment.Upscaler.entries.filter { it.isEnlarger }
        assertEquals(
            listOf(
                PictureAdjustment.Upscaler.WAIFU2X,
                PictureAdjustment.Upscaler.REALCUGAN,
                PictureAdjustment.Upscaler.REALSR,
            ),
            enlargers,
        )
        assertEquals("waifu2x", PictureAdjustment.Upscaler.WAIFU2X.label)
        assertEquals("Real-CUGAN", PictureAdjustment.Upscaler.REALCUGAN.label)
        assertEquals("RealSR", PictureAdjustment.Upscaler.REALSR.label)
        assertEquals(2, NeuralEnlarging.scale(PictureAdjustment.Upscaler.WAIFU2X))
        assertEquals(4, NeuralEnlarging.scale(PictureAdjustment.Upscaler.REALSR))
    }

    @Test
    fun `a unit runs an upscaler right where its output shrunk back keeps close to its input`() {
        // Measured on the desktop: waifu2x 1.28, Real-CUGAN 6.47 (halos at hard edges), RealSR 8.36; a shifted or
        // swapped picture is off by tens.
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
