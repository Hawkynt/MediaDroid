package com.hawkynt.mediadroid.player

/**
 * Teletext subtitles as DVB recordings carry them (ETSI EN 300 472): the teletext stream's PES packets, each a run of
 * 44-byte data units holding teletext packets (ETSI EN 300 706), decoded for one page — the one a subtitle track is
 * ([Page], from the stream's teletext descriptor, ETSI EN 300 468 6.2.43) — into the lines it shows ([Shown]).
 * Level 1 only: the G0 Latin set with its national options, the alphanumeric colours and boxes; enhancements (packets
 * X/26 to X/29), mosaics and the other alphabets are left out.
 */
object Teletext {
    /** Codec name FFmpeg gives a DVB teletext stream. */
    const val FFMPEG_CODEC = "dvb_teletext"

    /** The MIME type a teletext page's track carries in Media3 (one of our own: Media3 has none). */
    const val MIME_TYPE = "application/x-mediadroid-teletext"

    /** The teletext descriptor's tag in a PMT's ES info (ETSI EN 300 468, 6.2.43); the VBI teletext descriptor's. */
    const val DESCRIPTOR_TAG = 0x56
    const val VBI_DESCRIPTOR_TAG = 0x46

    /** teletext_type values of a subtitle page: plain, and for the hard of hearing (EN 300 468, table 94). */
    const val TYPE_SUBTITLE = 0x02
    const val TYPE_SUBTITLE_HARD_OF_HEARING = 0x05

    /**
     * A page a teletext descriptor lists: its [language] (ISO 639-2, as the descriptor has it), its [type] and its
     * number — [magazine] 1 to 8 and [number], the page's tens and units as hex digits (0x88 for page 888).
     */
    data class Page(val language: String, val type: Int, val magazine: Int, val number: Int) {
        /** The page as one number, magazine first: 0x888 for page 888. */
        val id: Int get() = magazine shl 8 or number

        /** Whether the descriptor calls it a subtitle page. */
        val isSubtitles: Boolean get() = type == TYPE_SUBTITLE || type == TYPE_SUBTITLE_HARD_OF_HEARING

        /** How a viewer knows it: "888". */
        val label: String get() = "%d%02X".format(magazine, number)
    }

    /**
     * The pages a teletext descriptor's [body] lists (what follows its tag and length): five bytes each — the language
     * code, then five bits of type and three of magazine (0 for 8), then the page number.
     */
    fun pages(body: ByteArray): List<Page> = (0 until body.size / 5).map { i ->
        val at = i * 5
        val language = String(body, at, 3, Charsets.ISO_8859_1)
        val typeAndMagazine = body[at + 3].toInt() and 0xFF
        val magazine = (typeAndMagazine and 7).let { if (it == 0) 8 else it }
        Page(language, typeAndMagazine ushr 3, magazine, body[at + 4].toInt() and 0xFF)
    }

    /**
     * The pages of a teletext stream as FFmpeg's MPEG-TS demuxer keeps its descriptor: two bytes a page in the
     * stream's [extradata] (type and magazine, page number) and their languages in its "language" tag, joined by
     * commas ([languages]).
     */
    fun ffmpegPages(extradata: ByteArray, languages: String?): List<Page> {
        val codes = languages.orEmpty().split(',')
        return (0 until extradata.size / 2).map { i ->
            val language = codes.getOrNull(i)?.trim().orEmpty().padEnd(3).take(3)
            pages(language.toByteArray(Charsets.ISO_8859_1) + extradata.copyOfRange(i * 2, i * 2 + 2)).single()
        }
    }

    /** A page's track id within a stream's: "stream:page" (page magazine first, 0x888 as 2184). */
    fun trackId(stream: Int, page: Page): String = "$stream:${page.id}"

    /** The stream and the page a track id of [trackId]'s names, or null where it names no teletext page. */
    fun ofTrackId(id: String): Pair<Int, Int>? {
        val stream = id.substringBefore(':', "").toIntOrNull() ?: return null
        val page = id.substringAfter(':').toIntOrNull() ?: return null
        return stream to page
    }

