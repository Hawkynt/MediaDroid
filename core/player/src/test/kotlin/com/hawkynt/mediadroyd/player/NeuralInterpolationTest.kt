package com.hawkynt.mediadroyd.player

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
    fun `an in-between keeps up when it takes at most three quarters of a 25 fps frame`() {
        assertTrue(NeuralInterpolation.fastEnough(msPerInBetween = 21.0))
        assertTrue(NeuralInterpolation.fastEnough(msPerInBetween = 30.0))
        assertFalse(NeuralInterpolation.fastEnough(msPerInBetween = 31.0))
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

    @Test
    fun `RIFE and IFRNet hand back their flow, so their self-test starts at 120p, the other networks at 480p`() {
        assertTrue(NeuralInterpolation.handsFlow(PictureAdjustment.Interpolation.RIFE))
        assertTrue(NeuralInterpolation.handsFlow(PictureAdjustment.Interpolation.IFRNET))
        assertFalse(NeuralInterpolation.handsFlow(PictureAdjustment.Interpolation.DAIN))
        assertEquals(
            listOf(
                NeuralInterpolation.Size(214, 120),
                NeuralInterpolation.Size(320, 180),
                NeuralInterpolation.Size(426, 240),
                NeuralInterpolation.Size(640, 360),
            ) + NeuralInterpolation.TEST_SIZES,
            NeuralInterpolation.testSizes(PictureAdjustment.Interpolation.RIFE),
        )
        val cain = NeuralInterpolation.testSizes(PictureAdjustment.Interpolation.CAIN)
        assertEquals(NeuralInterpolation.TEST_SIZES, cain)
    }

    @Test
    fun `a flow worked out at 240p serves 1080p material where the device keeps up with nothing larger`() {
        val slow = mapOf(240 to 13.5, 360 to 37.5)
        assertEquals(NeuralInterpolation.Size(426, 240), NeuralInterpolation.workingSize(1920, 1080, slow))
        assertEquals(NeuralInterpolation.Size(426, 240), NeuralInterpolation.workingSize(640, 360, slow))
    }

    @Test
    fun `the flow is brought to the picture's own size, each side by its own ratio`() {
        assertEquals(
            Pair(1920f / 426f, 1080f / 240f),
            NeuralInterpolation.flowScale(NeuralInterpolation.Size(1920, 1080), NeuralInterpolation.Size(426, 240)),
        )
    }

    private val device = mapOf(480 to 7.5, 576 to 10.5, 720 to 16.5, 1080 to 36.0)

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
        // 1080p takes 36 ms here: the in-betweens are made at 720p and enlarged with the rest of the picture.
        assertEquals(NeuralInterpolation.Size(1280, 720), NeuralInterpolation.workingSize(1920, 1080, device))
        // A wide film keeps its shape at the pixels of the 720p test picture; sizes stay even.
        assertEquals(
            NeuralInterpolation.Size(1488, 620),
            NeuralInterpolation.workingSize(1920, 800, mapOf(480 to 7.5, 576 to 10.5, 720 to 22.5, 1080 to 45.0)),
        )
    }

    @Test
    fun `a device too slow for even the smallest size interpolates nothing`() {
        assertNull(NeuralInterpolation.workingSize(1920, 1080, mapOf(480 to 45.0)))
        assertNull(NeuralInterpolation.workingSize(640, 360, mapOf(480 to 90.0)))
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
    fun `a self-test waits at most two seconds for one in-between, far beyond the budget already`() {
        assertEquals(2000.0, NeuralInterpolation.MAX_WAIT_MS, 0.0)
        assertTrue(NeuralInterpolation.cutShort(2000.0))
        assertFalse(NeuralInterpolation.cutShort(1999.0))
        assertFalse(NeuralInterpolation.fastEnough(NeuralInterpolation.MAX_WAIT_MS))
    }

    @Test
    fun `a network slower than the budget on a small picture is too slow for any larger`() {
        // The check's 128 x 128 picture: 35 ms there, and 480p has 25 times the pixels.
        assertEquals(25.0 * 854 * 480 / (128 * 128), NeuralInterpolation.estimate(25.0, 128 * 128), 1e-9)
        val small = NeuralInterpolation.Size(426, 240)
        assertEquals(25.0 * 426 * 240 / (128 * 128), NeuralInterpolation.estimate(25.0, 128 * 128, small), 1e-9)
        assertTrue(NeuralInterpolation.tooSlowAlready(35.0))
        assertFalse(NeuralInterpolation.tooSlowAlready(25.0))
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

    @Test
    fun `a flow that keeps up only at 120p still serves 1080p material at 120p`() {
        val phone = mapOf(120 to 18.0, 180 to 41.0)
        assertEquals(NeuralInterpolation.Size(214, 120), NeuralInterpolation.workingSize(1920, 1080, phone))
    }
}
