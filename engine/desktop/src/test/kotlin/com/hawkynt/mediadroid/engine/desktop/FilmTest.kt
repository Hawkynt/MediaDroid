package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.player.ToneMap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmTest {
    private val w = 160
    private val h = 120

    // A film frame: blotches of value noise drifting right, so every frame differs and fields of two frames comb.
    private fun filmFrame(k: Int): ByteArray = ByteArray(w * h * 4) { i ->
        val p = i / 4
        val x = p % w + k * 6
        val y = p / w
        if (i % 4 == 3) {
            -1
        } else {
            val cell = 8
            var v = (x / cell) * 73856093 xor (y / cell) * 19349663 xor (i % 4) * 83492791
            v = (v xor (v ushr 13)) * 0x5bd1e995
            ((v xor (v ushr 15)) and 0xFF).toByte()
        }
    }

    // Video frame [n] of 3:2 pulldown over the film frames: per cycle A/A, B/B, B/C, C/D, D/D (top/bottom field).
    private fun telecined(n: Int, film: (Int) -> ByteArray): MediaReader.Picture {
        val cycle = n / 5
        val (top, bottom) = when (n % 5) {
            0 -> 0 to 0
            1 -> 1 to 1
            2 -> 1 to 2
            3 -> 2 to 3
            else -> 3 to 3
        }
        val t = film(cycle * 4 + top)
        val b = film(cycle * 4 + bottom)
        val out = ByteArray(w * h * 4)
        for (y in 0 until h) System.arraycopy(if (y % 2 == 0) t else b, y * w * 4, out, y * w * 4, w * 4)
        return MediaReader.Picture(n * 1001L / 30, w, h, out)
    }

    @Test
    fun `3-2 pulldown comes back as the film's own frames, four of every five, spread over the cycle`() {
        val frames = HashMap<Int, ByteArray>()
        val film = { k: Int -> frames.getOrPut(k) { filmFrame(k) } }
        val out = Film().let { f -> (0 until 40).flatMap { f.add(telecined(it, film)) } }
        // Seven whole cycles are out; the eighth waits for the picture after it.
        assertEquals(28, out.size)
        val originals = (0 until 40).map(film)
        // Past the first cycle, which has no picture before it, every picture is a film frame, each one once.
        val matched = out.drop(4).map { p -> originals.indexOfFirst { it.contentEquals(p.rgba) } }
        assertTrue("$matched", matched.all { it >= 0 })
        assertEquals((4 until 28).toList(), matched)
        // Evenly over the cycle's own time: five video frames' worth for four pictures.
        val steps = out.zipWithNext { a, b -> b.ptsMs - a.ptsMs }.drop(4)
        assertTrue("$steps", steps.all { it in 40L..43L })
        assertEquals(5 * 1001L / 30, out[4].ptsMs)
    }

    @Test
    fun `2-2 pulldown at 25 frames a second whose film frames start on the bottom field comes back whole`() {
        val frames = HashMap<Int, ByteArray>()
        val film = { k: Int -> frames.getOrPut(k) { filmFrame(k) } }
        // Picture k: the top field of film frame k, the bottom field of film frame k + 1.
        fun picture(k: Int): MediaReader.Picture {
            val t = film(k)
            val b = film(k + 1)
            val out = ByteArray(w * h * 4)
            for (y in 0 until h) System.arraycopy(if (y % 2 == 0) t else b, y * w * 4, out, y * w * 4, w * 4)
            return MediaReader.Picture(k * 40L, w, h, out)
        }
        val out = Film().let { f -> (0 until 40).flatMap { f.add(picture(it)) } }
        // Nothing dropped: seven cycles of five are out, the eighth waits for the picture after it.
        assertEquals(35, out.size)
        // Once film is found every picture is its film frame, whole; until then the pictures are deinterlaced.
        val matched = out.map { p -> (0 until 41).indexOfFirst { film(it).contentEquals(p.rgba) } }
        val found = matched.indexOfFirst { it >= 0 }
        assertTrue("$matched", found in 1..5)
        assertEquals((found until 35).toList(), matched.drop(found))
        assertTrue(out.zipWithNext { a, b -> b.ptsMs - a.ptsMs }.all { it == 40L })
    }

    @Test
    fun `a picture whose fields belong together is kept as it is, a woven one takes its rows from its neighbour`() {
        val a = MediaReader.Picture(0, 4, 4, ByteArray(64) { 1 })
        val b = MediaReader.Picture(40, 4, 4, ByteArray(64) { 2 })
        val woven = Film.weave(a, b, 1)
        assertArrayEquals(ByteArray(16) { 1 }, woven.rgba.copyOfRange(0, 16))
        assertArrayEquals(ByteArray(16) { 2 }, woven.rgba.copyOfRange(16, 32))
        assertEquals(0, woven.ptsMs)
    }

    @Test
    fun `an HDR picture passes untouched`() {
        val hdr = MediaReader.Picture(0, 16, 16, ByteArray(16 * 16 * 8), ToneMap.Transfer.PQ)
        assertSame(hdr, Film().add(hdr).single())
    }
}
