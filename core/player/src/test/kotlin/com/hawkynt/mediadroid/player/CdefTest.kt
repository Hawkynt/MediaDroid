package com.hawkynt.mediadroid.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference deringing against dav1d's own CDEF, byte for byte: `resources/cdef/` holds a picture MPEG-2 left
 * ringing and what dav1d 1.5.0's filter made of it, run by `harness.c` (`make-expected.sh`).
 */
class CdefTest {

    private class Rgb(val width: Int, val height: Int, val bytes: ByteArray)

    private fun ppm(name: String): Rgb {
        val data = requireNotNull(CdefTest::class.java.getResourceAsStream("/cdef/$name")) { name }
            .use { it.readBytes() }
        val header = String(data, 0, 20, Charsets.US_ASCII).split(Regex("\\s+"))
        val width = header[1].toInt()
        val height = header[2].toInt()
        return Rgb(width, height, data.copyOfRange(data.size - width * height * 3, data.size))
    }

    private val input = ppm("input.ppm")

    private fun check(primary: Int, secondary: Int, damping: Int) {
        val name = "$primary-$secondary-$damping.ppm"
        val expected = ppm(name).bytes
        val changed = expected.indices.count { expected[it] != input.bytes[it] }
        assertTrue("$name: dav1d changed only $changed values", changed > expected.size / 50)
        val ours = Cdef.apply(input.bytes, input.width, input.height, Cdef.Params(primary, secondary, damping))
        assertArrayEquals(name, expected, ours)
    }

    @Test
    fun `primary and secondary together agree with dav1d`() = check(8, 2, 5)

    @Test
    fun `the strongest setting agrees`() = check(15, 4, 6)

    @Test
    fun `primary alone agrees`() = check(4, 0, 3)

    @Test
    fun `secondary alone agrees`() = check(0, 2, 4)

    @Test
    fun `odd primary strengths take the other tap, and agree`() = check(3, 1, 4)

    @Test
    fun `a vertical line's direction is found, and nothing varies across a flat block`() {
        // Pixels varying across columns only: their lines run down, direction 6 in AV1's numbering (2 is across).
        val (dir, variance) = Cdef.direction { x, _ -> if (x < 4) 60 else 200 }
        assertEquals(6, dir)
        assertTrue(variance > 0)
        assertEquals(0, Cdef.direction { _, _ -> 90 }.second)
        assertEquals(0, Cdef.adjustedStrength(8, 0))
    }

    @Test
    fun `a cropped picture is deringed on the grid of the picture it was cut from`() {
        val left = 3
        val top = 5
        val w = input.width - left
        val h = input.height - top
        val cut = ByteArray(w * h * 3) { i ->
            input.bytes[((i / 3 / w + top) * input.width + i / 3 % w + left) * 3 + i % 3]
        }
        val params = Cdef.Params(8, 2, 5)
        val whole = Cdef.apply(input.bytes, input.width, input.height, params)
        val part = Cdef.apply(cut, w, h, params, (8 - left) % 8, (8 - top) % 8)
        // The blocks wholly inside the cut, away from its edges, are the same pixels.
        for (y in 3 + 2 until h - 8) for (x in 5 + 2 until w - 8) for (c in 0..2) {
            assertEquals("($x, $y)", whole[((y + top) * input.width + x + left) * 3 + c], part[(y * w + x) * 3 + c])
        }
    }

    @Test
    fun `the slider runs from off to AV1's strongest`() {
        assertNull(Cdef.params(0f))
        assertEquals(Cdef.Params(15, 4, 6), Cdef.params(1f))
        val weak = requireNotNull(Cdef.params(0.05f))
        assertEquals(1, weak.primary)
        assertEquals(0, weak.secondary)
    }
}
