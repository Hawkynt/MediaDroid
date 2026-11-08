package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference dithering against libplacebo 7.360's own, run by libplacebo on Mesa's OpenGL: `resources/dither/` holds
 * a 16-bit input (`make-input.py`), libplacebo's 8-bit picture of it and its blue-noise matrix (`make-expected.sh`).
 */
class DitherTest {

    private fun resource(name: String): ByteArray =
        requireNotNull(DitherTest::class.java.getResourceAsStream("/dither/$name")) { name }.use { it.readBytes() }

    private class Ppm(val width: Int, val height: Int, val data: ByteArray)

    private fun ppm(name: String): Ppm {
        val bytes = resource(name)
        val header = String(bytes, 0, 20, Charsets.US_ASCII).split(Regex("\\s+"))
        val width = header[1].toInt()
        val height = header[2].toInt()
        val size = width * height * if (header[3] == "65535") 6 else 3
        return Ppm(width, height, bytes.copyOfRange(bytes.size - size, bytes.size))
    }

    private val input: Ppm by lazy { ppm("input.ppm") }

    private val input16: ShortArray by lazy {
        val data = input.data
        ShortArray(data.size / 2) {
            ((data[2 * it].toInt() and 0xFF) shl 8 or (data[2 * it + 1].toInt() and 0xFF)).toShort()
        }
    }

    @Test
    fun `the C library's rand, unseeded, as glibc gives it`() {
        val rand = Dither.GlibcRandom()
        // glibc's first five numbers after srand(1), the state every process starts in.
        val first = IntArray(5) { rand.next() }
        assertArrayEquals(intArrayOf(1804289383, 846930886, 1681692777, 1714636915, 1957747793), first)
    }

    @Test
    fun `the blue-noise matrix is libplacebo's own`() {
        val expected = String(resource("bluenoise.txt"), Charsets.US_ASCII).trim().split(Regex("\\s+"))
            .map { it.toInt() }.toIntArray()
        assertEquals(Dither.SIZE * Dither.SIZE, expected.size)
        assertArrayEquals(expected, Dither.blueNoise)
    }

    @Test
    fun `the GL pass's texture holds each rank in red and green`() {
        val rgba = Dither.noiseRgba()
        assertEquals(Dither.SIZE * Dither.SIZE * 4, rgba.size)
        for (i in Dither.blueNoise.indices) {
            val rank = (rgba[i * 4].toInt() and 0xFF) * 256 + (rgba[i * 4 + 1].toInt() and 0xFF)
            assertEquals(Dither.blueNoise[i], rank)
        }
    }

    @Test
    fun `a 16-bit picture is dithered to 8 bits as libplacebo dithers it, byte for byte`() {
        val expected = ppm("expected.ppm")
        val ours = Dither.apply(input16, input.width, input.height)
        // Dithering changed a good part of the picture against plain rounding, so the agreement is about it.
        val rounded = ByteArray(input16.size) { (((input16[it].toInt() and 0xFFFF) * 255 + 32767) / 65535).toByte() }
        val changed = expected.data.indices.count { expected.data[it] != rounded[it] }
        assertTrue("dithering changed only $changed values", changed > expected.data.size / 5)
        assertArrayEquals(expected.data, ours)
    }

    @Test
    fun `exact 8-bit levels and full white stay as they are`() {
        val ours = Dither.apply(input16, input.width, input.height)
        for (y in listOf(0, 7, 92, 95)) {
            for (x in 0 until input.width) {
                for (c in 0..2) {
                    val i = (y * input.width + x) * 3 + c
                    assertEquals("$x, $y", (input16[i].toInt() and 0xFFFF) / 257, ours[i].toInt() and 0xFF)
                }
            }
        }
    }

    @Test
    fun `a gradient between two levels keeps its average`() {
        // A quarter of the way from 100 to 101 over a 64 × 64 tile: a quarter of the pixels go up.
        val value = (100.25 * 257).toInt().toShort()
        val ours = Dither.apply(ShortArray(64 * 64 * 3) { value }, 64, 64)
        val up = ours.count { (it.toInt() and 0xFF) == 101 }
        assertEquals(ours.size / 4.0, up.toDouble(), ours.size * 0.01)
        assertTrue(ours.all { (it.toInt() and 0xFF) in 100..101 })
    }

    @Test
    fun `dithering applies where the picture holds more than the screen shows`() {
        val plain = PictureAdjustment.Neutral
        // A plain 8-bit picture: nothing to dither, nothing changes.
        assertFalse(Dither.applies(8, 8, plain))
        assertFalse(Dither.applies(8, 8, plain.copy(sharpness = 0.5f)))
        // 10-bit sources and HDR's tone-mapped floats.
        assertTrue(Dither.applies(10, 8, plain))
        assertTrue(Dither.applies(16, 8, plain))
        // Filters whose float results fall between 8-bit levels: the colour correction, debanding.
        assertTrue(Dither.applies(8, 8, plain.copy(contrast = 1.2f)))
        assertTrue(Dither.applies(8, 8, plain.copy(gamma = 0.9f)))
        assertTrue(Dither.applies(8, 8, plain.copy(deband = 0.5f)))
        // A screen as deep as the picture, or a 16-bit one (libplacebo leaves those alone).
        assertFalse(Dither.applies(10, 10, plain))
        assertFalse(Dither.applies(16, 16, plain.copy(contrast = 1.2f)))
    }
}