    /**
     * The teletext pages in a PMT entry's [descriptors] (the ES info loop: tag, length, body …): those of every
     * teletext and VBI teletext descriptor there.
     */
    fun pagesInDescriptors(descriptors: ByteArray): List<Page> {
        val found = mutableListOf<Page>()
        var at = 0
        while (at + 2 <= descriptors.size) {
            val tag = descriptors[at].toInt() and 0xFF
            val length = descriptors[at + 1].toInt() and 0xFF
            if (at + 2 + length > descriptors.size) break
            if (tag == DESCRIPTOR_TAG || tag == VBI_DESCRIPTOR_TAG) {
                found += pages(descriptors.copyOfRange(at + 2, at + 2 + length))
            }
            at += 2 + length
        }
        return found
    }

    /** One coloured run of a line: its [text] in [colour] (0xRRGGBB). */
    data class Span(val text: String, val colour: Int)

    /** A line the page shows: its display [row] (1 to 23) and its [spans]. */
    data class Line(val row: Int, val spans: List<Span>) {
        val text: String get() = spans.joinToString("") { it.text }
    }

    /** What a page shows now: its [lines] top to bottom, none where it is blank. */
    data class Shown(val lines: List<Line>) {
        /** The lines as plain text, one a line. */
        val text: String get() = lines.joinToString("\n") { it.text }
    }

    /** The eight alphanumeric colours of the spacing attributes 0/0 to 0/7: black, red … white (EN 300 706 12.2). */
    val COLOURS = intArrayOf(0x000000, 0xFF0000, 0x00FF00, 0xFFFF00, 0x0000FF, 0xFF00FF, 0x00FFFF, 0xFFFFFF)

    // A teletext byte as the PES carries it: in the order its bits are sent, the first bit (the byte's least
    // significant) uppermost (EN 300 472 4.4).
    private fun reversed(byte: Int): Int = Integer.reverse(byte and 0xFF) ushr 24

    // Hamming 8/4 (EN 300 706 8.2): the 16 code bytes, computed from the encoding rule; bits 2, 4, 6 and 8 carry D1
    // to D4.
    private val hammingCodes = IntArray(16) { value ->
        val d1 = value and 1
        val d2 = value shr 1 and 1
        val d3 = value shr 2 and 1
        val d4 = value shr 3 and 1
        val p1 = 1 xor d1 xor d3 xor d4
        val p2 = 1 xor d1 xor d2 xor d4
        val p3 = 1 xor d1 xor d2 xor d3
        val p4 = 1 xor p1 xor d1 xor p2 xor d2 xor p3 xor d3 xor d4
        p1 or (d1 shl 1) or (p2 shl 2) or (d2 shl 3) or (p3 shl 4) or (d3 shl 5) or (p4 shl 6) or (d4 shl 7)
    }

    /** A Hamming 8/4 code byte's 4 bits, any single error corrected; -1 where two bits are wrong. */
    fun hamming84(byte: Int): Int {
        for (value in 0 until 16) {
            if (Integer.bitCount(hammingCodes[value] xor (byte and 0xFF)) <= 1) return value
        }
        return -1
    }

    /** [value] (0 to 15) as its Hamming 8/4 code byte. */
    fun hammingCode(value: Int): Int = hammingCodes[value and 15]

    /** The Latin national option sub-sets (EN 300 706 table 36), by name: their 13 characters. */
    val nationalSets: Map<String, String> by lazy {
        val text = checkNotNull(Teletext::class.java.getResourceAsStream("teletext-national.txt")) {
            "teletext-national.txt"
        }.bufferedReader(Charsets.UTF_8).readText()
        text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { it.substringBefore('\t') to it.substringAfter('\t') }
    }

