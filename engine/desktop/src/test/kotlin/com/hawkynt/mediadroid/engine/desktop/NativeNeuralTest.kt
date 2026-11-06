package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.player.NeuralChecks
import com.hawkynt.mediadroid.player.NeuralInterpolation
import com.hawkynt.mediadroid.player.NeuralSelfTest
import com.hawkynt.mediadroid.player.PictureAdjustment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs

/**
 * The carried networks on the desktop library (ncnn on the CPU where there is no Vulkan device, as on a CI runner):
 * each passes the self-test's check of computing right, the test runs in a process of its own, and the stage enlarges
 * with one. Needs the library built (`-PnativeLibrary`), as NativeNetworksTest does.
 */
class NativeNeuralTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val quiet = object : NeuralChecks.Progress {
        override fun running(height: Int) = Unit
        override fun timed(height: Int, ms: Double) = Unit
    }

    @Test
    fun `RIFE and IFRNet compute the in-between right on the CPU`() {
        assertTrue("the native library did not load", DesktopNeural(folder.root).loaded)
        for (network in listOf(PictureAdjustment.Interpolation.RIFE, PictureAdjustment.Interpolation.IFRNET)) {
            val timing = NeuralTestProcess.test(folder.root, "motion", network.name, "CPU", quiet)
            assertNotNull("$network ran wrong", timing)
            assertEquals("CPU", timing!!.unit)
        }
    }

    @Test
    fun `IFRNet makes the same in-between again and again, the heap around it left whole`() {
        // Its time plane once went in at the whole size where the network joins it at a sixteenth: ncnn's Concat wrote
        // past its output (AddressSanitizer), and within a few 480p in-betweens the process went down (Android too).
        val net = requireNotNull(DesktopNeural(folder.root).interpolator(PictureAdjustment.Interpolation.IFRNET, false))
        net.use {
            val w = 854
            val h = 480
            val a = NeuralChecks.testFrame(w, h, 0)
            val b = NeuralChecks.testFrame(w, h, 2)
            val first = requireNotNull(it.run(a, b, w, h)).let { out -> ByteArray(w * h * 3).also(out::get) }
            repeat(8) { _ ->
                val again = requireNotNull(it.run(a, b, w, h)).let { out -> ByteArray(w * h * 3).also(out::get) }
                assertTrue(first.contentEquals(again))
            }
        }
        // The native heap still serves another network: a corrupted one fails here at the latest.
        val rife = DesktopNeural(folder.root).interpolator(PictureAdjustment.Interpolation.RIFE, false)
        requireNotNull(rife).use {
            assertNotNull(it.run(NeuralChecks.testFrame(256, 256, 0), NeuralChecks.testFrame(256, 256, 2), 256, 256))
        }
    }

    @Test
    fun `waifu2x, Real-CUGAN and Real-ESRGAN enlarge right on the CPU`() {
        val upscalers = listOf(
            PictureAdjustment.Upscaler.WAIFU2X, PictureAdjustment.Upscaler.REALCUGAN, PictureAdjustment.Upscaler.NEURAL,
        )
        for (upscaler in upscalers) {
            val timing = NeuralTestProcess.test(folder.root, "upscaler", upscaler.name, "CPU", quiet)
            assertNotNull("$upscaler ran wrong", timing)
        }
    }

    @Test
    fun `the self-test runs each unit in a process of its own and answers within its time`() {
        val start = System.nanoTime()
        val before = NeuralTestProcess.processes
        val outcome = NeuralTestProcess.selfTest(folder.root, PictureAdjustment.Interpolation.IFRNET)
        val ms = (System.nanoTime() - start) / 1e6
        assertEquals(2, NeuralTestProcess.processes - before)
        // The GPU's process answers, says it cannot run it or ends — a broken Vulkan driver takes only that process
        // down, never this one; the CPU's answers or is cut short.
        assertTrue("$outcome", outcome is NeuralSelfTest.Outcome.Ran)
        val timings = (outcome as NeuralSelfTest.Outcome.Ran).timings
        assertTrue("$timings", timings.isNotEmpty() && timings.all { it.unit in NeuralSelfTest.UNITS })
        assertTrue("took $ms ms", ms < NeuralInterpolation.MAX_WAIT_MS + 5_000)
    }

    @Test
    fun `the stage enlarges with a network whose test passed`() {
        assumeTrue("no display", System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null)
        GlStage().use { stage ->
            assumeTrue(stage.ready)
            val w = 48
            val h = 32
            val picture = MediaReader.Picture(0, w, h, ByteArray(w * h * 4) { i ->
                if (i % 4 == 3) -1 else (if ((i / 4 % w) / 6 % 2 == (i / 4 / w) / 6 % 2) 40 else 220).toByte()
            })
            val waifu2x = PictureAdjustment(upscaler = PictureAdjustment.Upscaler.WAIFU2X)
            val plain = stage.process(picture, waifu2x, w * 2, h * 2)
            stage.neural = GlStage.Neural(
                DesktopNeural(folder.root),
                enlargers = mapOf(
                    PictureAdjustment.Upscaler.WAIFU2X to NeuralInterpolation.Passed("CPU", mapOf(576 to 30.0)),
                ),
            )
            val enlarged = stage.process(picture, waifu2x, w * 2, h * 2)
            assertTrue(enlarged.rgba.indices.any { abs(enlarged.rgba[it] - plain.rgba[it]) > 8 })
        }
    }
}
