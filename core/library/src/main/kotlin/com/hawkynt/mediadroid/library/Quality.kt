package com.hawkynt.mediadroid.library

import com.hawkynt.mediadroid.nfo.StreamDetails
import kotlinx.serialization.Serializable

/**
 * What a file is, in the words a player shows on its badges: resolution class (`480p` … `8K`), HDR
 * flavour, video format, the best audio format and its channel layout. Each is null when unknown.
 *
 * It comes from the file itself — the app probes every video once and caches what its tracks are —
 * or, when that fails, from the NFO's stream details. File names are never trusted for it. Codec
 * names are Kodi's `<streamdetails>` values; [codecOf] turns a probed Media3 MIME type into one.
 */
@Serializable
data class Quality(
    val resolution: String? = null,
    val hdr: String? = null,
    val video: String? = null,
    val audio: String? = null,
    val channels: String? = null,
) {
    /** How good the picture is, for choosing between copies of one film: resolution first, then HDR. */
    val pictureRank: Int
        get() = RESOLUTIONS.indexOf(resolution) * 2 + if (hdr != null) 1 else 0

    /** Every known badge, in the order a player shows them. */
    val badges: List<String> get() = listOfNotNull(resolution, hdr, video, audio, channels)

    companion object {
        val NONE = Quality()

        /** The resolution classes from worst to best; unknown ranks below them all. */
        private val RESOLUTIONS = listOf("480p", "576p", "720p", "1080p", "2K", "4K", "8K")

        fun of(details: StreamDetails?): Quality {
            if (details == null) return NONE
            val best = details.audio.maxByOrNull { audioRank(audioLabel(it.codec)) * 100 + (it.channels ?: 0) }
            return Quality(
                resolution = resolution(details.videoWidth, details.videoHeight),
                hdr = hdrLabel(details.hdrType),
                video = videoLabel(details.videoCodec),
                audio = audioLabel(best?.codec),
                channels = channelLabel(best?.channels),
            )
        }

        /** The Kodi codec name for a Media3 MIME type (`MimeTypes` constants); null when there is none. */
        fun codecOf(mime: String?): String? = when (mime) {
            "video/avc" -> "h264"
            "video/hevc", "video/mv-hevc" -> "hevc"
            "video/dolby-vision" -> "hevc"
            "video/av01" -> "av1"
            "video/x-vnd.on2.vp9" -> "vp9"
            "video/x-vnd.on2.vp8" -> "vp8"
            "video/mp4v-es", "video/divx" -> "mpeg4"
            "video/mpeg2" -> "mpeg2video"
            "video/wvc1" -> "vc1"
            "video/3gpp" -> "h263"
            "video/mjpeg" -> "mjpeg"
            "audio/ac3" -> "ac3"
            "audio/eac3" -> "eac3"
            "audio/eac3-joc" -> "eac3_atmos"
            "audio/ac4" -> "ac4"
            "audio/true-hd" -> "truehd"
            "audio/vnd.dts" -> "dts"
            "audio/vnd.dts.hd" -> "dtshd"
            "audio/vnd.dts.hd;profile=lbr" -> "dtshd"
            "audio/vnd.dts.uhd;profile=p2" -> "dtshd_ma_x"
            "audio/mp4a-latm" -> "aac"
            "audio/mpeg", "audio/mpeg-L1", "audio/mpeg-L2" -> "mp3"
            "audio/flac" -> "flac"
            "audio/opus" -> "opus"
            "audio/vorbis" -> "vorbis"
            "audio/alac" -> "alac"
            "audio/raw" -> "pcm"
            else -> null
        }

        /**
         * The resolution class by width first — a scope film at 1920×800 is still 1080p — and by height
         * when the width is unknown.
         */
        fun resolution(width: Int?, height: Int?): String? = when {
            width != null && width > 0 -> when {
                width >= 7000 -> "8K"
                width >= 3200 -> "4K"
                width >= 2000 -> "2K"
                width >= 1700 -> "1080p"
                width >= 1100 -> "720p"
                (height ?: 0) > 480 -> "576p"
                else -> "480p"
            }
            height != null && height > 0 -> when {
                height >= 4000 -> "8K"
                height >= 2000 -> "4K"
                height >= 1300 -> "2K"
                height >= 1000 -> "1080p"
                height >= 700 -> "720p"
                height > 480 -> "576p"
                else -> "480p"
            }
            else -> null
        }

        private fun videoLabel(codec: String?): String? = when (codec) {
            null, "" -> null
            "h264", "avc", "avc1", "x264" -> "H.264"
            "hevc", "h265", "x265", "hvc1", "hev1" -> "HEVC"
            "av1" -> "AV1"
            "vp9" -> "VP9"
            "vp8" -> "VP8"
            "mpeg4", "xvid", "divx", "dx50", "mp4v" -> "MPEG-4"
            "mpeg2video", "mpeg2" -> "MPEG-2"
            "vc1", "wvc1" -> "VC-1"
            else -> codec.uppercase()
        }

        private fun audioLabel(codec: String?): String? {
            val c = codec ?: return null
            return when {
                c.isEmpty() -> null
                "atmos" in c -> "Dolby Atmos"
                c.startsWith("truehd") -> "Dolby TrueHD"
                c == "eac3" || c == "ec-3" || c == "ddp" -> "Dolby Digital+"
                c == "ac3" || c == "ac-3" -> "Dolby Digital"
                c.endsWith("_x") || c.contains("_x_") || c == "dtsx" -> "DTS:X"
                c == "dtshd_ma" -> "DTS-HD MA"
                c.startsWith("dtshd") -> "DTS-HD"
                c == "dts" || c == "dca" -> "DTS"
                c == "aac" -> "AAC"
                c == "mp3" -> "MP3"
                c == "flac" -> "FLAC"
                c == "opus" -> "Opus"
                c == "vorbis" -> "Vorbis"
                c.startsWith("pcm") || c == "lpcm" -> "PCM"
                else -> c.uppercase()
            }
        }

        /** Which of several audio streams a badge names: the richest format wins. */
        private fun audioRank(label: String?): Int = when (label) {
            "Dolby Atmos", "DTS:X" -> 9
            "Dolby TrueHD", "DTS-HD MA" -> 8
            "DTS-HD" -> 7
            "PCM", "FLAC" -> 6
            "Dolby Digital+" -> 5
            "DTS" -> 4
            "Dolby Digital" -> 3
            null -> 0
            else -> 1
        }

        private fun hdrLabel(type: String?): String? = when (type) {
            null, "" -> null
            "dolbyvision", "dolby vision", "dv" -> "Dolby Vision"
            "hdr10plus", "hdr10+" -> "HDR10+"
            "hdr10" -> "HDR10"
            "hlg" -> "HLG"
            else -> type.uppercase()
        }

        private fun channelLabel(channels: Int?): String? = when (channels) {
            null, 0 -> null
            1 -> "Mono"
            2 -> "2.0"
            3 -> "2.1"
            6 -> "5.1"
            7 -> "6.1"
            8 -> "7.1"
            else -> "$channels ch"
        }
    }
}
