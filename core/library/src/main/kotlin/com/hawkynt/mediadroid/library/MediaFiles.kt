package com.hawkynt.mediadroid.library

/** Which files are videos, shared by the library scanner and the Files browser. */
object MediaFiles {

    /**
     * Every container the player reads (the extractors sniff the content, so the extension only has to
     * say "video"): Media3's own families — MP4/QuickTime, Matroska/WebM, AVI, MPEG-PS, MPEG-TS, Ogg
     * and FLV, as in its `FileTypes` — and those the bundled FFmpeg demuxes when Media3 cannot: ASF
     * (WMV, WTV, DVR-MS), RealMedia, DV, NUT, MXF, NuppelVideo, raw MPEG video, H.264 and HEVC
     * streams, Y4M and IVF (see `tools/ffmpeg/build.sh`). `.ps` is MPEG-PS to Media3 but PostScript
     * to everybody else, so it stays out.
     */
    val videoExtensions = setOf(
        "mp4", "m4v", "mov", "qt", "3gp", "3g2", "f4v",
        "mkv", "mk3d", "webm",
        "avi", "divx", "xvid",
        "mpg", "mpeg", "mpe", "mp2", "m2p", "vob",
        // MPEG-PS under other names: JVC/Panasonic camcorders (MOD, TOD) and DVD-VR recorders (VRO).
        // A tracker-music .mod shares the name; it fails to play like any file that is no video.
        "mod", "tod", "vro",
        "ts", "m2ts", "mts", "m2t", "tp", "trp",
        "ogv", "ogm", "ogx",
        "flv",
        // Demuxed by the bundled FFmpeg.
        "wmv", "asf", "wtv", "dvr-ms",
        "rm", "rmvb",
        "dv", "dif",
        "nut", "mxf", "nuv",
        "m2v", "mpv", "264", "h264", "265", "h265", "hevc",
        "y4m", "ivf",
    )

    /** Whether the file [name] is a video by its extension. */
    fun isVideo(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in videoExtensions
}
