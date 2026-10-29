package com.hawkynt.mediadroid.player

import java.util.Locale

/**
 * How a video is being played, as a graph for the player's info sheet: every file read is an input —
 * where it is read from, which demuxer splits it — and from the inputs a lane each for picture, sound
 * and subtitles runs through its stages (decoder, conversion, filters, resampling, mixing, renderer …)
 * to its output, the screen or the speaker. A sidecar subtitle or audio file is one more input.
 */
object PlaybackChain {

    /** What a lane carries. */
    enum class Lane { PICTURE, SOUND, SUBTITLES }

    /** One stage of a lane, or its end: what it is and what it does here. */
    data class Node(val title: String, val detail: String)

    /** A file read: its name, how it is reached and the demuxer that splits it. */
    data class Input(val name: String, val readFrom: String, val demuxer: String)

    /** One lane from the input at [input] (an index into [Graph.inputs]) through [nodes] to [output]. */
    data class Path(val lane: Lane, val input: Int, val nodes: List<Node>, val output: Node)

    data class Graph(val inputs: List<Input>, val paths: List<Path>, val engine: String)

    /** A file the player reads: its path or location URI, and the demuxer that took it (see [Facts]). */
    data class Source(val location: String, val demuxer: String?)

    /** What the player and the platform report about the playing video. */
    data class Facts(
        /** Every file read; the tracks name theirs by index. */
        val inputs: List<Source>,
        val video: Video?,
        val audio: Audio?,
        val subtitles: Subtitles?,
        /** The playback engine and its version. */
        val player: String,
    )

    /** The picture's way from the decoder to the screen. */
    data class Video(
        val input: Int,
        val decoder: String?,
        val width: Int,
        val height: Int,
        /** Width over height of one pixel; 1 for square pixels. */
        val pixelRatio: Float,
        val picture: PictureAdjustment,
        /** How the picture fills the screen: `Fit`, `Fill`, `Stretch`. */
        val fit: String,
        val zoom: Float,
        /** Which screen shows it: `this screen` or the second screen's name. */
        val screen: String,
        val screenWidth: Int,
        val screenHeight: Int,
        val refreshHz: Float,
        /** The pixel format FFmpeg decodes to (`yuv420p`, `yuv411p`, `yuv422p10le`); null for other decoders. */
        val decodedFormat: String? = null,
        /** The stream's chroma subsampling, as `4:2:0`, when known. */
        val chroma: String? = null,
        /** Bits per sample of the picture, or 0 when unknown. */
        val bitDepth: Int = 0,
        /** Colour space, as `BT.709`; transfer, as `SDR` or `PQ (HDR10)`; range, as `limited range`. */
        val colorSpace: String? = null,
        val transfer: String? = null,
        val range: String? = null,
        /** The film's frames per second, or 0 when unknown. */
        val frameRate: Float = 0f,
        /** How much faster (above 1) or slower the film plays so its frames fall evenly on the screen. */
        val speedFactor: Float = 1f,
    )

    /** The sound's way from the decoder to the output. */
    data class Audio(
        val input: Int,
        val decoder: String?,
        val inputSampleRate: Int,
        val inputChannels: Int,
        /** What the sink is fed: `16-bit PCM`, `float PCM`, or the passed-through format's name. */
        val sinkEncoding: String?,
        val sinkSampleRate: Int,
        val sinkChannels: Int,
        val passthrough: Boolean,
        val offload: Boolean,
        val loudnessDb: Float,
        val nightMode: Boolean,
        val speed: Float,
        val delayMs: Int,
        val spatial: Boolean,
        /** The output the sound goes to: `This device`, a headset's name, `HDMI`. */
        val device: String,
        /** The sample format FFmpeg decodes to (`fltp`, `s16`); null for other decoders. */
        val decodedFormat: String? = null,
        /** The PCM the decoder hands on and the processing works in: `16-bit PCM`, `float PCM`. */
        val pcmFormat: String? = null,
    )

    /** The subtitles shown: their format (a MIME type), language, shift, and the screen they are drawn on. */
    data class Subtitles(
        val input: Int,
        val mimeType: String?,
        val language: String?,
        /** Positive shows them later, negative earlier. */
        val delayMs: Int,
        val screen: String,
    )

