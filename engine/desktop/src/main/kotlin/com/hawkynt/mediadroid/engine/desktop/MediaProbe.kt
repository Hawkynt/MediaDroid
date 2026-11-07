package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.library.FsFile
import com.hawkynt.mediadroid.player.ToneMap
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.PointerPointer

/**
 * What a media file holds, read by FFmpeg's demuxer alone — no decoder opened, no picture decoded: cheap enough to ask
 * of every row a list shows. For the length a browser shows beside a video and the tracks a title's details list.
 */
object MediaProbe {

    /** What kind of stream a [Stream] is. */
    enum class Kind { VIDEO, AUDIO, SUBTITLE }

    /**
     * One stream as the file holds it: its [codec] by FFmpeg's name (`h264`, `ac3`, `subrip` — Kodi's `<streamdetails>`
     * names are the same), its [profile] as FFmpeg calls it (`High`, `Main 10`, `DTS-HD MA`); a picture's size, frame
     * rate and [hdr] transfer; a sound's channels; its [language] (ISO 639, `und` left out) and [title].
     */
    data class Stream(
        val kind: Kind,
        val codec: String,
        val profile: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val frameRate: Float = 0f,
        val hdr: ToneMap.Transfer? = null,
        val channels: Int = 0,
        val language: String? = null,
        val title: String? = null,
    )

    /** A file's length in milliseconds (0 where it does not say) and its [streams] in the file's order. */
    data class Facts(val durationMs: Long, val streams: List<Stream>)

    /** The facts of the file at [path] — or [from] a network source, [path] its location there; null where unread. */
    fun read(path: String, from: FsFile? = null): Facts? = runCatching {
        FfmpegNatives.load()
        val input = from?.let(::FsInput)
        val format = if (input == null) AVFormatContext(null) else avformat.avformat_alloc_context().pb(input.context)
        try {
            if (avformat.avformat_open_input(format, path, null, null as AVDictionary?) < 0) return@runCatching null
            try {
                if (avformat.avformat_find_stream_info(format, null as PointerPointer<*>?) < 0) return@runCatching null
                Facts(
                    durationMs = if (format.duration() > 0) format.duration() / 1000 else 0,
                    streams = (0 until format.nb_streams()).mapNotNull { streamOf(format, it) },
                )
            } finally {
                avformat.avformat_close_input(format)
            }
        } finally {
            input?.close()
        }
    }.getOrNull()

    private fun streamOf(format: AVFormatContext, index: Int): Stream? {
        val stream = format.streams(index)
        val parameters = stream.codecpar()
        val kind = when (parameters.codec_type()) {
            avutil.AVMEDIA_TYPE_VIDEO -> Kind.VIDEO
            avutil.AVMEDIA_TYPE_AUDIO -> Kind.AUDIO
            avutil.AVMEDIA_TYPE_SUBTITLE -> Kind.SUBTITLE
            else -> return null
        }
        // A cover picture a file carries is a video stream to FFmpeg, not a track to play.
        if (kind == Kind.VIDEO && stream.disposition() and avformat.AV_DISPOSITION_ATTACHED_PIC != 0) return null
        fun tag(name: String) = avutil.av_dict_get(stream.metadata(), name, null, 0)?.value()?.string
            ?.trim()?.takeIf { it.isNotEmpty() }
        val rate = stream.avg_frame_rate()
        val frameRate = if (rate.num() > 0 && rate.den() > 0) rate.num().toFloat() / rate.den() else 0f
        return Stream(
            kind = kind,
            codec = avcodec.avcodec_get_name(parameters.codec_id()).string,
            profile = avcodec.avcodec_profile_name(parameters.codec_id(), parameters.profile())?.string,
            width = if (kind == Kind.VIDEO) parameters.width() else 0,
            height = if (kind == Kind.VIDEO) parameters.height() else 0,
            frameRate = if (kind == Kind.VIDEO) frameRate else 0f,
            hdr = if (kind != Kind.VIDEO) null else when (parameters.color_trc()) {
                avutil.AVCOL_TRC_SMPTE2084 -> ToneMap.Transfer.PQ
                avutil.AVCOL_TRC_ARIB_STD_B67 -> ToneMap.Transfer.HLG
                else -> null
            },
            channels = if (kind == Kind.AUDIO) parameters.ch_layout().nb_channels() else 0,
            language = tag("language")?.takeIf { it != "und" },
            title = tag("title"),
        )
    }
}
