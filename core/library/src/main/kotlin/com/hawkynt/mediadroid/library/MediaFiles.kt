package com.hawkynt.mediadroid.library

/** Which files are videos, shared by the library scanner and the Files browser. */
object MediaFiles {

    /**
     * Every container family Media3's extractors read (they sniff the content, so the extension
     * only has to say "video"): MP4/QuickTime, Matroska/WebM, AVI, MPEG-PS, MPEG-TS, Ogg and FLV.
     */
    val videoExtensions = setOf(
        "mp4", "m4v", "mov", "qt", "3gp", "3g2", "f4v",
        "mkv", "mk3d", "webm",
        "avi", "divx", "xvid",
        "mpg", "mpeg", "mpe", "mp2", "m2p", "vob",
        "ts", "m2ts", "mts", "m2t", "tp", "trp",
        "ogv", "ogm",
        "flv",
    )

    /** Whether the file [name] is a video by its extension. */
    fun isVideo(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in videoExtensions
}
