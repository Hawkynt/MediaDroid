package com.hawkynt.mediadroid.library

import java.io.File

/**
 * Chapters of an Ogg file (Vorbis, Opus, Theora, FLAC): the Xiph "Chapter Extension" to Vorbis comments,
 * `CHAPTERxx=HH:MM:SS.sss` and `CHAPTERxxNAME=title` in a stream's comment header (FFmpeg writes three
 * digits, the extension's examples two). Page layout and the comment header follow RFC 3533 and the
 * Vorbis I specification. Only the first [HEAD_BYTES] are read: the comment header is the second packet.
 */
object OggChapters {

    private const val HEAD_BYTES = 512 * 1024
    private val chapterTime = Regex("^CHAPTER(\\d{2,3})=(.+)$", RegexOption.IGNORE_CASE)
    private val chapterName = Regex("^CHAPTER(\\d{2,3})NAME=(.*)$", RegexOption.IGNORE_CASE)

    /** Whether [head], the first bytes of a file, are an Ogg page. */
    fun accepts(head: ByteArray): Boolean = head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "OggS"

    fun parse(file: File): List<Chapter> = runCatching {
        val bytes = file.inputStream().use { it.readNBytes(HEAD_BYTES) }
        parse(bytes)
    }.getOrDefault(emptyList())

    fun parse(bytes: ByteArray): List<Chapter> {
        for (comments in commentHeaders(bytes)) {
            val chapters = chapters(comments)
            if (chapters.isNotEmpty()) return chapters
        }
        return emptyList()
    }

    /** The comments of every logical stream's comment header found in [bytes]. */
    private fun commentHeaders(bytes: ByteArray): List<List<String>> {
        val packets = HashMap<Int, MutableList<ByteArray>>() // serial → finished packets
        val pending = HashMap<Int, java.io.ByteArrayOutputStream>()
        val found = ArrayList<List<String>>()
        var pos = 0
        while (pos + 27 <= bytes.size && String(bytes, pos, 4, Charsets.ISO_8859_1) == "OggS") {
            val serial = le32(bytes, pos + 14)
            val segments = bytes[pos + 26].toInt() and 0xFF
            var data = pos + 27 + segments
            for (s in 0 until segments) {
                val lacing = bytes[pos + 27 + s].toInt() and 0xFF
                if (data + lacing > bytes.size) return found
                val packet = pending.getOrPut(serial) { java.io.ByteArrayOutputStream() }
                packet.write(bytes, data, lacing)
                data += lacing
                if (lacing < 255) {
                    val done = packets.getOrPut(serial) { ArrayList() }
                    done += packet.toByteArray()
                    pending.remove(serial)
                    if (done.size == 2) comments(done[1])?.let { found += it }
                }
            }
            pos = data
        }
        return found
    }

    /** The comment list of a comment-header packet, whatever codec the stream carries. */
    private fun comments(packet: ByteArray): List<String>? {
        fun startsWith(prefix: ByteArray) =
            packet.size >= prefix.size && prefix.indices.all { packet[it] == prefix[it] }
        val body = when {
            startsWith("OpusTags".toByteArray()) -> 8
            startsWith(byteArrayOf(3) + "vorbis".toByteArray()) -> 7
            startsWith(byteArrayOf(0x81.toByte()) + "theora".toByteArray()) -> 7
            packet.isNotEmpty() && (packet[0].toInt() and 0x7F) == 4 -> 4 // FLAC VORBIS_COMMENT metadata block
            else -> return null
        }
        var p = body
        val vendor = le32(packet, p)
        p += 4 + vendor
        val count = le32(packet, p)
        p += 4
        val out = ArrayList<String>()
        repeat(count) {
            if (p + 4 > packet.size) return out
            val length = le32(packet, p)
            p += 4
            if (length < 0 || p + length > packet.size) return out
            out += String(packet, p, length, Charsets.UTF_8)
            p += length
        }
        return out
    }

    private fun chapters(comments: List<String>): List<Chapter> {
        val times = HashMap<Int, Long>()
        val names = HashMap<Int, String>()
        for (comment in comments) {
            val name = chapterName.find(comment)
            if (name != null) {
                names[name.groupValues[1].toInt()] = name.groupValues[2]
                continue
            }
            val time = chapterTime.find(comment) ?: continue
            clock(time.groupValues[2])?.let { times[time.groupValues[1].toInt()] = it }
        }
        val sorted = times.entries.sortedBy { it.key }
        return sorted.mapIndexed { i, (number, start) ->
            Chapter(start, sorted.getOrNull(i + 1)?.value ?: -1, names[number] ?: "")
        }
    }

    /** `HH:MM:SS.sss` in milliseconds. */
    private fun clock(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toDoubleOrNull() ?: return null
        return (h * 3600 + m * 60) * 1000 + Math.round(s * 1000)
    }

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}
