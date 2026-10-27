package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameSplitterTest {

    /** A toy format: sync byte 0x7F, then one byte holding the frame's total length; every frame lasts 32 ms. */
    private val probe = { bytes: ByteArray, at: Int, available: Int ->
        when {
            bytes[at] != 0x7F.toByte() -> FrameSplitter.Probe.NotAFrame
            available < 2 -> FrameSplitter.Probe.NeedMore
            else -> FrameSplitter.Probe.Frame(size = bytes[at + 1].toInt(), durationUs = 32_000)
        }
    }

    private fun frame(length: Int, fill: Int) =
        ByteArray(length) { if (it == 0) 0x7F else if (it == 1) length.toByte() else fill.toByte() }

    private data class Out(val bytes: List<Byte>, val timeUs: Long)

    private fun feed(splitter: FrameSplitter, chunks: List<Pair<ByteArray, Long>>): List<Out> {
        val out = ArrayList<Out>()
        for ((chunk, timeUs) in chunks) {
            splitter.append(chunk, 0, chunk.size)
            splitter.chunkEnded(timeUs, chunk.size) { bytes, offset, size, frameTimeUs ->
                out += Out(bytes.copyOfRange(offset, offset + size).toList(), frameTimeUs)
            }
        }
        return out
    }

    @Test
    fun chunksThatCutThroughFramesComeOutAsWholeFrames() {
        val stream = frame(10, 1) + frame(12, 2) + frame(9, 3)
        // AVI-style chunks of 7 bytes, each stamped with the time its first byte plays at.
        val chunks = stream.toList().chunked(7).mapIndexed { i, c -> c.toByteArray() to i * 1000L }
        val out = feed(FrameSplitter(probe), chunks)
        assertEquals(listOf(10, 12, 9), out.map { it.bytes.size })
        assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), out.map { it.bytes.last() })
        // The first frame takes the stream's start time; each next one follows by a frame's duration.
        assertEquals(listOf(0L, 32_000L, 64_000L), out.map { it.timeUs })
    }

    @Test
    fun aChunkThatStartsOnAFrameResynchronisesTheClock() {
        val chunks = listOf(frame(10, 1) to 0L, frame(10, 2) to 500_000L)
        val out = feed(FrameSplitter(probe), chunks)
        assertEquals(listOf(0L, 500_000L), out.map { it.timeUs })
    }

    @Test
    fun junkBeforeASyncIsSkipped() {
        val out = feed(FrameSplitter(probe), listOf((byteArrayOf(0, 1, 2) + frame(8, 5)) to 0L))
        assertEquals(1, out.size)
        assertEquals(8, out[0].bytes.size)
    }

    @Test
    fun resetForgetsAHalfFrameAfterASeek() {
        val splitter = FrameSplitter(probe)
        val half = frame(10, 1).copyOf(6)
        feed(splitter, listOf(half to 0L))
        splitter.reset()
        val out = feed(splitter, listOf(frame(10, 9) to 90_000L))
        assertEquals(listOf(90_000L), out.map { it.timeUs })
        assertEquals(9.toByte(), out.single().bytes.last())
    }
}
