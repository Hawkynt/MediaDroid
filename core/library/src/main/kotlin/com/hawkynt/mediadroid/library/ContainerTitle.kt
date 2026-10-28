package com.hawkynt.mediadroid.library

import java.io.File
import java.io.RandomAccessFile

/**
 * The title a video file carries inside its container: Matroska's `Segment/Info/Title` (element ids from
 * the Matroska specification) or MP4's iTunes-style `moov/udta/meta/ilst/©nam` name atom (the
 * QuickTime / ISO 14496-12 metadata layout FFmpeg and taggers write). Null when there is none.
 */
object ContainerTitle {

    private const val HEAD_BYTES = 1024 * 1024
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TITLE = 0x7BA9L

    fun read(file: File): String? = runCatching {
        val head = file.inputStream().use { it.readNBytes(12) }
        val title = when {
            head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() -> matroska(file)
            Mp4Chapters.accepts(head) -> mp4(file)
            else -> null
        }
        title?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun matroska(file: File): String? {
        val bytes = file.inputStream().use { it.readNBytes(HEAD_BYTES) }
        val reader = MatroskaChapters.Ebml(bytes)
        while (reader.hasRoom) {
            val id = reader.readId() ?: return null
            val size = reader.readSize()
            if (id == ID_SEGMENT) break
            if (size < 0) return null
            reader.position += size
        }
        while (reader.hasRoom) {
            val id = reader.readId() ?: return null
            val size = reader.readSize()
            val end = if (size < 0) bytes.size.toLong() else reader.position + size
            if (id == ID_INFO) {
                while (reader.position < end) {
                    val child = reader.readId() ?: return null
                    val childSize = reader.readSize()
                    val childEnd = if (childSize < 0) end else reader.position + childSize
                    if (child == ID_TITLE) return reader.readUtf8(childEnd)
                    reader.position = childEnd
                }
                return null
            }
            if (size < 0) return null
            reader.position = end
        }
        return null
    }

    private fun mp4(file: File): String? = RandomAccessFile(file, "r").use { raf ->
        val moov = Mp4Chapters.readMoov(raf) ?: return null
        val udta = moov.children(0, moov.size).firstOrNull { it.type == "udta" } ?: return null
        val meta = moov.children(udta.start, udta.end).firstOrNull { it.type == "meta" } ?: return null
        // `meta` is a full box: version and flags come before its children.
        val ilst = moov.children(meta.start + 4, meta.end).firstOrNull { it.type == "ilst" } ?: return null
        val name = moov.children(ilst.start, ilst.end).firstOrNull { it.type == "©nam" } ?: return null
        val data = moov.children(name.start, name.end).firstOrNull { it.type == "data" } ?: return null
        // `data`: 4 bytes type, 4 bytes locale, then the UTF-8 text.
        val textStart = data.start + 8
        if (textStart > data.end) return null
        moov.string(textStart, data.end - textStart, Charsets.UTF_8)
    }
}
