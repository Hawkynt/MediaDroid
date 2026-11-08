package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeletextTest {
    @Test
    fun `Hamming 8-4 codes are the standard's, single errors are corrected and double ones refused`() {
        // The 16 code bytes as EN 300 706's encoding rule gives them, least significant bit sent first.
        val codes = intArrayOf(
            0x15, 0x02, 0x49, 0x5E, 0x64, 0x73, 0x38, 0x2F, 0xD0, 0xC7, 0x8C, 0x9B, 0xA1, 0xB6, 0xFD, 0xEA,
        )
        for (value in 0 until 16) {
            assertEquals(codes[value], Teletext.hammingCode(value))
            assertEquals(value, Teletext.hamming84(codes[value]))
            for (bit in 0 until 8) {
                assertEquals(value, Teletext.hamming84(codes[value] xor (1 shl bit)))
                for (other in bit + 1 until 8) {
                    assertEquals(-1, Teletext.hamming84(codes[value] xor (1 shl bit) xor (1 shl other)))
                }
            }
        }
    }

    @Test
    fun `a teletext descriptor lists its pages`() {
        val body = "deu".toByteArray() + byteArrayOf(0x10, 0x88.toByte()) +
            "eng".toByteArray() + byteArrayOf(0x2A, 0x77) +
            "fra".toByteArray() + byteArrayOf(0x09, 0x00)
        val pages = Teletext.pages(body)
        assertEquals(
            listOf(
                Teletext.Page("deu", Teletext.TYPE_SUBTITLE, 8, 0x88),
                Teletext.Page("eng", Teletext.TYPE_SUBTITLE_HARD_OF_HEARING, 2, 0x77),
                Teletext.Page("fra", 1, 1, 0x00),
            ),
            pages,
        )
        assertEquals(listOf("888", "277", "100"), pages.map { it.label })
        assertEquals(listOf(true, true, false), pages.map { it.isSubtitles })
        assertEquals(0x888, pages[0].id)
        // In an ES info loop, after an ISO 639 descriptor.
        val loop = byteArrayOf(0x0A, 4) + "deu".toByteArray() + byteArrayOf(0, 0x56, 5) + body.copyOf(5)
        assertEquals(listOf(pages[0]), Teletext.pagesInDescriptors(loop))
    }

    @Test
    fun `FFmpeg's record of the descriptor gives the same pages, and a page's track id names it`() {
        val pages = Teletext.ffmpegPages(byteArrayOf(0x10, 0x88.toByte(), 0x2F, 0x77), "deu,eng")
        assertEquals(
            listOf(
                Teletext.Page("deu", Teletext.TYPE_SUBTITLE, 8, 0x88),
                Teletext.Page("eng", Teletext.TYPE_SUBTITLE_HARD_OF_HEARING, 7, 0x77),
            ),
            pages,
        )
        assertEquals("   ", Teletext.ffmpegPages(byteArrayOf(0x10, 0x88.toByte()), null).single().language)
        assertEquals("3:2184", Teletext.trackId(3, pages[0]))
        assertEquals(3 to 0x888, Teletext.ofTrackId("3:2184"))
        assertNull(Teletext.ofTrackId("3"))
        assertNull(Teletext.ofTrackId("sidecar:1"))
    }

    @Test
    fun `the playback graph names the format`() =
        assertEquals("DVB teletext", PlaybackChain.subtitleFormat(Teletext.MIME_TYPE))

    @Test
    fun `the national option sub-sets are table 36's thirteen characters each`() {
        assertEquals(13, Teletext.nationalSets.size)
        Teletext.nationalSets.forEach { (name, set) -> assertEquals(name, 13, set.codePointCount(0, set.length)) }
        assertEquals("Ä", Teletext.character(0x5B, 1))
        assertEquals("ß", Teletext.character(0x7E, 1))
        assertEquals("£", Teletext.character(0x23, 0))
        assertEquals("é", Teletext.character(0x23, 4))
        assertEquals("ñ", Teletext.character(0x7C, 5))
        assertEquals("A", Teletext.character(0x41, 1))
    }

    @Test
    fun `a subtitle page shows its boxed lines with their colours and national characters`() {
        val decoder = Teletext.Decoder(0x888)
        val shown = decoder.feed(
            pes(
                header(8, 0x88, erase = true, subtitle = true, option = 1),
                row(8, 20, listOf(0x0B, 0x0B, 0x03) + text("Hallo") + listOf(0x0A, 0x0A)),
                row(8, 22, listOf(0x0D, 0x0B, 0x0B) + text("Gr") + listOf(0x7C, 0x7E) + text("e") + listOf(0x0A, 0x0A)),
                // Under a double height row: not shown.
                row(8, 23, listOf(0x0B, 0x0B) + text("hidden") + listOf(0x0A)),
                // Outside a box: not shown on a subtitle page.
                row(8, 1, text("Unboxed")),
            ),
        )!!
        assertEquals("Hallo\nGröße", shown.text)
        assertEquals(listOf(20, 22), shown.lines.map { it.row })
        assertEquals(listOf(Teletext.Span("Hallo", 0xFFFF00)), shown.lines[0].spans)
        assertEquals(0xFFFFFF, shown.lines[1].spans.single().colour)
        // The same again: nothing new to show.
        assertNull(decoder.feed(pes(header(8, 0x88, erase = false, subtitle = true, option = 1))))
        // Cleared by the next transmission of the page with nothing in it.
        assertEquals(Teletext.Shown(emptyList()), decoder.feed(pes(header(8, 0x88, erase = true, subtitle = true))))
    }

    @Test
    fun `rows count only while the page is being transmitted in its own magazine`() {
        val decoder = Teletext.Decoder(0x888)
        // Another page of magazine 8 is on air: its rows are not ours.
        assertNull(decoder.feed(pes(header(8, 0x01, erase = true, subtitle = false), row(8, 20, text("Index")))))
        decoder.feed(pes(header(8, 0x88, erase = true, subtitle = false), row(8, 20, text("Ours"))))
        // A page of another magazine (parallel mode) does not end ours; a row of that magazine is not ours.
        val shown = decoder.feed(
            pes(header(1, 0x00, erase = true, subtitle = false), row(1, 21, text("Other")), row(8, 21, text("More"))),
        )!!
        assertEquals("Ours\nMore", shown.text)
        // The next page in magazine 8 ends it.
        assertNull(decoder.feed(pes(header(8, 0x89, erase = true, subtitle = false), row(8, 22, text("Next")))))
    }

    @Test
    fun `a character with a parity error shows as a space and a damaged address is passed over`() {
        val decoder = Teletext.Decoder(0x888)
        // 'B' sent with even parity: an error.
        val codes = listOf(odd('A'.code), 'B'.code, odd('C'.code))
        val shown = decoder.feed(
            pes(header(8, 0x88, erase = true, subtitle = false), row(8, 20, codes, parity = false)),
        )
        assertEquals("A C", shown!!.text)
        // Two bits of the address's first byte wrong: refused, not guessed.
        val broken = row(8, 21, text("Lost")).also { it[4] = sent(Teletext.hammingCode(0) xor 0x03) }
        assertNull(decoder.feed(pes(broken)))
    }

    private companion object {
        fun text(value: String): List<Int> = value.map { it.code }

        /** A byte with odd parity in bit 8. */
        fun odd(code: Int): Int = if (Integer.bitCount(code and 0x7F) % 2 == 0) code or 0x80 else code and 0x7F

        /** A teletext byte as the PES carries it: bits in the order sent. */
        fun sent(byte: Int): Byte = (Integer.reverse(byte and 0xFF) ushr 24).toByte()

        /** A data unit: unit id, length, line offset, framing code, the address, the 40 bytes. */
        fun unit(magazine: Int, packet: Int, block: List<Int>): ByteArray {
            val address1 = Teletext.hammingCode((magazine and 7) or (packet and 1 shl 3))
            val address2 = Teletext.hammingCode(packet shr 1)
            val data = ByteArray(46)
            data[0] = Teletext.UNIT_SUBTITLE.toByte()
            data[1] = Teletext.UNIT_LENGTH.toByte()
            data[2] = 0xC7.toByte()
            data[3] = Teletext.FRAMING_CODE.toByte()
            data[4] = sent(address1)
            data[5] = sent(address2)
            for (i in 0 until 40) data[6 + i] = sent(block.getOrElse(i) { odd(0x20) })
            return data
        }

        fun header(magazine: Int, page: Int, erase: Boolean, subtitle: Boolean, option: Int = 0): ByteArray {
            val nibbles = listOf(
                page and 0xF, page shr 4, 0, if (erase) 8 else 0, 0, if (subtitle) 8 else 0, 0,
                (option shr 2 and 1 shl 1) or (option shr 1 and 1 shl 2) or (option and 1 shl 3),
            )
            return unit(magazine, 0, nibbles.map(Teletext::hammingCode) + List(32) { odd(0x20) })
        }

        fun row(magazine: Int, number: Int, codes: List<Int>, parity: Boolean = true): ByteArray =
            unit(magazine, number, codes.map { if (parity) odd(it) else it } + List(40 - codes.size) { odd(0x20) })

        fun pes(vararg units: ByteArray): ByteArray = byteArrayOf(0x10) + units.fold(ByteArray(0)) { a, b -> a + b }
    }
}
