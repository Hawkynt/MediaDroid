package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.player.ModelFolder
import com.hawkynt.mediadroid.player.NeuralChecks
import com.hawkynt.mediadroid.player.NeuralEnlarging
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
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO
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
    fun `every model of waifu2x and Real-CUGAN agrees with its reference implementation, once and eight ways`() {
        // The expected pictures (src/test/resources/enlargers, see tools/upscalers/README.md): waifu2x's and the SE
        // models' from their PyTorch reference implementations on the CPU, the pro models' from realcugan-ncnn-vulkan
        // itself (their PyTorch weights are not published for download). Measured: off by at most 0.15 per value on
        // average, 5 at the most (waifu2x's ncnn port leaves out a clamp between its two U-Nets).
        val models = System.getProperty("mediadroid.testModels")?.let(::File) ?: folder.newFolder("models")
        val neural = DesktopNeural(models)
        assertTrue("the native library did not load", neural.loaded)
        val (input, w, h) = rgba("input.png")
        var compared = 0
        for (upscaler in listOf(PictureAdjustment.Upscaler.WAIFU2X, PictureAdjustment.Upscaler.REALCUGAN)) {
            for (model in NeuralEnlarging.models(upscaler)) for (tta in listOf(false, true)) {
                val choice = NeuralEnlarging.Choice(model, tta)
                val stem = if (upscaler == PictureAdjustment.Upscaler.WAIFU2X) {
                    "waifu2x-${model.file}"
                } else {
                    "realcugan-${if (model.isDefault) "se-up2x-conservative" else model.id}"
                }
                val expected = javaClass.getResource("/enlargers/$stem${if (tta) "+tta" else ""}.png") ?: continue
                if (!neural.ready(model.files)) {
                    val fetched = ModelFolder.fetch(neural.folder(model.files), model.files, {})
                    assertTrue("${choice.label} not fetched", fetched)
                }
                val out = requireNotNull(neural.enlarger(choice, false)) { "${choice.label} did not open" }.use { net ->
                    val rgb = requireNotNull(net.run(input, w, h)) { "${choice.label} did not run" }
                    ByteArray(w * h * net.scale * net.scale * 3).also(rgb::get)
                }
                val (mean, max) = difference(out, ImageIO.read(expected))
                assertTrue("${choice.label} off by $mean on average, $max at most", mean <= 0.5 && max <= 8)
                compared++
            }
        }
        assertEquals(26, compared)
    }

    @Test
    fun `tiles enlarge as the ncnn ports' tiles do, close to the whole picture`() {
        val neural = DesktopNeural(folder.root)
        val (input, w, h) = rgba("input.png")
        val choice = NeuralEnlarging.choice("WAIFU2X")!!
        requireNotNull(neural.enlarger(choice, false)).use { net ->
            val whole = ByteArray(w * h * 12).also(requireNotNull(net.run(input, w, h, tile = 0))::get)
            val tiled = ByteArray(w * h * 12).also(requireNotNull(net.run(input, w, h, tile = 16))::get)
            val mean = whole.indices.sumOf { abs((whole[it].toInt() and 0xFF) - (tiled[it].toInt() and 0xFF)) } /
                whole.size.toDouble()
            // Each tile's SE blocks average that tile alone: a little off at the seams, as in the ports.
            assertTrue("tiles off by $mean", mean < 1.0)
        }
    }

    /** The picture [name] of the expected ones as RGBA bytes, rows from the top: its bytes, width and height. */
    private fun rgba(name: String): Triple<ByteBuffer, Int, Int> {
        val image = ImageIO.read(requireNotNull(javaClass.getResource("/enlargers/$name")))
        val buffer = ByteBuffer.allocateDirect(image.width * image.height * 4)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val p = image.getRGB(x, y)
            buffer.put((p shr 16).toByte()).put((p shr 8).toByte()).put(p.toByte()).put(-1)
        }
        return Triple(buffer.rewind(), image.width, image.height)
    }

    // How far the RGB bytes [out] are from [expected]: the mean difference per value and the largest.
    private fun difference(out: ByteArray, expected: java.awt.image.BufferedImage): Pair<Double, Int> {
        assertEquals(expected.width * expected.height * 3, out.size)
        var sum = 0L
        var max = 0
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            val p = expected.getRGB(x, y)
            for (c in 0 until 3) {
                val want = (p shr (16 - 8 * c)) and 0xFF
                val got = out[(y * expected.width + x) * 3 + c].toInt() and 0xFF
                val d = abs(want - got)
                sum += d
                max = maxOf(max, d)
            }
        }
        return sum.toDouble() / out.size to max
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
                    NeuralEnlarging.choice(waifu2x)!! to NeuralInterpolation.Passed("CPU", mapOf(576 to 30.0)),
                ),
            )
            val enlarged = stage.process(picture, waifu2x, w * 2, h * 2)
            assertTrue(enlarged.rgba.indices.any { abs(enlarged.rgba[it] - plain.rgba[it]) > 8 })
        }
    }
}