    /** The fact sheet's graph. Media3 parts are named as Media3 calls them; the codecs as people do. */
    fun of(facts: Facts): Graph = Graph(
        inputs = facts.inputs.map { Input(nameOf(it.location), sourceOf(it.location), demuxerOf(it.demuxer)) },
        paths = listOfNotNull(
            facts.video?.let(::picture),
            facts.audio?.let(::sound),
            facts.subtitles?.let(::subtitles),
        ),
        engine = facts.player,
    )

    /** A subtitle format's name for its MIME type: `SubRip`, `PGS (pictures)`; unknown ones as they are. */
    fun subtitleFormat(mimeType: String): String = SUBTITLE_FORMATS[mimeType] ?: mimeType

    /** A decoder's name with what kind it is: the device's own (hardware or software) or the bundled FFmpeg. */
    fun decoderKind(name: String?): String = when {
        name == null -> "not in use"
        isFfmpeg(name) -> "$name (software, FFmpeg)"
        name.startsWith("c2.android.") || name.startsWith("OMX.google.") -> "$name (software)"
        name.startsWith("OMX.") && name.contains("sw", ignoreCase = true) -> "$name (software)"
        else -> "$name (hardware)"
    }

    private fun isFfmpeg(name: String) = name.startsWith("ffmpeg", ignoreCase = true) || name.contains("Ffmpeg")

    private fun sourceOf(location: String): String = when (location.substringBefore("://", "").lowercase()) {
        "smb" -> "SMB share (smbj)"
        "nfs" -> "NFS export (NFSv3)"
        "ftp" -> "FTP (Commons Net)"
        "ftps" -> "FTPS (Commons Net)"
        "sftp" -> "SFTP (sshj)"
        "http", "https", "dav", "davs" -> "WebDAV (OkHttp)"
        "content" -> "Document folder (Android storage access)"
        else -> "Local file"
    }

    private fun demuxerOf(name: String?): String = when {
        name == null -> "not known yet"
        name.startsWith("ffmpeg:") -> "FFmpeg (${name.removePrefix("ffmpeg:")})"
        else -> "Media3 " + (MEDIA3_DEMUXERS[name] ?: name.removeSuffix("Extractor"))
    }

    private fun nameOf(location: String): String =
        location.trimEnd('/').substringAfterLast('/').ifEmpty { location }

    private fun picture(video: Video): Path {
        val ffmpeg = video.decoder?.let(::isFfmpeg) == true
        val shownWidth = (video.width * video.pixelRatio).toInt()
        val size = when {
            video.width <= 0 -> "not known yet"
            video.pixelRatio == 1f || shownWidth == video.width -> "${video.width} × ${video.height}, square"
            else -> "${video.width} × ${video.height}, shown $shownWidth × ${video.height}"
        }
        val colour = listOfNotNull(video.colorSpace, video.transfer, video.range).joinToString(", ")
        val pixels = if (colour.isEmpty()) size else "$size · $colour"
        // What the decoder hands on: FFmpeg's own pixel format, else what the stream declares.
        val streamFormat = video.chroma?.let { chroma ->
            "YUV $chroma" + if (video.bitDepth > 0) ", ${video.bitDepth}-bit" else ""
        }
        val decoded = video.decodedFormat?.let { "$it (${pixelFormat(it)})" } ?: streamFormat
        val decoder = decoderKind(video.decoder) + (decoded?.let { " → $it" } ?: "")
        val conversion = when {
            ffmpeg && video.decodedFormat == "yuv420p" -> "yuv420p copied as $YV12, $TO_RGB"
            ffmpeg && video.decodedFormat != null -> "${video.decodedFormat} → $YV12 by libyuv, $TO_RGB"
            ffmpeg -> "to $YV12 by libyuv, $TO_RGB"
            streamFormat != null -> "none on the processor: $streamFormat stays on the surface, $TO_RGB"
            else -> "none on the processor: the decoder draws onto the surface, $TO_RGB"
        }
        val adjustments = pictureOf(video.picture).ifEmpty { listOf("none") }.joinToString(", ")
        val framing = if (video.zoom != 1f) "${video.fit}, zoom ${number(video.zoom)}×" else video.fit
        val screen = buildList {
            add(video.screen)
            if (video.screenWidth > 0) add("${video.screenWidth} × ${video.screenHeight}")
            if (video.refreshHz > 0) add("${number(video.refreshHz)} Hz")
        }.joinToString(" · ")
        return Path(
            Lane.PICTURE,
            video.input,
            listOf(
                Node("Decoder", decoder),
                Node("Conversion", conversion),
                Node("Pixels", pixels),
                Node("Filters", "$adjustments · $framing"),
            ) + timing(video) + listOf(
                Node("Renderer", if (ffmpeg) "FFmpeg video renderer" else "Media3 MediaCodec video renderer"),
            ),
            Node("Screen", screen),
        )
    }

