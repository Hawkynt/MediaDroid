package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `an in-between keeps up when it takes at most half a 25 fps frame`() {
        assertTrue(NeuralInterpolation.fastEnough(msPerInBetween = 19.0))
        assertTrue(NeuralInterpolation.fastEnough(msPerInBetween = 20.0))
        assertFalse(NeuralInterpolation.fastEnough(msPerInBetween = 21.0))
    }

    @Test
    fun `the self-test times 16 to 9 pictures from 480p to 1080p, smallest first`() {
        assertEquals(
            listOf<NeuralInterpolation.Size>(
                NeuralInterpolation.Size(854, 480),
                NeuralInterpolation.Size(1024, 576),
                NeuralInterpolation.Size(1280, 720),
                NeuralInterpolation.Size(1920, 1080),
            ),
            NeuralInterpolation.TEST_SIZES,
        )
    }

    private val device = mapOf(480 to 5.0, 576 to 7.0, 720 to 11.0, 1080 to 24.0)

    @Test
    fun `material the device keeps up with is interpolated at its own size`() {
        assertEquals(NeuralInterpolation.Size(1280, 720), NeuralInterpolation.workingSize(1280, 720, device))
        // PAL's 720 x 576 has fewer pixels than the 576p test picture: estimated from it, well within.
        assertEquals(NeuralInterpolation.Size(720, 576), NeuralInterpolation.workingSize(720, 576, device))
        // Smaller than anything tested: estimated from the smallest.
        assertEquals(NeuralInterpolation.Size(640, 360), NeuralInterpolation.workingSize(640, 360, device))
    }

    @Test
    fun `larger material is brought down to the largest size the device keeps up with, keeping its shape`() {
        // 1080p takes 24 ms here: the in-betweens are made at 720p and enlarged with the rest of the picture.
        assertEquals(NeuralInterpolation.Size(1280, 720), NeuralInterpolation.workingSize(1920, 1080, device))
        // A wide film keeps its shape at the pixels of the 720p test picture; sizes stay even.
        assertEquals(
            NeuralInterpolation.Size(1488, 620),
            NeuralInterpolation.workingSize(1920, 800, mapOf(480 to 5.0, 576 to 7.0, 720 to 15.0, 1080 to 30.0)),
        )
    }

    @Test
    fun `a device too slow for even the smallest size interpolates nothing`() {
        assertNull(NeuralInterpolation.workingSize(1920, 1080, mapOf(480 to 30.0)))
        assertNull(NeuralInterpolation.workingSize(640, 360, mapOf(480 to 60.0)))
        assertNull(NeuralInterpolation.workingSize(1920, 1080, emptyMap()))
    }

    @Test
    fun `a unit passes only where its in-between is near the true middle and nearer than a plain blend`() {
        // Errors: mean differences per value (0..255) to the frame truly halfway.
        assertTrue(NeuralInterpolation.rightEnough(networkError = 2.0, blendError = 30.0))
        assertFalse(NeuralInterpolation.rightEnough(networkError = 9.0, blendError = 30.0))
        assertFalse(NeuralInterpolation.rightEnough(networkError = 3.0, blendError = 2.0))
    }

    @Test
    fun `a passed self-test is kept per network as its unit and times, and read back`() {
        val passed = NeuralInterpolation.Passed("GPU", mapOf(480 to 5.5, 720 to 11.25))
        assertEquals("GPU|480=5.5;720=11.25", passed.encode())
        assertEquals(passed, NeuralInterpolation.Passed.decode("GPU|480=5.5;720=11.25"))
        assertNull(NeuralInterpolation.Passed.decode("GPU|"))
        assertNull(NeuralInterpolation.Passed.decode("nonsense"))
    }

    @Test
    fun `what the self-test measured is kept as text and read back`() {
        val text = NeuralInterpolation.encode(mapOf(480 to 5.5, 720 to 11.25))
        assertEquals("480=5.5;720=11.25", text)
        assertEquals(mapOf(480 to 5.5, 720 to 11.25), NeuralInterpolation.decode(text))
        assertEquals(emptyMap<Int, Double>(), NeuralInterpolation.decode(""))
        assertEquals(mapOf(480 to 5.5), NeuralInterpolation.decode("480=5.5;nonsense;=3;720=x"))
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
