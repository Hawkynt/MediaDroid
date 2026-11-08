package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureTrialTest {

    /** A device whose answers the test sets, keeping what the trial asked of it. */
    private class FakeDevice(
        var dryRun: PictureCheck.DryRun? = PictureCheck.DryRun(frames = 30, late = 0, workMs = 10.0),
        var outcome: NeuralSelfTest.Outcome = NeuralSelfTest.Outcome.Ran(emptyList()),
        var ready: Boolean = true,
        var fetches: Boolean = true,
        var upscaling: NeuralSelfTest.Timing? = null,
        override val neuralOnNcnn: Boolean = false,
    ) : PictureTrial.Device {
        val enlargers = HashMap<NeuralEnlarging.Choice, NeuralInterpolation.Passed>()
        val motion = HashMap<PictureAdjustment.Interpolation, NeuralInterpolation.Passed>()
        var upscalingKept: NeuralSelfTest.Timing? = null
        val fetched = ArrayList<String>()
        val tested = ArrayList<String>()
        val dryRuns = ArrayList<PictureAdjustment>()

        override val frameMs = 40.0
        override val neuralUpscalingPassed get() = upscalingKept != null
        override fun testNeuralUpscaling(): NeuralSelfTest.Timing? = upscaling.also { tested += "Real-ESRGAN" }
        override fun keepNeuralUpscaling(best: NeuralSelfTest.Timing) {
            upscalingKept = best
        }

        override fun enlargerPassed(choice: NeuralEnlarging.Choice) = enlargers[choice]
        override fun motionPassed(network: PictureAdjustment.Interpolation) = motion[network]
        override fun modelsReady(download: NeuralModels.Download) = ready
        override fun fetch(download: NeuralModels.Download, progress: (Float) -> Unit): Boolean {
            fetched += download.folder
            progress(0.5f)
            return fetches
        }

        override fun selfTest(choice: NeuralEnlarging.Choice) = outcome.also { tested += choice.key }
        override fun selfTest(network: PictureAdjustment.Interpolation) = outcome.also { tested += network.name }
        override fun keepEnlarger(choice: NeuralEnlarging.Choice, passed: NeuralInterpolation.Passed) {
            enlargers[choice] = passed
        }

        override fun keepMotion(network: PictureAdjustment.Interpolation, passed: NeuralInterpolation.Passed) {
            motion[network] = passed
        }

        var colorizer: NeuralInterpolation.Passed? = null
        override fun colorizerPassed() = colorizer
        override fun selfTestColorizer() = outcome.also { tested += "DDCOLOR" }
        override fun keepColorizer(passed: NeuralInterpolation.Passed) {
            colorizer = passed
        }

        var colorizerRefused = false
        override fun refuseColorizer() {
            colorizerRefused = true
        }

        override fun dryRun(picture: PictureAdjustment): PictureCheck.DryRun? = dryRun.also { dryRuns += picture }
    }

    private val said = ArrayList<String>()
    private fun check(picture: PictureAdjustment, device: FakeDevice) =
        PictureTrial.check(picture, device) { said += it }

    @Test
    fun `a choice the dry run keeps up with is taken`() {
        val device = FakeDevice()
        val fsr = PictureAdjustment(upscaler = PictureAdjustment.Upscaler.FSR)
        assertTrue(check(fsr, device).keepsUp)
        assertEquals(listOf(fsr), device.dryRuns)
        assertEquals(listOf("Trying it on this device…"), said)
    }

    @Test
    fun `a dry run with late frames, too much work or no stage at all refuses, saying so`() {
        val picture = PictureAdjustment(interpolation = PictureAdjustment.Interpolation.MOTION)
        val late = check(picture, FakeDevice(dryRun = PictureCheck.DryRun(30, 9, 10.0)))
        assertFalse(late.keepsUp)
        assertEquals(
            "This device falls behind with it: 9 of 30 frames came too late. It stays as it was.",
            late.message,
        )
        val slow = check(picture, FakeDevice(dryRun = PictureCheck.DryRun(30, 0, 35.0)))
        assertFalse(slow.keepsUp)
        assertEquals(
            "This device falls behind with it: a 576p frame takes 35 ms of the 40 ms it lasts, and smooth playback " +
                "leaves 32 ms. It stays as it was.",
            slow.message,
        )
        val none = check(picture, FakeDevice(dryRun = null))
        assertEquals("The picture could not be tried on this device; it stays as it was.", none.message)
    }

    @Test
    fun `a network not yet fetched is fetched first, and refused where that fails`() {
        val device = FakeDevice(ready = false, fetches = false)
        val verdict = check(PictureAdjustment(interpolation = PictureAdjustment.Interpolation.DAIN), device)
        assertFalse(verdict.keepsUp)
        assertEquals(listOf("dain"), device.fetched)
        assertTrue(device.tested.isEmpty())
        assertEquals("Fetching DAIN… 50 %", said.single())
        assertEquals(
            "DAIN could not be fetched. Is the device online? Smooth motion stays as it was.",
            verdict.message,
        )
    }

    @Test
    fun `a network the device runs fast enough is kept as passed, then the whole picture is dry run`() {
        val timing = NeuralSelfTest.Timing("GPU", mapOf(480 to 8.0, 576 to 12.0))
        val device = FakeDevice(outcome = NeuralSelfTest.Outcome.Ran(listOf(timing)))
        val rife = PictureAdjustment(interpolation = PictureAdjustment.Interpolation.RIFE)
        assertTrue(check(rife, device).keepsUp)
        assertEquals(
            NeuralInterpolation.Passed("GPU", timing.msByHeight),
            device.motion[PictureAdjustment.Interpolation.RIFE],
        )
        assertEquals(listOf("RIFE"), device.tested)
        assertEquals(listOf(rife), device.dryRuns)
        // Passed once, it is not tested again.
        check(rife, device)
        assertEquals(listOf("RIFE"), device.tested)
    }

    @Test
    fun `a network too slow, run wrong or ended is refused without a dry run`() {
        val slow = FakeDevice(
            outcome = NeuralSelfTest.Outcome.Ran(listOf(NeuralSelfTest.Timing("CPU", mapOf(480 to 2000.0)))),
        )
        val flavr = PictureAdjustment(interpolation = PictureAdjustment.Interpolation.FLAVR)
        assertEquals(
            "This device is too slow for FLAVR: a 480p in-between takes more than 2 seconds on its fastest unit " +
                "(CPU), and playback allows 30 ms. Smooth motion stays as it was.",
            check(flavr, slow).message,
        )
        assertTrue(slow.dryRuns.isEmpty())
        assertTrue(slow.motion.isEmpty())
        val rife = PictureAdjustment(interpolation = PictureAdjustment.Interpolation.RIFE)
        val small = FakeDevice(
            outcome = NeuralSelfTest.Outcome.Ran(listOf(NeuralSelfTest.Timing("CPU", mapOf(120 to 34.0)))),
        )
        assertEquals(
            "This device is too slow for RIFE: a 120p in-between takes 34 ms on its fastest unit (CPU), and " +
                "playback allows 30 ms. Smooth motion stays as it was.",
            check(rife, small).message,
        )
        assertEquals(
            "This device cannot run FLAVR at all. Smooth motion stays as it was.",
            check(flavr, FakeDevice()).message,
        )
        assertEquals(
            "FLAVR needed more than this device can give — memory or graphics — and the test was ended. Smooth " +
                "motion stays as it was.",
            check(flavr, FakeDevice(outcome = NeuralSelfTest.Outcome.Ended)).message,
        )
    }

    @Test
    fun `an enlarger is tested on a 576p picture against its own budget`() {
        val device = FakeDevice(
            outcome = NeuralSelfTest.Outcome.Ran(listOf(NeuralSelfTest.Timing("GPU", mapOf(576 to 55.0)))),
        )
        val verdict = check(PictureAdjustment(upscaler = PictureAdjustment.Upscaler.REALSR), device)
        assertEquals(
            "This device is too slow for RealSR: a 576p picture takes 55 ms on its fastest unit (GPU), and a frame " +
                "allows 40 ms. The upscaler stays as it was.",
            verdict.message,
        )
        val fast = FakeDevice(
            outcome = NeuralSelfTest.Outcome.Ran(listOf(NeuralSelfTest.Timing("GPU", mapOf(576 to 25.0)))),
        )
        assertTrue(check(PictureAdjustment(upscaler = PictureAdjustment.Upscaler.WAIFU2X), fast).keepsUp)
        assertEquals(
            NeuralInterpolation.Passed("GPU", mapOf(576 to 25.0)),
            fast.enlargers[NeuralEnlarging.choice("WAIFU2X")],
        )
    }

    @Test
    fun `each model of an upscaler is fetched where not carried and tested on its own, eight ways apart`() {
        val passed = NeuralSelfTest.Outcome.Ran(listOf(NeuralSelfTest.Timing("GPU", mapOf(576 to 25.0))))
        val device = FakeDevice(outcome = passed, ready = false)
        val noise3 = PictureAdjustment(upscaler = PictureAdjustment.Upscaler.WAIFU2X, upscalerModel = "noise3")
        assertTrue(check(noise3, device).keepsUp)
        assertEquals(listOf("waifu2x-cunet-noise3"), device.fetched)
        assertEquals(listOf("WAIFU2X:noise3"), device.tested)
        assertTrue("Testing waifu2x Noise 3 on this device…" in said)
        // The same model eight ways is a choice of its own; once passed, neither is tested again.
        assertTrue(check(noise3.copy(upscalerTta = true), device).keepsUp)
        assertTrue(check(noise3, device).keepsUp)
        assertEquals(listOf("WAIFU2X:noise3", "WAIFU2X:noise3+tta"), device.tested)
        // The carried default needs no fetch.
        assertTrue(check(PictureAdjustment(upscaler = PictureAdjustment.Upscaler.WAIFU2X), device).keepsUp)
        assertEquals(listOf("waifu2x-cunet-noise3", "waifu2x-cunet-noise3"), device.fetched)
    }

    @Test
    fun `Real-ESRGAN is kept on its fastest unit where that is within a frame`() {
        val device = FakeDevice(upscaling = NeuralSelfTest.Timing("GPU", mapOf(576 to 30.0)))
        assertTrue(check(PictureAdjustment(upscaler = PictureAdjustment.Upscaler.NEURAL), device).keepsUp)
        assertEquals("GPU", device.upscalingKept?.unit)
        val slow = FakeDevice(upscaling = NeuralSelfTest.Timing("CPU", mapOf(576 to 900.0)))
        assertEquals(
            "This device is too slow for Real-ESRGAN: a 576p picture takes 900 ms on its fastest unit (CPU), and a " +
                "frame allows 40 ms. The upscaler stays as it was.",
            check(PictureAdjustment(upscaler = PictureAdjustment.Upscaler.NEURAL), slow).message,
        )
        assertEquals(
            "This device cannot run Real-ESRGAN at all; the upscaler stays as it was.",
            check(PictureAdjustment(upscaler = PictureAdjustment.Upscaler.NEURAL), FakeDevice()).message,
        )
    }

    @Test
    fun `where Real-ESRGAN runs on ncnn each of its models is fetched and tested as the other networks are`() {
        val passed = NeuralSelfTest.Outcome.Ran(listOf(NeuralSelfTest.Timing("GPU", mapOf(576 to 30.0))))
        val device = FakeDevice(outcome = passed, ready = false, neuralOnNcnn = true)
        val general = PictureAdjustment(upscaler = PictureAdjustment.Upscaler.NEURAL, upscalerModel = "x4plus")
        assertTrue(check(general, device).keepsUp)
        assertEquals(listOf("realesrgan-x4plus"), device.fetched)
        assertEquals(listOf("NEURAL:x4plus"), device.tested)
        assertEquals(null, device.upscalingKept)
        assertTrue("Testing Real-ESRGAN General on this device…" in said)
    }

    @Test
    fun `colour is fetched, tested once and kept, refused where a picture's colour takes over a second`() {
        val colour = PictureAdjustment(colorize = PictureAdjustment.Colorize.DDCOLOR)
        val slow = FakeDevice(
            outcome = NeuralSelfTest.Outcome.Ran(listOf(NeuralSelfTest.Timing("CPU", mapOf(256 to 1500.0)))),
        )
        assertEquals(
            "This device is too slow for DDColor: colouring a picture takes 1500 ms on its fastest unit (CPU), and " +
                "the colour should follow within 1000 ms. The colour stays as it was.",
            check(colour, slow).message,
        )
        val fast = FakeDevice(
            outcome = NeuralSelfTest.Outcome.Ran(listOf(NeuralSelfTest.Timing("GPU", mapOf(256 to 120.0)))),
            ready = false,
        )
        assertTrue(check(colour, fast).keepsUp)
        assertEquals(listOf("ddcolor"), fast.fetched)
        assertEquals("GPU", fast.colorizer?.unit)
        assertTrue(check(colour, fast).keepsUp)
        assertEquals(listOf("DDCOLOR"), fast.tested)
    }

    @Test
    fun `colour too slow or wrong on the device is refused for good, a fetch that failed is not`() {
        val colour = PictureAdjustment(colorize = PictureAdjustment.Colorize.DDCOLOR)
        val slow = FakeDevice(
            outcome = NeuralSelfTest.Outcome.Ran(listOf(NeuralSelfTest.Timing("CPU", mapOf(256 to 1500.0)))),
        )
        check(colour, slow)
        assertTrue(slow.colorizerRefused)
        val wrong = FakeDevice(outcome = NeuralSelfTest.Outcome.Ran(emptyList()))
        check(colour, wrong)
        assertTrue(wrong.colorizerRefused)
        val offline = FakeDevice(ready = false, fetches = false)
        check(colour, offline)
        assertFalse(offline.colorizerRefused)
        val fast = FakeDevice(
            outcome = NeuralSelfTest.Outcome.Ran(listOf(NeuralSelfTest.Timing("GPU", mapOf(256 to 120.0)))),
        )
        check(colour, fast)
        assertFalse(fast.colorizerRefused)
    }

    @Test
    fun `a cut-short time reads as more than the whole test may take`() {
        assertEquals("more than 2 seconds", PictureTrial.duration(NeuralInterpolation.MAX_WAIT_MS))
        assertEquals("17 ms", PictureTrial.duration(17.9))
    }
}
