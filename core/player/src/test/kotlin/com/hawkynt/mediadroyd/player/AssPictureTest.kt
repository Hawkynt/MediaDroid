package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AssPictureTest {
    @Test
    fun `the native answer is the box and its pixels`() {
        val picture = AssPicture.of(intArrayOf(3, 4, 2, 1, 0x11223344, 0x55667788))
        assertEquals(3, picture.x)
        assertEquals(4, picture.y)
        assertEquals(2, picture.width)
        assertEquals(1, picture.height)
        assertArrayEquals(intArrayOf(0x11223344, 0x55667788), picture.pixels)
    }

    @Test
    fun `a box of nothing is the empty picture`() {
        assertSame(AssPicture.EMPTY, AssPicture.of(intArrayOf(0, 0, 0, 0)))
        assertTrue(AssPicture.EMPTY.isEmpty)
    }

    @Test
    fun `laid over a frame each pixel blends by its alpha, and what lies outside the frame is left out`() {
        // A 2 × 2 frame of mid grey, opaque; the picture is 2 × 1 at (1, 1): its second pixel falls outside.
        val frame = ByteArray(16) { if (it % 4 == 3) -1 else 0x80.toByte() }
        AssPicture(1, 1, 2, 1, intArrayOf(0x80FF0000.toInt(), 0xFF00FF00.toInt())).blendInto(frame, 2, 2)
        // (255·128 + 128·127 + 127) / 255 = 192; (0·128 + 128·127 + 127) / 255 = 64.
        assertArrayEquals(byteArrayOf(192.toByte(), 64, 64, -1), frame.copyOfRange(12, 16))
        assertArrayEquals(ByteArray(12) { if (it % 4 == 3) -1 else 0x80.toByte() }, frame.copyOfRange(0, 12))
    }

    @Test
    fun `over a transparent frame the alpha is the picture's`() {
        val frame = ByteArray(4)
        AssPicture(0, 0, 1, 1, intArrayOf(0x40FFFFFF)).blendInto(frame, 1, 1)
        assertEquals(0x40, frame[3].toInt() and 0xFF)
    }
}
