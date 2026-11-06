package com.hawkynt.mediadroid.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MatroskaFontsTest {
    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream(name)) { name }.readBytes()

    private fun fontsOf(file: ByteArray, reads: MutableList<LongRange>? = null) =
        MatroskaFonts.read(file.size.toLong()) { at, length ->
            reads?.add(at until at + length)
            file.copyOfRange(at.toInt(), minOf(file.size, (at + length).toInt()))
        }

    @Test
    fun `the fonts FFmpeg attached to a Matroska file come out whole, by name`() {
        // ass/styled.mkv: made by FFmpeg with NotoSans-Basic.ttf attached (see engine/desktop's ass/oracle.sh).
        val fonts = fontsOf(resource("/ass/styled.mkv"))
        assertEquals(listOf("NotoSans-Basic.ttf"), fonts.map { it.first })
        val font = fonts.single().second
        assertEquals(37816, font.size)
        // A TrueType font starts with its version, 1.0.
        assertArrayEquals(byteArrayOf(0, 1, 0, 0), font.copyOfRange(0, 4))
    }

    /** An EBML element: [id]'s bytes as written, its size as a vint of 8 bytes, then [data]. */
    private fun element(id: Long, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val idBytes = generateSequence(id) { it ushr 8 }.takeWhile { it != 0L }.map { it.toByte() }.toList().reversed()
        out.write(idBytes.toByteArray())
        out.write(0x01)
        for (shift in 48 downTo 0 step 8) out.write((data.size.toLong() ushr shift).toInt() and 0xFF)
        out.write(data)
        return out.toByteArray()
    }

    private fun elements(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { all, part -> all + part }

    private fun attachment(name: String, type: String, data: ByteArray) = element(
        0x61A7,
        elements(element(0x466E, name.toByteArray()), element(0x4660, type.toByteArray()), element(0x465C, data)),
    )

    @Test
    fun `attachments after the clusters are found through the seek head, the clusters never read`() {
        val cluster = element(0x1F43B675, ByteArray(10_000))
        val attachments = element(
            0x1941A469,
            elements(
                attachment("Sign.otf", "application/octet-stream", byteArrayOf(1, 2, 3)),
                attachment("cover.jpg", "image/jpeg", byteArrayOf(9)),
                attachment("Dialogue.ttf", "font/ttf", byteArrayOf(4, 5)),
            ),
        )
        // The seek head's position is fixed-size, so the cluster's place can be known before it is written.
        fun seekHead(position: Long) = element(
            0x114D9B74,
            element(0x4DBB, elements(element(0x53AB, byteArrayOf(0x19, 0x41, 0xA4.toByte(), 0x69)),
                element(0x53AC, ByteArray(8) { (position ushr (56 - 8 * it)).toInt().toByte() }))),
        )
        val head = seekHead(0)
        val segmentData = elements(seekHead((head.size + cluster.size).toLong()), cluster, attachments)
        val file = elements(element(0x1A45DFA3, element(0x4282, "matroska".toByteArray())),
            element(0x18538067, segmentData))
        val reads = mutableListOf<LongRange>()
        val fonts = fontsOf(file, reads)
        assertEquals(listOf("Sign.otf", "Dialogue.ttf"), fonts.map { it.first })
        assertArrayEquals(byteArrayOf(4, 5), fonts[1].second)
        val clusterData = file.size - attachments.size - cluster.size + 12 until file.size - attachments.size
        assertTrue(reads.none { it.first in clusterData && it.last in clusterData && it.count() > 64 })
    }

    @Test
    fun `what is no Matroska file, or is cut short, has no fonts`() {
        assertEquals(emptyList<Pair<String, ByteArray>>(), fontsOf("not a video".toByteArray()))
        val whole = resource("/ass/styled.mkv")
        assertEquals(emptyList<Pair<String, ByteArray>>(), fontsOf(whole.copyOfRange(0, 1500)))
        assertEquals(emptyList<Pair<String, ByteArray>>(), fontsOf(ByteArray(0)))
    }
}
