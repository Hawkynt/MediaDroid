package com.hawkynt.mediadroid.library

import java.io.File

/**
 * Chapters of an MP4 / QuickTime file, in either of the two forms muxers write: Nero's `moov/udta/chpl`
 * list (start in 100 ns units, UTF-8 title) and a QuickTime chapter track — a text track another track
 * points at through `tref/chap`, one title sample per chapter. Converted from FFmpeg's `mov_read_chpl`
 * and `mov_read_chapters` (libavformat/mov.c, LGPL-2.1-or-later); box layouts are ISO/IEC 14496-12's.
 *
 * Only the movie header is read: top-level boxes are skipped by their size, so a `moov` behind
 * gigabytes of `mdat` costs a few seeks.
 */
object Mp4Chapters {

    /** A larger movie header is no library file's; refusing it keeps a broken file from eating memory. */
    private const val MAX_MOOV_BYTES = 64 * 1024 * 1024
    private const val NERO_TICKS_PER_MS = 10_000L

    /** Box types that open an MP4 / QuickTime file. */
    private val topLevelTypes = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "pnot", "uuid")

    /** Whether [head], the first bytes of a file, look like an MP4 / QuickTime box. */
    fun accepts(head: ByteArray): Boolean = head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in topLevelTypes

    fun parse(file: File): List<Chapter> = parse(LocalFile(file))

    fun parse(file: FsFile): List<Chapter> = runCatching {
        SeekableFile(file).use { raf ->
            val moov = readMoov(raf) ?: return@use emptyList()
            nero(moov).ifEmpty { quickTime(moov, raf) }
        }
    }.getOrDefault(emptyList())

    internal class Box(val type: String, val start: Int, val end: Int)

    /** The body of the top-level `moov` box. */
    internal fun readMoov(raf: SeekableFile): Bytes? {
        val length = raf.length()
        var pos = 0L
        val header = ByteArray(16)
        while (pos + 8 <= length) {
            raf.seek(pos)
            raf.readFully(header, 0, 8)
            var size = u32(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8
            if (size == 1L) {
                raf.readFully(header, 8, 8)
                size = u64(header, 8)
                headerSize = 16
            } else if (size == 0L) {
                size = length - pos
            }
            if (size < headerSize) return null
            if (type == "moov") {
                val bodySize = size - headerSize
                if (bodySize > MAX_MOOV_BYTES) return null
                val body = ByteArray(bodySize.toInt())
                raf.readFully(body)
                return Bytes(body)
            }
            pos += size
        }
        return null
    }

    // ----- Nero ------------------------------------------------------------------------------------

    private fun nero(moov: Bytes): List<Chapter> {
        val udta = moov.children(0, moov.size).firstOrNull { it.type == "udta" } ?: return emptyList()
        val chpl = moov.children(udta.start, udta.end).firstOrNull { it.type == "chpl" } ?: return emptyList()
        var p = chpl.start
        val version = moov.u8(p)
        p += 4
        if (version != 0) p += 4
        val count = moov.u8(p++)
        val starts = ArrayList<Pair<Long, String>>()
        repeat(count) {
            if (p + 9 > chpl.end) return@repeat
            val start = moov.u64(p) / NERO_TICKS_PER_MS
            val length = moov.u8(p + 8)
            p += 9
            if (p + length > chpl.end) return@repeat
            starts += start to moov.string(p, length, Charsets.UTF_8)
            p += length
        }
        return withEnds(starts, lastEnd = -1)
    }

    // ----- QuickTime chapter track ------------------------------------------------------------------

    private fun quickTime(moov: Bytes, raf: SeekableFile): List<Chapter> {
        val tracks = moov.children(0, moov.size).filter { it.type == "trak" }
        val chapterIds = tracks.flatMap { trak ->
            val tref = moov.child(trak, "tref") ?: return@flatMap emptyList()
            val chap = moov.child(tref, "chap") ?: return@flatMap emptyList()
            (chap.start until chap.end step 4).map { moov.u32(it) }
        }.toSet()
        if (chapterIds.isEmpty()) return emptyList()
        val track = tracks.firstOrNull { trackId(moov, it) in chapterIds } ?: return emptyList()

        val mdia = moov.child(track, "mdia") ?: return emptyList()
        val mdhd = moov.child(mdia, "mdhd") ?: return emptyList()
        val timescale = if (moov.u8(mdhd.start) == 1) moov.u32(mdhd.start + 20) else moov.u32(mdhd.start + 12)
        if (timescale <= 0) return emptyList()
        val stbl = moov.child(mdia, "minf")?.let { moov.child(it, "stbl") } ?: return emptyList()

        val durations = sampleDurations(moov, moov.child(stbl, "stts") ?: return emptyList())
        val offsets = sampleOffsets(moov, stbl) ?: return emptyList()
        val starts = ArrayList<Pair<Long, String>>()
        var time = 0L
        for ((i, offset) in offsets.withIndex()) {
            val title = readTitle(raf, offset)
            if (title != null) starts += time * 1000 / timescale to title
            time += durations.getOrElse(i) { 0L }
        }
        return withEnds(starts, lastEnd = time * 1000 / timescale)
    }

    private fun trackId(moov: Bytes, trak: Box): Long? {
        val tkhd = moov.child(trak, "tkhd") ?: return null
        return if (moov.u8(tkhd.start) == 1) moov.u32(tkhd.start + 20) else moov.u32(tkhd.start + 12)
    }

    /** Duration of every sample, from the time-to-sample table. */
    private fun sampleDurations(moov: Bytes, stts: Box): List<Long> {
        val entries = moov.u32(stts.start + 4).toInt()
        val out = ArrayList<Long>()
        for (e in 0 until entries) {
            val at = stts.start + 8 + e * 8
            if (at + 8 > stts.end) break
            val count = moov.u32(at)
            val delta = moov.u32(at + 4)
            for (k in 0 until minOf(count, 100_000L)) out += delta
        }
        return out
    }

    /** File offset of every sample: chunk offsets, split into samples by sample-to-chunk and sample sizes. */
    private fun sampleOffsets(moov: Bytes, stbl: Box): List<Long>? {
        val stsz = moov.child(stbl, "stsz") ?: return null
        val uniform = moov.u32(stsz.start + 4)
        val sampleCount = moov.u32(stsz.start + 8).toInt()
        fun size(i: Int): Long = if (uniform != 0L) uniform else moov.u32(stsz.start + 12 + i * 4)

        val stco = moov.child(stbl, "stco")
        val co64 = moov.child(stbl, "co64")
        val chunkOffsets: List<Long> = when {
            stco != null -> (0 until moov.u32(stco.start + 4).toInt()).map { moov.u32(stco.start + 8 + it * 4) }
            co64 != null -> (0 until moov.u32(co64.start + 4).toInt()).map { moov.u64(co64.start + 8 + it * 8) }
            else -> return null
        }
        val stsc = moov.child(stbl, "stsc") ?: return null
        val runs = (0 until moov.u32(stsc.start + 4).toInt()).map {
            val at = stsc.start + 8 + it * 12
            moov.u32(at).toInt() to moov.u32(at + 4).toInt() // first chunk (1-based), samples per chunk
        }
        val offsets = ArrayList<Long>()
        var sample = 0
        for ((chunkIndex, chunkOffset) in chunkOffsets.withIndex()) {
            val perChunk = runs.lastOrNull { it.first <= chunkIndex + 1 }?.second ?: break
            var offset = chunkOffset
            repeat(perChunk) {
                if (sample < sampleCount) {
                    offsets += offset
                    offset += size(sample)
                    sample++
                }
            }
        }
        return offsets
    }

    /** A text sample: a 16-bit length, then the title in UTF-8 or, after a byte-order mark, UTF-16. */
    private fun readTitle(raf: SeekableFile, offset: Long): String? {
        if (offset < 0 || offset + 2 > raf.length()) return null
        raf.seek(offset)
        val length = raf.readUnsignedShort()
        if (offset + 2 + length > raf.length()) return null
        val bytes = ByteArray(length)
        raf.readFully(bytes)
        return when {
            length >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, length - 2, Charsets.UTF_16BE)
            length >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, length - 2, Charsets.UTF_16LE)
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    /** Chapters from (start, title) pairs: each ends where the next starts, the last at [lastEnd]. */
    private fun withEnds(starts: List<Pair<Long, String>>, lastEnd: Long): List<Chapter> =
        starts.sortedBy { it.first }.let { sorted ->
            sorted.mapIndexed { i, (start, title) ->
                Chapter(start, sorted.getOrNull(i + 1)?.first ?: lastEnd, title)
            }
        }

    // ----- bytes -------------------------------------------------------------------------------------

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)

    internal class Bytes(val data: ByteArray) {
        val size: Int get() = data.size
        fun u8(at: Int): Int = data[at].toInt() and 0xFF
        fun u32(at: Int): Long = u32(data, at)
        fun u64(at: Int): Long = u64(data, at)
        fun string(at: Int, length: Int, charset: java.nio.charset.Charset) = String(data, at, length, charset)

        /** The boxes directly inside [start] until [end]. */
        fun children(start: Int, end: Int): List<Box> {
            val out = ArrayList<Box>()
            var pos = start
            while (pos + 8 <= end) {
                var size = u32(pos)
                val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
                var header = 8
                if (size == 1L) {
                    if (pos + 16 > end) break
                    size = u64(pos + 8)
                    header = 16
                } else if (size == 0L) {
                    size = (end - pos).toLong()
                }
                if (size < header || pos + size > end) break
                out += Box(type, pos + header, (pos + size).toInt())
                pos += size.toInt()
            }
            return out
        }

        fun child(parent: Box, type: String): Box? = children(parent.start, parent.end).firstOrNull { it.type == type }
    }
}