    // The sub-sets C12, C13 and C14 pick with the default G0 designation (EN 300 706 table 32, first group): C12 the
    // most significant. The eighth has none; English stands in.
    private val defaultRegion = listOf(
        "English", "German", "Swedish/Finnish/Hungarian", "Italian", "French", "Portuguese/Spanish", "Czech/Slovak",
        "English",
    )

    // The G0 positions a national option sub-set fills, in table 36's order.
    private val nationalPositions =
        intArrayOf(0x23, 0x24, 0x40, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x60, 0x7B, 0x7C, 0x7D, 0x7E)

    /** The character at G0 [code] (0x20 to 0x7F) under the national option [option] (C12 C13 C14, 0 to 7). */
    fun character(code: Int, option: Int): String {
        val position = nationalPositions.indexOf(code)
        if (position >= 0) {
            val set = nationalSets.getValue(defaultRegion[option and 7])
            return String(Character.toChars(set.codePointAt(set.offsetByCodePoints(0, position))))
        }
        return when (code) {
            0x7F -> "■"
            else -> code.toChar().toString()
        }
    }

    /**
     * One page decoded as its packets arrive ([feed], a PES packet's data at a time): the page [page] (magazine first,
     * 0x888) gathered from its header on; [feed] tells what it shows whenever that has changed.
     */
    class Decoder(private val page: Int) {
        private val magazine = page shr 8 and 7
        private val rows = arrayOfNulls<IntArray>(ROWS)
        private var receiving = false
        private var boxed = false
        private var option = 0
        private var changed = false
        private var lastShown: Shown? = null

        /** Back to nothing received: a seek, or another stream. */
        fun reset() {
            rows.fill(null)
            receiving = false
            changed = false
            lastShown = null
        }

        /**
         * A PES packet's data field ([data] from [offset], [length] bytes — the data identifier first): what the page
         * shows once it is taken in, where that differs from before; else null.
         */
        fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Shown? {
            if (length < 1) return null
            val identifier = data[offset].toInt() and 0xFF
            if (identifier !in 0x10..0x1F) return null
            var at = offset + 1
            val end = offset + length
            while (at + 2 <= end) {
                val unitId = data[at].toInt() and 0xFF
                val unitLength = data[at + 1].toInt() and 0xFF
                at += 2
                if (at + unitLength > end) break
                if ((unitId == UNIT_SUBTITLE || unitId == UNIT_NON_SUBTITLE) && unitLength == UNIT_LENGTH) {
                    packet(data, at)
                }
                at += unitLength
            }
            if (!changed) return null
            changed = false
            val shown = shown()
            if (shown == lastShown) return null
            lastShown = shown
            return shown
        }

        // One data unit's packet at [at]: line offset, framing code, the address and the 40 bytes.
        private fun packet(data: ByteArray, at: Int) {
            if (data[at + 1].toInt() and 0xFF != FRAMING_CODE) return
            val first = hamming84(reversed(data[at + 2].toInt()))
            val second = hamming84(reversed(data[at + 3].toInt()))
            if (first < 0 || second < 0) return
            val packetMagazine = first and 7
            val number = (first shr 3) or (second shl 1)
            val block = at + 4
            if (number == 0) {
                header(data, block, packetMagazine)
            } else if (number in 1 until ROWS && receiving && packetMagazine == magazine) {
                rows[number] = IntArray(COLUMNS) { reversed(data[block + it].toInt()) }
                changed = true
            }
        }

