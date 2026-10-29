package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * Field matching and decimation against FFmpeg 6.0's `fieldmatch` and `decimate` on a 3:2-pulldown
 * clip: the debug line FFmpeg prints for every frame and cycle, word for word. `resources/telecine/` holds
 * the clip, written by `make-input.py` there, and FFmpeg's lines, made with the commands in its header.
 */
class TelecineTest {

    private val width = 128
    private val height = 96

    private fun text(name: String) =
        String(requireNotNull(javaClass.getResourceAsStream("/telecine/$name")) { name }.use { it.readBytes() })
            .lines().filter { it.isNotBlank() }

    private fun frames(name: String): List<Deinterlacer.Plane> {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/telecine/$name"))
            .use { GZIPInputStream(it).readBytes() }
        val size = width * height
        return (0 until bytes.size / size).map { k ->
            Deinterlacer.Plane(width, height, IntArray(size) { bytes[k * size + it].toInt() and 0xFF })
        }
    }

    /**
     * FFmpeg's window: frame k between k - 1 and k + 1, the first frame standing in for its predecessor
     * and the last for its successor.
     */
    private fun matched(topFirst: Boolean = true): List<Pair<Telecine.Matched, Deinterlacer.Plane>> {
        val frames = frames(if (topFirst) "telecined.gray.gz" else "telecined-bff.gray.gz")
        val matcher = Telecine.FieldMatch(topFirst)
        val last = frames.size - 1
        return frames.indices.map { k -> matcher.add(frames[maxOf(k - 1, 0)], frames[k], frames[minOf(k + 1, last)]) }
    }

    @Test
    fun `every frame is matched as FFmpeg matches it`() {
        assertEquals(text("fieldmatch.txt"), matched().map { it.first.log })
    }

    @Test
    fun `bottom field first, every frame is matched as FFmpeg matches it`() {
        assertEquals(text("fieldmatch-bff.txt"), matched(topFirst = false).map { it.first.log })
    }

    @Test
    fun `the repeated frame of every cycle is dropped as FFmpeg drops it`() {
        val decimate = Telecine.Decimate()
        val cycles = matched().mapNotNull { decimate.add(it.second) }
        // FFmpeg's log lines lose their indent to the prefix it puts before them.
        assertEquals(
            text("decimate.txt").take(cycles.size * (Telecine.CYCLE + 1)),
            cycles.flatMap { it.log }.map { it.trim() },
        )
        assertEquals(6, cycles.size)
    }

    @Test
    fun `matching the previous frame weaves its field into the current one`() {
        val prev = Deinterlacer.Plane(16, 4, IntArray(64) { 1 })
        val cur = Deinterlacer.Plane(16, 4, IntArray(64) { 2 })
        val woven = Telecine.weave(Telecine.Match.PREVIOUS, 1, prev, cur, cur)
        assertEquals(listOf(2, 1, 2, 1), (0 until 4).map { woven[0, it] })
    }
}
