package com.hawkynt.mediadroid.library

import java.io.File

/**
 * The chapters of any video, read by its content: Matroska / WebM ([MatroskaChapters]), MP4 / QuickTime
 * ([Mp4Chapters]) and Ogg ([OggChapters]). MPEG program and transport streams and AVI carry no chapter
 * titles, so they have none; their intros and credits come from an edit decision list instead.
 */
object Chapters {

    private val EBML_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

    fun read(file: File): List<Chapter> = read(LocalFile(file))

    /** The chapters of a video on any source. */
    fun read(file: FsFile): List<Chapter> {
        val head = runCatching { file.open().use { it.readNBytes(12) } }.getOrNull() ?: return emptyList()
        return when {
            head.size >= 4 && head.copyOf(4).contentEquals(EBML_MAGIC) -> MatroskaChapters.parse(file)
            OggChapters.accepts(head) -> OggChapters.parse(file)
            Mp4Chapters.accepts(head) -> Mp4Chapters.parse(file)
            else -> emptyList()
        }
    }
}