        // A page header in [packetMagazine]: the start of our page, or — in our magazine, or in any where the service
        // runs its magazines in series (C11) — the end of its transmission.
        private fun header(data: ByteArray, block: Int, packetMagazine: Int) {
            val bytes = IntArray(8) { hamming84(reversed(data[block + it].toInt())) }
            if (bytes.any { it < 0 }) return
            val number = bytes[1] shl 4 or bytes[0]
            val serial = bytes[7] and 1 != 0
            if (packetMagazine == magazine && number == page and 0xFF) {
                receiving = true
                if (bytes[3] and 8 != 0) {
                    rows.fill(null)
                    changed = true
                }
                // C5 newsflash or C6 subtitle: only what is boxed shows.
                boxed = bytes[5] and 0xC != 0
                // Byte 13 holds C11 to C14, lowest first; C12 is the option's most significant bit (table 32).
                val c12 = bytes[7] shr 1 and 1
                val c13 = bytes[7] shr 2 and 1
                val c14 = bytes[7] shr 3 and 1
                option = (c12 shl 2) or (c13 shl 1) or c14
                changed = true
            } else if (packetMagazine == magazine || serial) {
                receiving = false
            }
        }

        // The rows' text as it shows: colours and boxes followed, attributes as spaces, the row under a double height
        // row left out.
        private fun shown(): Shown {
            val lines = mutableListOf<Line>()
            var skipNext = false
            for (row in 1 until ROWS) {
                val codes = rows[row]
                if (skipNext || codes == null) {
                    skipNext = false
                    continue
                }
                val spans = mutableListOf<Span>()
                var colour = COLOURS[7]
                var mosaic = false
                var inBox = false
                var doubleHeight = false
                val run = StringBuilder()
                var runColour = colour
                fun put(text: String) {
                    if (colour != runColour && run.isNotEmpty()) {
                        spans += Span(run.toString(), runColour)
                        run.clear()
                    }
                    runColour = colour
                    run.append(text)
                }
                for (code in codes) {
                    // An odd parity error shows as a space (EN 300 706 8.1).
                    val value = if (Integer.bitCount(code) % 2 == 1) code and 0x7F else 0x20
                    val shows = !boxed || inBox
                    if (value < 0x20) {
                        if (shows) put(" ")
                        // Set-After: in effect from the next character on.
                        when (value) {
                            in 0x00..0x07 -> {
                                colour = COLOURS[value]
                                mosaic = false
                            }
                            in 0x10..0x17 -> mosaic = true
                            START_BOX -> inBox = true
                            END_BOX -> inBox = false
                            DOUBLE_HEIGHT, DOUBLE_SIZE -> doubleHeight = true
                        }
                        continue
                    }
                    if (!shows) {
                        put(" ")
                        continue
                    }
                    // Mosaics are pictures, not text; only the capitals "blast through".
                    put(if (mosaic && value !in 0x40..0x5F) " " else character(value, option))
                }
                if (run.isNotEmpty()) spans += Span(run.toString(), runColour)
                val trimmed = trim(spans)
                if (trimmed.isNotEmpty()) lines += Line(row, trimmed)
                skipNext = doubleHeight
            }
            return Shown(lines)
        }

        private fun trim(spans: List<Span>): List<Span> {
            val kept = spans.toMutableList()
            while (kept.isNotEmpty() && kept.first().text.isBlank()) kept.removeAt(0)
            while (kept.isNotEmpty() && kept.last().text.isBlank()) kept.removeAt(kept.lastIndex)
            if (kept.isEmpty()) return kept
            kept[0] = kept[0].copy(text = kept[0].text.trimStart())
            kept[kept.lastIndex] = kept.last().copy(text = kept.last().text.trimEnd())
            return kept
        }
    }

    /** data_unit_id of EBU teletext subtitle and non-subtitle data (EN 300 472 table 4), and their length. */
    const val UNIT_NON_SUBTITLE = 0x02
    const val UNIT_SUBTITLE = 0x03
    const val UNIT_LENGTH = 0x2C

    /** The framing code as the PES carries it: 11100100, in the order sent (EN 300 472 4.4). */
    const val FRAMING_CODE = 0xE4

    private const val ROWS = 24
    private const val COLUMNS = 40
    private const val END_BOX = 0x0A
    private const val START_BOX = 0x0B
    private const val DOUBLE_HEIGHT = 0x0D
    private const val DOUBLE_SIZE = 0x0F
}
