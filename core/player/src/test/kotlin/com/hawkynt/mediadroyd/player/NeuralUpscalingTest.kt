package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralUpscalingTest {

    private fun covers(width: Int, height: Int) {
        val tiles = NeuralUpscaling.tiles(width, height)
        val hits = IntArray(width * height)
        for (t in tiles) {
            // The core a tile fills lies inside the tile with the overlap to spare, so seams come from its interior.
            assertTrue(t.coreX - t.x >= NeuralUpscaling.OVERLAP && t.coreY - t.y >= NeuralUpscaling.OVERLAP)
            assertTrue(t.x + NeuralUpscaling.TILE - (t.coreX + t.coreWidth) >= NeuralUpscaling.OVERLAP)
            assertTrue(t.y + NeuralUpscaling.TILE - (t.coreY + t.coreHeight) >= NeuralUpscaling.OVERLAP)
            for (y in t.coreY until t.coreY + t.coreHeight) for (x in t.coreX until t.coreX + t.coreWidth) hits[y * width + x]++
        }
        assertTrue("every pixel filled once", hits.all { it == 1 })
    }

    @Test
    fun `the tiles' cores fill a picture exactly once, PAL and odd sizes alike`() {
        covers(720, 576)
        covers(640, 360)
        covers(100, 50)
        covers(113, 241)
    }

    @Test
    fun `a PAL picture takes 42 tiles of 128 with 8 pixels to spare`() {
        assertEquals(42, NeuralUpscaling.tiles(720, 576).size)
    }

    @Test
    fun `the self-test passes a device that upscales a 576p picture within a 25 fps frame`() {
        assertTrue(NeuralUpscaling.fastEnough(msFor576p = 38.0))
        assertTrue(NeuralUpscaling.fastEnough(msFor576p = 40.0))
        assertFalse(NeuralUpscaling.fastEnough(msFor576p = 41.0))
        assertFalse(NeuralUpscaling.fastEnough(msFor576p = 1_640.0))
    }
}