    /** The Timing node: the frame rate, and the one it is played at where that differs. */
    private fun timing(video: Video): List<Node> {
        if (video.frameRate <= 0f) return emptyList()
        val on = if (video.refreshHz > 0) " on ${number(video.refreshHz)} Hz" else ""
        if (video.speedFactor == 1f) return listOf(Node("Timing", "${number(video.frameRate)} fps$on"))
        val change = (video.speedFactor - 1f) * 100f
        val percent = String.format(Locale.ROOT, "%.1f", kotlin.math.abs(change)) + " %"
        val played = number(kotlin.math.round(video.frameRate * video.speedFactor * 1000f) / 1000f)
        val sign = if (change > 0) "+" else "−"
        return listOf(Node("Timing", "${number(video.frameRate)} fps played at $played fps ($sign$percent)$on"))
    }

    private fun pictureOf(picture: PictureAdjustment): List<String> = buildList {
        if (picture.brightness != 0f) add("brightness ${signed(picture.brightness * 100)} %")
        if (picture.contrast != 1f) add("contrast ${number(picture.contrast * 100)} %")
        if (picture.saturation != 1f) add("saturation ${number(picture.saturation * 100)} %")
        if (picture.gamma != 1f) add("gamma ${number(picture.gamma)}")
    }

    private fun sound(audio: Audio): Path {
        val processing = buildList {
            if (audio.loudnessDb != 0f) add("loudness ${signed(audio.loudnessDb)} dB")
            if (audio.nightMode) add("night mode")
            if (audio.speed != 1f) add("speed ${number(audio.speed)}× (Sonic)")
            if (audio.delayMs != 0) add("delay ${audio.delayMs} ms")
            if (audio.spatial) add("spatial audio")
        }.joinToString(", ").let { steps ->
            when {
                steps.isEmpty() -> "none" + (audio.pcmFormat?.let { " ($it)" } ?: "")
                audio.pcmFormat != null -> "in ${audio.pcmFormat}: $steps"
                else -> steps
            }
        }
        val decoder = decoderKind(audio.decoder) +
            (audio.decodedFormat?.let { " · $it (${sampleFormat(it)})" } ?: "") +
            (audio.pcmFormat?.let { " → $it" } ?: "")
        val resampling = when {
            audio.passthrough || audio.sinkSampleRate <= 0 || audio.inputSampleRate <= 0 ->
                "none" + if (audio.sinkSampleRate > 0) " (${khz(audio.sinkSampleRate)})" else ""
            audio.inputSampleRate == audio.sinkSampleRate -> "none (${khz(audio.sinkSampleRate)})"
            else -> "${khz(audio.inputSampleRate)} → ${khz(audio.sinkSampleRate)}"
        }
        val mixing = when {
            audio.passthrough -> "none — passed through to the receiver"
            audio.inputChannels <= 0 || audio.sinkChannels <= 0 -> "not known yet"
            audio.inputChannels > audio.sinkChannels ->
                "${audio.inputChannels} → ${audio.sinkChannels} channels (downmix)"
            audio.inputChannels < audio.sinkChannels ->
                "${audio.inputChannels} → ${audio.sinkChannels} channels (upmix)"
            else -> "none (${channels(audio.sinkChannels)})"
        }
        val sink = when {
            audio.passthrough -> "AudioTrack · passthrough of ${audio.sinkEncoding ?: "encoded sound"}"
            audio.sinkSampleRate <= 0 -> "AudioTrack · not started yet"
            else -> buildList {
                add("AudioTrack")
                add(khz(audio.sinkSampleRate))
                add(channels(audio.sinkChannels))
                audio.sinkEncoding?.let { add(it) }
                if (audio.offload) add("offloaded")
            }.joinToString(" · ")
        }
        return Path(
            Lane.SOUND,
            audio.input,
            listOf(
                Node("Decoder", decoder),
                Node("Processing", processing),
                Node("Resampling", resampling),
                Node("Mixing", mixing),
                Node("Sink", sink),
            ),
            Node("Output", audio.device),
        )
    }

