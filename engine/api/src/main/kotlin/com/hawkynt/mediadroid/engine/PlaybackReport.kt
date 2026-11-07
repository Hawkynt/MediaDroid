package com.hawkynt.mediadroid.engine

/**
 * A track's format as the file holds it, for the info sheet: its [mimeType] — a subtitle track's own format (SubRip,
 * SSA …), never the engine's inner one — and RFC 6381 [codecs] string, or for a stream FFmpeg demuxed
 * `video/x-ffmpeg-<name>` / `audio/x-ffmpeg-<name>` with FFmpeg's codec name in [codecs]; the picture's [width],
 * [height] and [frameRate], the sound's [channels] and [sampleRate] (0 where not known or not its kind).
 */
data class TrackFormat(
    val mimeType: String? = null,
    val codecs: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val channels: Int = 0,
    val sampleRate: Int = 0,
)

/**
 * How the engine plays what is open, as it knows it — the facts behind the info sheet and its graph of the playback
 * chain: the file's own [tags], the files read ([inputs]: the video, then its subtitle files, then its audio files,
 * in the order they are merged in), the playing picture, sound and subtitles as decoded, and the [player] itself.
 * What the screen and the household add — the picture's adjustment, the screen, the loudness — the screens add.
 */
data class PlaybackReport(
    val tags: FileTags = FileTags(),
    val inputs: List<Input> = emptyList(),
    val video: Video? = null,
    val audio: Audio? = null,
    val subtitles: Subtitles? = null,
    val player: String = "",
) {
    /** What the file's own tags say about it; null where they say nothing. */
    data class FileTags(
        val title: String? = null,
        val displayTitle: String? = null,
        val subtitle: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val genre: String? = null,
        val year: Int? = null,
        val writer: String? = null,
        val description: String? = null,
    )

    /**
     * A file read: its [path] and the [demuxer] that reads it, where known — Media3's extractor by its class name
     * (`MatroskaExtractor`), FFmpeg's as `ffmpeg:<format>` (`ffmpeg:matroska,webm`).
     */
    data class Input(val path: String, val demuxer: String? = null)

    /**
     * The picture playing: from [inputs] number [input], its [codecs] string, its size as decoded ([width], [height],
     * the pixels' [pixelAspect]), [frameRate] (0 unknown), [bitDepth], colour space, transfer and range as named
     * (BT.709, PQ (HDR10), limited range …, null unknown), and the decoder's own output format where it says
     * ([decodedFormat], FFmpeg's pixel format).
     */
    data class Video(
        val input: Int = 0,
        val codecs: String? = null,
        val width: Int,
        val height: Int,
        val pixelAspect: Float = 1f,
        val frameRate: Float = 0f,
        val bitDepth: Int = 8,
        val colorSpace: String? = null,
        val transfer: String? = null,
        val range: String? = null,
        val decodedFormat: String? = null,
    )

    /**
     * The sound playing: from [inputs] number [input], as the file holds it ([sampleRate], [channels]) and as it
     * reaches the output ([sinkEncoding] named, [sinkSampleRate], [sinkChannels], [passthrough] undecoded to a
     * receiver, [offload]ed to the hardware), the decoder's own output format ([decodedFormat]) and the PCM it hands
     * on ([pcmFormat]), where known.
     */
    data class Audio(
        val input: Int = 0,
        val sampleRate: Int = 0,
        val channels: Int = 0,
        val sinkEncoding: String? = null,
        val sinkSampleRate: Int = 0,
        val sinkChannels: Int = 0,
        val passthrough: Boolean = false,
        val offload: Boolean = false,
        val decodedFormat: String? = null,
        val pcmFormat: String? = null,
    )

    /**
     * The subtitles showing: from [inputs] number [input], their own format ([mimeType]), [language] (ISO 639, null
     * unknown) and whether libass draws them.
     */
    data class Subtitles(
        val input: Int = 0,
        val mimeType: String? = null,
        val language: String? = null,
        val libass: Boolean = false,
    )
}
