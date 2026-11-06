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
    fun `waifu2x and Real-CUGAN enlarge right on the CPU`() {
        for (upscaler in listOf(PictureAdjustment.Upscaler.WAIFU2X, PictureAdjustment.Upscaler.REALCUGAN)) {
            val timing = NeuralTestProcess.test(folder.root, "upscaler", upscaler.name, "CPU", quiet)
            assertNotNull("$upscaler ran wrong", timing)
        }
    }

    @Test
    fun `the self-test runs each unit in a process of its own and answers within its time`() {
        val start = System.nanoTime()
        val outcome = NeuralTestProcess.selfTest(folder.root, PictureAdjustment.Interpolation.IFRNET)
        val ms = (System.nanoTime() - start) / 1e6
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
