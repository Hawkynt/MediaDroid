package com.hawkynt.mediadroid.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference debanding against libplacebo's own `pl_shader_deband` (7.360), run by libplacebo on Mesa's OpenGL, byte
 * for byte:
 * `resources/deband/` holds the input (`make-input.py`) and libplacebo's output for each case (`make-expected.sh`).
 */
class DebandTest {

    private class Rgb(val width: Int, val height: Int, val bytes: ByteArray)

    /** A binary PPM fixture from `resources/deband/`. */
    private fun ppm(name: String): Rgb {
        val data = requireNotNull(DebandTest::class.java.getResourceAsStream("/deband/$name")) { name }
            .use { it.readBytes() }
        val header = String(data, 0, 20, Charsets.US_ASCII).split(Regex("\\s+"))
        val width = header[1].toInt()
        val height = header[2].toInt()
        return Rgb(width, height, data.copyOfRange(data.size - width * height * 3, data.size))
    }

    private val input = ppm("input.ppm")

    private fun check(iterations: Int, threshold: Float, radius: Float, grain: Float, frame: Int) {
        val name = "$iterations-${threshold.toInt()}-${radius.toInt()}-${grain.toInt()}-$frame.ppm"
        val expected = ppm(name).bytes
        val ours = Deband.apply(
            input.bytes,
            input.width,
            input.height,
            Deband.Params(iterations, threshold, radius, grain),
            frame,
        )
        // libplacebo changed a good part of the picture, so the agreement is about something.
        val changed = expected.indices.count { expected[it] != input.bytes[it] }
        assertTrue("$name: libplacebo changed only $changed values", changed > expected.size / 20)
        assertArrayEquals(name, expected, ours)
    }

    @Test
    fun `one iteration without grain agrees with libplacebo`() = check(1, 3f, 16f, 0f, 0)

    @Test
    fun `libplacebo's defaults with grain agree`() = check(1, 3f, 16f, 4f, 0)

    @Test
    fun `another frame draws other random numbers, as libplacebo's`() = check(1, 3f, 16f, 4f, 5)

    @Test
    fun `two iterations agree`() = check(2, 4f, 16f, 6f, 1)

    @Test
    fun `four iterations, the strongest setting, agree`() = check(4, 6f, 16f, 8f, 0)

    @Test
    fun `a wider radius and the last frame index agree`() = check(3, 5f, 24f, 0f, 255)

    @Test
    fun `frame indices wrap at 256, as libplacebo's 8-bit counter`() {
        val params = Deband.Params(1, 3f, 16f, 4f)
        val a = Deband.apply(input.bytes, input.width, input.height, params, 5)
        val b = Deband.apply(input.bytes, input.width, input.height, params, 261)
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `the slider runs from off through libplacebo's defaults to four iterations`() {
        assertNull(Deband.params(0f))
        assertNull(Deband.params(-0.5f))
        assertEquals(Deband.Params(1, 3f, 16f, 4f), Deband.params(0.5f))
        assertEquals(Deband.Params(4, 6f, 16f, 8f), Deband.params(1f))
        assertEquals(Deband.Params(4, 6f, 16f, 8f), Deband.params(2f))
        val weak = requireNotNull(Deband.params(0.1f))
        assertEquals(1, weak.iterations)
        assertEquals(0.6f, weak.threshold, 1e-6f)
        val steps = (1..20).map { requireNotNull(Deband.params(it / 20f)) }
        steps.zipWithNext().forEach { (a, b) ->
            assertTrue(b.iterations >= a.iterations)
            assertTrue(b.threshold > a.threshold)
        }
    }

    @Test
    fun `debanding asks for the GL stage`() {
        assertTrue(PictureAdjustment(deband = 0.4f).hasFilters)
        assertEquals(Deband.params(0.4f), PictureAdjustment(deband = 0.4f).debandParams())
        assertNull(PictureAdjustment.Neutral.debandParams())
    }
}
