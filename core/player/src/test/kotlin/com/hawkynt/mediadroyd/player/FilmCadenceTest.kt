package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

/** Film's choice per frame: 2:2 at PAL's rate on the PAL clip, 3:2 at NTSC's on the telecined one, cycles of five. */
class FilmCadenceTest {

    private fun frames(path: String, width: Int, height: Int): List<Deinterlacer.Plane> {
        val bytes = requireNotNull(javaClass.getResourceAsStream(path)).use { GZIPInputStream(it).readBytes() }
        val size = width * height
        return (0 until bytes.size / size).map { k ->
            Deinterlacer.Plane(width, height, IntArray(size) { bytes[k * size + it].toInt() and 0xFF })
        }
    }

    private fun verdicts(frames: List<Deinterlacer.Plane>, intervalNs: Long): List<FilmCadence.Verdict> {
        val film = FilmCadence()
        val last = frames.size - 1
        return frames.indices.map { k ->
            film.add(frames.getOrNull(k - 1), frames[k], frames[minOf(k + 1, last)], true, intervalNs)
        }
    }

    @Test
    fun `at 25 frames a second 2-2 film is woven, anything else deinterlaced, and nothing dropped`() {
        val frames = frames("/pulldown/pal.gray.gz", 256, 192)
        val detector = Pulldown22()
        val pairings = frames.indices.map { detector.add(frames.getOrNull(it - 1), frames[it], true) }
        val verdicts = verdicts(frames, 40_000_000L)
        verdicts.zip(pairings).forEachIndexed { k, (verdict, pairing) ->
            val expected = when (pairing) {
                Pulldown22.Pairing.VIDEO -> Telecine.Match.CURRENT to true
                Pulldown22.Pairing.IN_FRAME -> Telecine.Match.CURRENT to false
                Pulldown22.Pairing.ACROSS -> Telecine.Match.PREVIOUS to false
            }
            assertEquals("frame $k", expected, verdict.match to verdict.combed)
            assertEquals("frame $k", 1, verdict.field)
        }
        // A cycle closes every five frames, and keeps all five.
        assertEquals((4 until frames.size step 5).toList(), verdicts.indices.filter { verdicts[it].cycle != null })
        assertTrue(verdicts.mapNotNull { it.cycle }.all { it.drop == -1 })
    }

    @Test
    fun `at 30 frames a second fields are matched and the repeated frame dropped, as FFmpeg's filters do`() {
        val frames = frames("/telecine/telecined.gray.gz", 128, 96)
        val matcher = Telecine.FieldMatch(true)
        val decimate = Telecine.Decimate(mixed = true, duplicatePercent = FilmCadence.DUPLICATE_PERCENT)
        val last = frames.size - 1
        val expected = frames.indices.map { k ->
            val (matched, woven) = matcher.add(frames[maxOf(k - 1, 0)], frames[k], frames[minOf(k + 1, last)])
            Triple(matched.match, matched.combed, decimate.add(woven)?.drop)
        }
        val verdicts = verdicts(frames, 33_366_666L)
        assertEquals(expected, verdicts.map { Triple(it.match, it.combed, it.cycle?.drop) })
        assertTrue(verdicts.mapNotNull { it.cycle }.any { it.drop >= 0 })
    }
}
