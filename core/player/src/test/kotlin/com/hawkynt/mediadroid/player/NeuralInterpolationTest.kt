package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralInterpolationTest {
    @Test
    fun `RIFE is a smooth-motion choice the stage runs, built along the motion`() {
        assertEquals("RIFE", PictureAdjustment.Interpolation.RIFE.label)
        assertTrue(PictureAdjustment.Interpolation.RIFE.isNeural)
        assertFalse(PictureAdjustment.Interpolation.MOTION.isNeural)
        assertTrue(PictureAdjustment(interpolation = PictureAdjustment.Interpolation.RIFE).hasFilters)
    }

    @Test
    fun `the self-test passes a device that makes a 576p in-between within half a 25 fps frame`() {
        assertTrue(NeuralInterpolation.fastEnough(msPerInBetween = 19.0))
        assertTrue(NeuralInterpolation.fastEnough(msPerInBetween = 20.0))
        assertFalse(NeuralInterpolation.fastEnough(msPerInBetween = 21.0))
    }

    @Test
    fun `between two frames the picture runs through the in-between at the half`() {
        // Before the half: the earlier frame towards the in-between; after it: the in-between towards the later.
        assertEquals(NeuralInterpolation.Mix(towardsMiddle = true, weight = 0.5f), NeuralInterpolation.mix(0.25f))
        assertEquals(NeuralInterpolation.Mix(towardsMiddle = false, weight = 0.5f), NeuralInterpolation.mix(0.75f))
        assertEquals(NeuralInterpolation.Mix(towardsMiddle = false, weight = 0f), NeuralInterpolation.mix(0.5f))
        assertEquals(NeuralInterpolation.Mix(towardsMiddle = false, weight = 1f), NeuralInterpolation.mix(1f))
    }
}
