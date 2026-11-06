package com.hawkynt.mediadroid.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference deblocking against FFmpeg's own `deblock` filter, byte for byte: `resources/deblock/` holds a picture
 * MPEG-2 left blocky and FFmpeg's output for it, each RGB channel filtered as a plane (`make-expected.sh`).
 */
class DeblockTest {

    private class Rgb(val width: Int, val height: Int, val bytes: ByteArray)

    private fun ppm(name: String): Rgb {
        val data = requireNotNull(DeblockTest::class.java.getResourceAsStream("/deblock/$name")) { name }
            .use { it.readBytes() }
        val header = String(data, 0, 20, Charsets.US_ASCII).split(Regex("\\s+"))
        val width = header[1].toInt()
        val height = header[2].toInt()
        return Rgb(width, height, data.copyOfRange(data.size - width * height * 3, data.size))
    }

    private val input = ppm("input.ppm")

    /** [rgb]'s channel [c] as a plane. */
    private fun plane(rgb: Rgb, c: Int) = ByteArray(rgb.width * rgb.height) { rgb.bytes[it * 3 + c] }

    private fun deblocked(params: Deblock.Params, originX: Int = 0, originY: Int = 0, from: Rgb = input): ByteArray {
        val out = ByteArray(from.bytes.size)
        for (c in 0..2) {
            Deblock.apply(plane(from, c), from.width, from.height, params, originX, originY)
                .forEachIndexed { i, v -> out[i * 3 + c] = v }
        }
        return out
    }

    private fun check(filter: Deblock.Filter, alpha: String, beta: String, gamma: String, delta: String) {
        val name = "${filter.name.lowercase()}-$alpha-$beta-$gamma-$delta.ppm"
        val expected = ppm(name).bytes
        val changed = expected.indices.count { expected[it] != input.bytes[it] }
        assertTrue("$name: FFmpeg changed only $changed values", changed > expected.size / 50)
        val params = Deblock.Params(filter, alpha.toFloat(), beta.toFloat(), gamma.toFloat(), delta.toFloat())
        assertArrayEquals(name, expected, deblocked(params))
    }

    @Test
    fun `the weak filter agrees with FFmpeg's`() = check(Deblock.Filter.WEAK, "0.098", "0.05", "0.05", "0.05")

    @Test
    fun `the strong filter, FFmpeg's default, agrees`() =
        check(Deblock.Filter.STRONG, "0.098", "0.05", "0.05", "0.05")

    @Test
    fun `higher thresholds agree`() = check(Deblock.Filter.STRONG, "0.196", "0.1", "0.1", "0.1")

    @Test
    fun `lower thresholds agree`() = check(Deblock.Filter.WEAK, "0.05", "0.025", "0.025", "0.025")

    @Test
    fun `a cropped picture is deblocked on the grid of the picture it was cut from`() {
        // Cut 3 pixels from the left and 5 from the top: the blocks start 5 and 3 pixels in.
        val left = 3
        val top = 5
        val w = input.width - left
        val h = input.height - top
        val cut = Rgb(w, h, ByteArray(w * h * 3) { i ->
            val x = i / 3 % w
            val y = i / 3 / w
            input.bytes[((y + top) * input.width + x + left) * 3 + i % 3]
        })
        val whole = deblocked(Deblock.Defaults)
        val (originX, originY) = Deblock.gridOrigin(left, top)
        assertEquals(5 to 3, originX to originY)
        assertEquals(0 to 0, Deblock.gridOrigin(16, 8))
        val part = deblocked(Deblock.Defaults, originX, originY, cut)
        // Away from the cut's edges and the last band (whose top edge FFmpeg leaves) both are the same pixels.
        for (y in 8 until h - 16) for (x in 8 until w) for (c in 0..2) {
            assertEquals("($x, $y)", whole[((y + top) * input.width + x + left) * 3 + c], part[(y * w + x) * 3 + c])
        }
        assertTrue(!part.contentEquals(cut.bytes))
    }

    @Test
    fun `the slider runs from off through FFmpeg's defaults to twice their reach`() {
        assertNull(Deblock.params(0f))
        assertEquals(Deblock.Filter.WEAK, Deblock.params(0.3f)?.filter)
        assertEquals(Deblock.Defaults, Deblock.params(0.5f))
        val top = requireNotNull(Deblock.params(1f))
        assertEquals(Deblock.Filter.STRONG, top.filter)
        assertEquals(0.196f, top.alpha, 1e-6f)
        assertEquals(24, Deblock.threshold(0.098f))
    }
}
