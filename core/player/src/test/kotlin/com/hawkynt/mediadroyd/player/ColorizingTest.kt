package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs

class ColorizingTest {
    private fun near(expected: Float, actual: Float, within: Float, what: String = "") =
        assertTrue("$what: $actual is not $expected", abs(expected - actual) <= within)

    @Test
    fun `Lab is OpenCV's, for floats in 0 to 1`() {
        // cv2.cvtColor(..., COLOR_RGB2Lab) on float32 pixels, OpenCV 4.
        val cases = listOf(
            floatArrayOf(1f, 0f, 0f) to floatArrayOf(53.240967f, 80.09375f, 67.203125f),
            floatArrayOf(0f, 1f, 0f) to floatArrayOf(87.73804f, -86.1875f, 83.171875f),
            floatArrayOf(0f, 0f, 1f) to floatArrayOf(32.2937f, 79.1875f, -107.859375f),
            floatArrayOf(0.5f, 0.5f, 0.5f) to floatArrayOf(53.38745f, 0f, 0f),
            floatArrayOf(0.2f, 0.4f, 0.6f) to floatArrayOf(41.888428f, 0.0625f, -32.984375f),
        )
        for ((rgb, lab) in cases) {
            val got = Colorizing.lab(rgb[0], rgb[1], rgb[2])
            // OpenCV's float Lab comes out of tables (a and b in 64ths), so to within a few tenths of a unit.
            for (i in 0 until 3) near(lab[i], got[i], 0.3f, "${rgb.toList()}[$i]")
            val back = Colorizing.rgb(got[0], got[1], got[2])
            for (i in 0 until 3) near(rgb[i], back[i], 0.002f, "back ${rgb.toList()}[$i]")
        }
        // A grey of lightness L, as DDColor's input is made: cv2 Lab2RGB of (50, 0, 0) and (5, 0, 0).
        near(0.46632671f, Colorizing.rgb(50f, 0f, 0f)[1], 0.0005f)
        near(0.06602654f, Colorizing.rgb(5f, 0f, 0f)[1], 0.0005f)
    }

    private fun picture(w: Int, h: Int, colour: (Int, Int) -> Int): ByteBuffer =
        ByteBuffer.allocateDirect(w * h * 4).also { b ->
            for (y in 0 until h) for (x in 0 until w) {
                val c = colour(x, y)
                b.put((c shr 16).toByte()).put((c shr 8).toByte()).put(c.toByte()).put(-1)
            }
            b.rewind()
        }

    @Test
    fun `the network's input is the picture's lightness as grey, at 256 by 256, three planes`() {
        val grey = picture(512, 300) { x, _ -> if (x < 256) 0x808080 else 0x202020 }
        val input = Colorizing.input(grey, 512, 300)
        assertEquals(3 * 256 * 256, input.size)
        val left = Colorizing.rgb(Colorizing.lab(128 / 255f, 128 / 255f, 128 / 255f)[0], 0f, 0f)[0]
        near(left, input[100 * 256 + 10], 0.003f)
        // R, G and B the same.
        assertEquals(input[100 * 256 + 10], input[256 * 256 + 100 * 256 + 10])
        assertEquals(input[100 * 256 + 200], input[2 * 256 * 256 + 100 * 256 + 200])
        assertTrue(input[100 * 256 + 200] < 0.2f)
    }

    @Test
    fun `a refusal holds for one app version and one model only`() {
        val key = Colorizing.refusalKey(20261107)
        assertEquals(key, Colorizing.refusalKey(20261107))
        assertTrue(key != Colorizing.refusalKey(20261108))
        val other = NeuralModels.DDCOLOR.copy(
            files = NeuralModels.DDCOLOR.files.map { it.copy(sha256 = "0".repeat(64)) },
        )
        assertTrue(key != Colorizing.refusalKey(20261107, other))
        assertFalse(Colorizing.refused("", 20261107))
        assertTrue(Colorizing.refused(key, 20261107))
        assertFalse(Colorizing.refused(key, 20261108))
    }

    @Test
    fun `a picture is offered colour where it is grey already`() {
        val grey = picture(64, 48) { x, y -> ((x + y) * 2).let { it shl 16 or (it shl 8) or it } }
        assertTrue(Colorizing.isGrey(grey, 64, 48))
        val sepia = picture(64, 48) { _, _ -> 0x9A7B55 }
        assertFalse(Colorizing.isGrey(sepia, 64, 48))
        val colour = picture(64, 48) { x, _ -> if (x < 32) 0xFF2020 else 0x2040FF }
        assertFalse(Colorizing.isGrey(colour, 64, 48))
    }

