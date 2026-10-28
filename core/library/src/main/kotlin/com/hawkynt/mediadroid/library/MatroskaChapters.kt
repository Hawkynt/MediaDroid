package com.hawkynt.mediadroid.library

import java.io.File

/** One Matroska chapter: display title and time range in milliseconds; [endMs] is -1 when the file leaves it open. */
data class Chapter(val startMs: Long, val endMs: Long, val title: String)

/**
 * Reads chapter atoms straight from a Matroska file's EBML — media3 1.11 exposes
 * no chapter API, and the intro/credits skip needs the titles. The element ids
 * and the variable-size integer rules are copied from the Matroska and EBML
 * specifications. Only the first [HEAD_BYTES] are read: muxers write the
 * chapter atoms near the front, and library files run to gigabytes.
 *
 * Muxers such as mkvmerge often omit `ChapterTimeEnd`; the end of a chapter is
 * then the start of the next one, and the last chapter stays open (-1).
 */
object MatroskaChapters {

    private const val HEAD_BYTES = 8 * 1024 * 1024

    // Element ids copied from the Matroska specification.
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_CHAPTERS = 0x1043A770L
    private const val ID_EDITION_ENTRY = 0x45B9L
    private const val ID_CHAPTER_ATOM = 0xB6L
    private const val ID_TIME_START = 0x91L
    private const val ID_TIME_END = 0x92L
    private const val ID_FLAG_HIDDEN = 0x98L
    private const val ID_CHAPTER_DISPLAY = 0x80L
    private const val ID_CHAP_STRING = 0x85L

    fun parse(file: File): List<Chapter> = runCatching {
        val size = minOf(file.length(), HEAD_BYTES.toLong()).toInt()
        val bytes = ByteArray(size)
        file.inputStream().use { input ->
            var read = 0
            while (read < size) {
                val n = input.read(bytes, read, size - read)
                if (n < 0) break
                read += n
            }
        }
        parse(bytes)
    }.getOrDefault(emptyList())

    fun parse(bytes: ByteArray): List<Chapter> {
        val reader = Ebml(bytes)
        var segmentEnd = -1L
        while (reader.hasRoom) {
            val id = reader.readId() ?: break
            val size = reader.readSize()
            if (id == ID_SEGMENT) {
                segmentEnd = if (size < 0) bytes.size.toLong() else reader.position + size
                break
            }
            if (size < 0) break
            reader.position += size
        }
        if (segmentEnd < 0) return deriveEnds(emptyList())

        val raw = mutableListOf<Chapter>()
        while (reader.position < segmentEnd) {
            val id = reader.readId() ?: break
            val size = reader.readSize()
            val end = if (size < 0) segmentEnd else reader.position + size
            if (id == ID_CHAPTERS) raw += chaptersOf(reader, end)
            reader.position = end
        }
        return deriveEnds(raw.sortedBy { it.startMs })
    }

    private fun chaptersOf(reader: Ebml, limit: Long): List<Chapter> {
        val out = mutableListOf<Chapter>()
        while (reader.position < limit) {
            val id = reader.readId() ?: break
            val size = reader.readSize()
            val end = if (size < 0) limit else reader.position + size
            if (id == ID_EDITION_ENTRY) out += editionsOf(reader, end)
            reader.position = end
        }
        return out
    }

    private fun editionsOf(reader: Ebml, limit: Long): List<Chapter> {
        val out = mutableListOf<Chapter>()
        while (reader.position < limit) {
            val id = reader.readId() ?: break
            val size = reader.readSize()
            val end = if (size < 0) limit else reader.position + size
            if (id == ID_CHAPTER_ATOM) atomOf(reader, end)?.let { out += it }
            reader.position = end
        }
        return out
    }

    private fun atomOf(reader: Ebml, limit: Long): Chapter? {
        var startMs = -1L
        var endMs = -1L
        var hidden = false
        var title: String? = null
        while (reader.position < limit) {
            val id = reader.readId() ?: break
            val size = reader.readSize()
            val end = if (size < 0) limit else reader.position + size
            when (id) {
                ID_TIME_START -> startMs = reader.readUnsigned(end) / 1_000_000
                ID_TIME_END -> endMs = reader.readUnsigned(end) / 1_000_000
                ID_FLAG_HIDDEN -> hidden = reader.readUnsigned(end) != 0L
                ID_CHAPTER_DISPLAY -> {
                    if (title == null) title = displayOf(reader, end)
                }
            }
            reader.position = end
        }
        return if (startMs >= 0 && !hidden && !title.isNullOrEmpty()) Chapter(startMs, endMs, title) else null
    }

    private fun displayOf(reader: Ebml, limit: Long): String? {
        while (reader.position < limit) {
            val id = reader.readId() ?: break
            val size = reader.readSize()
            val end = if (size < 0) limit else reader.position + size
            if (id == ID_CHAP_STRING) return reader.readUtf8(end)
            reader.position = end
        }
        return null
    }

    private fun deriveEnds(chapters: List<Chapter>): List<Chapter> =
        chapters.mapIndexed { i, chapter ->
            if (chapter.endMs >= 0 || i == chapters.lastIndex) chapter
            else chapter.copy(endMs = chapters[i + 1].startMs)
        }

    /** Minimal EBML cursor: variable-size ids and sizes per the EBML specification. */
    internal class Ebml(private val bytes: ByteArray) {

        var position = 0L

        val hasRoom: Boolean get() = position < bytes.size.toLong()

        fun readId(): Long? {
            val first = bytes.getOrNull(position.toInt())?.toInt()?.and(0xFF) ?: return null
            val length = Integer.numberOfLeadingZeros(first) - 23
            if (length < 1 || length > 4 || position + length > bytes.size.toLong()) return null
            var id = 0L
            repeat(length) { id = (id shl 8) or (bytes[(position + it).toInt()].toLong() and 0xFF) }
            position += length
            return id
        }

        /** Data size; -1 for the "unknown size" vint. */
        fun readSize(): Long {
            val first = bytes.getOrNull(position.toInt())?.toInt()?.and(0xFF) ?: return 0
            val length = Integer.numberOfLeadingZeros(first) - 23
            if (length < 1 || length > 8 || position + length > bytes.size.toLong()) return 0
            var value = (first and (0xFF shr length)).toLong()
            repeat(length - 1) { value = (value shl 8) or (bytes[(position + 1 + it).toInt()].toLong() and 0xFF) }
            position += length
            // All data bits set means "unknown size".
            return if (value == (1L shl (7 * length)) - 1) -1 else value
        }

        fun readUnsigned(limit: Long): Long {
            var value = 0L
            while (position < limit && position < bytes.size.toLong()) {
                value = (value shl 8) or (bytes[position.toInt()].toLong() and 0xFF)
                position++
            }
            return value
        }

        fun readUtf8(limit: Long): String {
            val end = minOf(limit, bytes.size.toLong())
            // Muxers pad ChapString with NULs; strip them and surrounding blanks.
            val text = String(bytes, position.toInt(), (end - position).toInt(), Charsets.UTF_8).trim('\u0000', ' ')
            position = end
            return text
        }
    }
}