    private fun subtitles(subtitles: Subtitles): Path {
        val format = subtitles.mimeType?.let(::subtitleFormat) ?: "not known yet"
        val timing = when {
            subtitles.delayMs > 0 -> "${subtitles.delayMs} ms later"
            subtitles.delayMs < 0 -> "${-subtitles.delayMs} ms earlier"
            else -> "in sync"
        }
        return Path(
            Lane.SUBTITLES,
            subtitles.input,
            listOf(
                Node("Parser", listOfNotNull(format, subtitles.language).joinToString(" · ")),
                Node("Timing", timing),
                Node("Renderer", "Media3 text renderer"),
            ),
            Node("Screen", "over the picture on ${subtitles.screen}"),
        )
    }

    /**
     * An FFmpeg pixel format in words: `yuv411p` is `YUV 4:1:1, 8-bit`, `yuv422p10le` `YUV 4:2:2, 10-bit`,
     * `yuvj420p` full-range 4:2:0; the semi-planar, grey and RGB ones by name. The names are FFmpeg's
     * (libavutil/pixfmt.h); unknown ones stay as they are.
     */
    fun pixelFormat(name: String): String {
        Regex("^yuv(j?)(4\\d\\d)p(\\d*)(le|be)?$").find(name)?.let { m ->
            val (full, chroma, depth) = m.destructured
            val sampling = chroma.toCharArray().joinToString(":")
            return "YUV $sampling, ${depth.ifEmpty { "8" }}-bit" + if (full.isNotEmpty()) ", full range" else ""
        }
        return when (name) {
            "nv12", "nv21" -> "YUV 4:2:0 semi-planar, 8-bit"
            "p010le", "p010be" -> "YUV 4:2:0 semi-planar, 10-bit"
            "gray" -> "grey, 8-bit"
            "rgb24", "bgr24" -> "RGB, 8-bit"
            "pal8" -> "8-bit palette"
            else -> name
        }
    }

    /** An FFmpeg sample format in words: `fltp` is `float, planar`, `s16` `16-bit` (libavutil/samplefmt.h). */
    fun sampleFormat(name: String): String {
        val planar = name.endsWith("p")
        val base = when (name.removeSuffix("p")) {
            "u8" -> "8-bit"
            "s16" -> "16-bit"
            "s32" -> "32-bit"
            "s64" -> "64-bit"
            "flt" -> "float"
            "dbl" -> "double"
            else -> return name
        }
        return if (planar) "$base, planar" else base
    }

    private fun channels(count: Int): String = if (count == 1) "1 channel" else "$count channels"

    private fun khz(rate: Int): String = number(rate / 1000f) + " kHz"

    private fun number(value: Float): String =
        String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')

    private fun signed(value: Float): String = (if (value > 0) "+" else "") + number(value)

    private const val YV12 = "YV12 (YUV 4:2:0, 8-bit)"
    private const val TO_RGB = "the display turns it into RGB"
    /** Subtitle formats by MIME type, as they are called; the picture-based ones say so. */
    private val SUBTITLE_FORMATS = mapOf(
        "application/x-subrip" to "SubRip",
        "text/x-ssa" to "SSA / ASS",
        "text/vtt" to "WebVTT",
        "application/ttml+xml" to "TTML",
        "application/x-quicktime-tx3g" to "3GPP timed text",
        "application/pgs" to "PGS (pictures)",
        "application/vobsub" to "VobSub (pictures)",
        "application/dvbsubs" to "DVB (pictures)",
        "application/cea-608" to "CEA-608 captions",
        "application/cea-708" to "CEA-708 captions",
    )

    /** Media3's extractors by class name, as their container is called. */
    private val MEDIA3_DEMUXERS = mapOf(
        "MatroskaExtractor" to "Matroska / WebM",
        "Mp4Extractor" to "MP4",
        "FragmentedMp4Extractor" to "MP4",
        "TsExtractor" to "MPEG-TS",
        "PsExtractor" to "MPEG-PS",
        "AviExtractor" to "AVI",
        "FlvExtractor" to "FLV",
        "OggExtractor" to "Ogg",
        "Mp3Extractor" to "MP3",
        "AdtsExtractor" to "AAC (ADTS)",
        "Ac3Extractor" to "AC3",
        "Ac4Extractor" to "AC4",
        "FlacExtractor" to "FLAC",
        "WavExtractor" to "WAV",
        "AmrExtractor" to "AMR",
        "SubtitleExtractor" to "subtitle parser",
    )
}
