package com.hawkynt.mediadroid.library

import org.junit.Assert.assertEquals
import org.junit.Test

class MatroskaChaptersTest {

    @Test
    fun readsChaptersFromARealMkvmergeFile() {
        // matroska-chapters.mkv was muxed with mkvmerge 9x from a 4 s black frame
        // and an OGM chapter file: Chapter 1 @0, Intro @1s, Real start @2s,
        // End credits @3.5s. mkvmerge writes no ChapterTimeEnd, so every end
        // except the last is derived from the next atom.
        val bytes = MatroskaChaptersTest::class.java.getResourceAsStream("/matroska-chapters.mkv")!!.readBytes()
        val chapters = MatroskaChapters.parse(bytes)
        assertEquals(
            listOf("Chapter 1" to 0L, "Intro" to 1000L, "Real start" to 2000L, "End credits" to 3500L),
            chapters.map { it.title to it.startMs },
        )
        assertEquals(listOf(1000L, 2000L, 3500L, -1L), chapters.map { it.endMs })
    }

    @Test
    fun readsCraftedAtomsWithExplicitEndsAndHiddenSkipped() {
        val atom1 = atom(startNs = 2_000_000_000, endNs = null, title = "Intro")
        val atom2 = atom(startNs = 5_000_000_000, endNs = 8_000_000_000, title = "End credits")
        val hidden = byteArrayOf(0x98.toByte(), 0x81.toByte(), 0x01) // ChapterFlagHidden = 1
        val atom3 = element(0xB6, concat(startBytes(9_000_000_000), hidden, display("Nope")))
        val edition = element(0x45B9, concat(atom1, atom2, atom3))
        val chapters = element(0x1043A770, edition)
        val segment = elementUnknownSize(0x18538067, chapters)

        val parsed = MatroskaChapters.parse(concat(header(), segment))
        assertEquals(listOf("Intro" to 2000L, "End credits" to 5000L), parsed.map { it.title to it.startMs })
        assertEquals(listOf(5000L, 8000L), parsed.map { it.endMs })
    }

    @Test
    fun fileWithoutChaptersYieldsNothing() {
        val segment = elementUnknownSize(0x18538067, element(0x114D9B74, byteArrayOf(0, 1, 2))) // Infos with junk
        assertEquals(emptyList<Chapter>(), MatroskaChapters.parse(concat(header(), segment)))
    }

    // --- tiny EBML writers for the crafted cases -----------------------------------

    private fun header(): ByteArray =
        element(0x1A45DFA3, byteArrayOf(0x42, 0x82.toByte(), 0x86.toByte(), 0x44, 0x81.toByte(), 0x02))

    private fun atom(startNs: Long, endNs: Long?, title: String): ByteArray {
        val parts = mutableListOf(startBytes(startNs))
        if (endNs != null) parts += elementUint(0x92, endNs)
        parts += display(title)
        return element(0xB6, concat(*parts.toTypedArray()))
    }

    private fun startBytes(startNs: Long): ByteArray = elementUint(0x91, startNs)

    private fun display(title: String): ByteArray =
        element(0x80, element(0x85, title.toByteArray(Charsets.UTF_8)))

    private fun element(id: Long, payload: ByteArray): ByteArray =
        concat(idBytes(id), sizeBytes(payload.size), payload)

    private fun elementUint(id: Long, value: Long): ByteArray {
        val bytes = generateSequence(value) { it ushr 8 }.takeWhile { it > 0 }.toList().reversed()
            .map { it.toByte() }.toByteArray()
        return concat(idBytes(id), sizeBytes(bytes.size), if (bytes.isEmpty()) byteArrayOf(0) else bytes)
    }

    private fun elementUnknownSize(id: Long, payload: ByteArray): ByteArray =
        concat(idBytes(id), byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1), payload)

    private fun idBytes(id: Long): ByteArray = when {
        id >= 0x10000000L -> byteArrayOf((id shr 24).toByte(), (id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
        id >= 0x10000L -> byteArrayOf((id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
        id >= 0x100L -> byteArrayOf((id shr 8).toByte(), id.toByte())
        else -> byteArrayOf(id.toByte())
    }

    private fun sizeBytes(size: Int): ByteArray = when {
        size < 0x7F -> byteArrayOf((0x80 or size).toByte())
        size < 0x3FFF -> byteArrayOf((0x40 or (size shr 8)).toByte(), size.toByte())
        else -> byteArrayOf((0x20 or (size shr 16)).toByte(), (size shr 8).toByte(), size.toByte())
    }

    private fun concat(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { acc, p -> acc + p }
}
