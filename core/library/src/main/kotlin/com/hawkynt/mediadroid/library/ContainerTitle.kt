package com.hawkynt.mediadroid.library

import java.io.File

/**
 * The title a video file carries inside its container: Matroska's `Segment/Info/Title` (element ids from
 * the Matroska specification) or MP4's iTunes-style `moov/udta/meta/ilst/©nam` name atom (the
 * QuickTime / ISO 14496-12 metadata layout FFmpeg and taggers write). Null when there is none. MP4 also
 * carries a description: the long `ldes` atom (FFmpeg's "synopsis"), else the short `desc`.
 */
object ContainerTitle {

    private const val HEAD_BYTES = 1024 * 1024
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TITLE = 0x7BA9L

    /** What a container says about the video in it; each part null when it does not say. */
    data class Tags(val title: String?, val description: String?)

    fun read(file: File): String? = read(LocalFile(file))

    /** The title a video on any source carries. */
    fun read(file: FsFile): String? = tags(file).title

    /** The title and the description a video on any source carries. */
    fun tags(file: FsFile): Tags = runCatching {
        val head = file.open().use { it.readNBytes(12) }
        val tags = when {
            head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() -> Tags(matroska(file), null)
            Mp4Chapters.accepts(head) -> mp4(file)
            else -> Tags(null, null)
        }
        Tags(tags.title.clean(), tags.description.clean())
    }.getOrDefault(Tags(null, null))

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun matroska(file: FsFile): String? {
        val bytes = file.open().use { it.readNBytes(HEAD_BYTES) }
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

    private fun mp4(file: FsFile): Tags = SeekableFile(file).use { raf ->
        val none = Tags(null, null)
        val moov = Mp4Chapters.readMoov(raf) ?: return none
        val udta = moov.children(0, moov.size).firstOrNull { it.type == "udta" } ?: return none
        val meta = moov.children(udta.start, udta.end).firstOrNull { it.type == "meta" } ?: return none
        // `meta` is a full box: version and flags come before its children.
        val ilst = moov.children(meta.start + 4, meta.end).firstOrNull { it.type == "ilst" } ?: return none
        val items = moov.children(ilst.start, ilst.end)
        fun text(type: String): String? {
            val item = items.firstOrNull { it.type == type } ?: return null
            val data = moov.children(item.start, item.end).firstOrNull { it.type == "data" } ?: return null
            // `data`: 4 bytes type, 4 bytes locale, then the UTF-8 text.
            val textStart = data.start + 8
            if (textStart > data.end) return null
            return moov.string(textStart, data.end - textStart, Charsets.UTF_8)
        }
        Tags(text("©nam"), text("ldes") ?: text("desc"))
    }
}