    @Test
    fun `a cut is a change of the whole picture's lightness, not motion within it`() {
        val a = FloatArray(256 * 256) { (it % 256) / 255f }
        val moved = FloatArray(256 * 256) { ((it + 3) % 256) / 255f }
        val other = FloatArray(256 * 256) { 1f - (it % 256) / 255f }
        assertFalse(Colorizing.cut(a, moved))
        assertTrue(Colorizing.cut(a, other))
        assertTrue(Colorizing.cut(null, a))
    }

    @Test
    fun `the colour is smoothed over time and starts afresh at a cut`() {
        val smoother = Colorizing.Smoother(weight = 0.25f)
        assertNull(smoother.current)
        smoother.add(FloatArray(4) { 8f }, cut = false)
        assertArrayEquals(FloatArray(4) { 8f }, smoother.current, 0f)
        smoother.add(FloatArray(4) { 0f }, cut = false)
        assertArrayEquals(FloatArray(4) { 6f }, smoother.current, 1e-6f)
        smoother.add(FloatArray(4) { -4f }, cut = true)
        assertArrayEquals(FloatArray(4) { -4f }, smoother.current, 0f)
        smoother.clear()
        assertNull(smoother.current)
    }

    @Test
    fun `the colour is laid under the frame's own lightness, the chroma enlarged smoothly`() {
        val w = 8
        val h = 4
        val frame = picture(w, h) { x, _ -> (60 + x * 20).let { it shl 16 or (it shl 8) or it } }
        // a = 20, b = 30 everywhere: an orange tint.
        val ab = FloatArray(2 * 256 * 256) { if (it < 256 * 256) 20f else 30f }
        val out = Colorizing.colorize(frame, w, h, ab)
        for (x in 0 until w) {
            val i = (2 * w + x) * 4
            val r = (out[i].toInt() and 0xFF) / 255f
            val g = (out[i + 1].toInt() and 0xFF) / 255f
            val b = (out[i + 2].toInt() and 0xFF) / 255f
            val lab = Colorizing.lab(r, g, b)
            val v = (60 + x * 20) / 255f
            near(Colorizing.lab(v, v, v)[0], lab[0], 1.2f, "L at $x")
            if (x in 2..6) {
                assertTrue("tinted at $x: $r $g $b", r > b)
            }
        }
    }

    @Test
    fun `the self-test takes a unit whose colours match DDColor's reference, and no other`() {
        val expected = javaClass.getResourceAsStream("/colorize/check.ab")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }.map { it.toFloat() }
        // The reference's block averages spread back over their blocks: what a unit computing right gives.
        val right = FloatArray(2 * 256 * 256) { i ->
            val plane = i / 65536
            val y = i % 65536 / 256
            val x = i % 256
            expected[plane * 256 + y / 16 * 16 + x / 16]
        }
        val quiet = object : NeuralChecks.Progress {
            override fun running(height: Int) = Unit
            override fun timed(height: Int, ms: Double) = Unit
        }
        val timing = NeuralChecks.colorizer({ grey -> assertEquals(3 * 65536, grey.size); right }, "GPU", quiet)
        assertEquals("GPU", timing?.unit)
        assertEquals(setOf(256), timing?.msByHeight?.keys)
        assertNull(NeuralChecks.colorizer({ FloatArray(2 * 65536) }, "GPU", quiet))
        assertNull(NeuralChecks.colorizer({ null }, "GPU", quiet))
    }

    @Test
    fun `a run within a second keeps the colour current`() {
        assertTrue(Colorizing.fastEnough(900.0))
        assertFalse(Colorizing.fastEnough(1100.0))
        assertTrue(Colorizing.rightEnough(1.5))
        assertFalse(Colorizing.rightEnough(6.0))
    }

    @Test
    fun `DDColor is fetched from the app's model repository, its chip shown once the files are there`() {
        val ddcolor = NeuralModels.DDCOLOR
        assertEquals("ddcolor", ddcolor.folder)
        assertEquals(listOf("ddcolor.param", "ddcolor.bin"), ddcolor.files.map { it.name })
        assertEquals(
            "https://huggingface.co/Hawkynt/mediadroid-models/resolve/main/ddcolor/ddcolor.bin",
            ddcolor.url(ddcolor.files.last()),
        )
        assertEquals(listOf(PictureAdjustment.Colorize.OFF), Colorizing.offered(available = false))
        assertEquals(PictureAdjustment.Colorize.entries, Colorizing.offered(available = true))
        // Reachable once the repository answers for its first file; offline or missing, not.
        val asked = ArrayList<String>()
        assertTrue(ModelFolder.reachable(ddcolor) { url -> asked += url; true })
        assertEquals(listOf(ddcolor.url(ddcolor.files.first())), asked)
        assertFalse(ModelFolder.reachable(ddcolor) { false })
        assertFalse(ModelFolder.reachable(ddcolor) { error("offline") })
    }
}
