package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * 2:2 pulldown found and undone on a PAL clip whose answer is known by construction (`resources/pulldown/`, written by
 * `make-input.py` there, which says what every frame is made of): video, film in phase, an edit to film whose frames
 * start on the bottom field, an edit back to video. FFmpeg's idet calls the whole clip top field first.
 */
class Pulldown22Test {

    private val width = 256
    private val height = 192

    private fun frames(name: String): List<Deinterlacer.Plane> {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/pulldown/$name"))
            .use { GZIPInputStream(it).readBytes() }
        val size = width * height
        return (0 until bytes.size / size).map { k ->
            Deinterlacer.Plane(width, height, IntArray(size) { bytes[k * size + it].toInt() and 0xFF })
        }
    }

    private fun verdicts(frames: List<Deinterlacer.Plane>, topFirst: Boolean = true): List<Pulldown22.Pairing> {
        val detector = Pulldown22()
        return frames.indices.map { k -> detector.add(frames.getOrNull(k - 1), frames[k], topFirst) }
    }

    /**
     * Film in phase is found on its third frame, 22: the second fields drop from 20 on. The edit at 45 combs where
     * the bottom field joins the top one, so that frame is video at once; and that field's comb factor lies under 40 %
     * of the edit's before it, a drop itself, so the top field after it cannot count: the drops across count from 47,
     * and film is found again at 49. The edit at 65 is video at once.
     */
    private val expected = List(80) { k ->
        when (k) {
            in 22..44 -> Pulldown22.Pairing.IN_FRAME
            in 49..64 -> Pulldown22.Pairing.ACROSS
            else -> Pulldown22.Pairing.VIDEO
        }
    }

    @Test
    fun `film is found after three drops on the same field, and an edit drops back to video at once`() {
        assertEquals(expected, verdicts(frames("pal.gray.gz")))
    }

    @Test
    fun `every frame film is found in is woven back into its film frame exactly`() {
        val frames = frames("pal.gray.gz")
        val film = frames("pal-film.gray.gz")
        verdicts(frames).forEachIndexed { k, pairing ->
            if (pairing == Pulldown22.Pairing.VIDEO) return@forEachIndexed
            val woven = Pulldown22.weave(pairing, frames[k - 1], frames[k], topFieldFirst = true)
            assertArrayEquals("frame $k", film[k].pixels, woven.pixels)
        }
    }

    @Test
    fun `bottom field first, the same clip turned upside down is found the same`() {
        fun flipped(p: Deinterlacer.Plane) = Deinterlacer.Plane(
            p.width,
            p.height,
            IntArray(p.pixels.size) { i -> p[i % p.width, p.height - 1 - i / p.width] },
        )
        assertEquals(expected, verdicts(frames("pal.gray.gz").map(::flipped), topFirst = false))
    }

    @Test
    fun `a still picture never looks like film`() {
        val still = frames("pal.gray.gz")[30]
        assertTrue(verdicts(List(20) { still }).all { it == Pulldown22.Pairing.VIDEO })
    }

    @Test
    fun `the comb factor counts the pixels a field sticks out of the other between its lines`() {
        // Top rows 0, bottom rows 200: every bottom pixel away from the margins combs; one field alone does not.
        val combed = Deinterlacer.Plane(64, 80, IntArray(64 * 80) { if ((it / 64) % 2 == 0) 0 else 200 })
        val flat = Deinterlacer.Plane(64, 80, IntArray(64 * 80) { 100 })
        // Field lines 16 to 23 of 40 are measured, 32 columns each; the first and last measured line lack a
        // combed neighbour on one side, so six lines count.
        assertEquals(6 * 32, Pulldown22.combFactor(combed, combed, topRowsFromFirst = true))
        assertEquals(0, Pulldown22.combFactor(flat, flat, topRowsFromFirst = true))
    }

    @Test
    fun `25 frames a second takes the 2-2 detector, 30 the 3-2 matcher`() {
        assertTrue(Pulldown22.appliesTo(40_000_000L))
        assertTrue(Pulldown22.appliesTo(41_708_333L))
        assertTrue(!Pulldown22.appliesTo(33_366_666L))
        assertTrue(!Pulldown22.appliesTo(0L))
    }
}
