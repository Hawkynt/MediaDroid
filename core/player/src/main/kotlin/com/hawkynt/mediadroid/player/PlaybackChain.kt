package com.hawkynt.mediadroid.player

import java.util.Locale

/**
 * How a video is being played, step by step, for the player's info sheet: where the file is read from,
 * which demuxer splits it, and for the picture and the sound every stage between the file and the
 * screen or the speaker — decoder, conversion, filters, resampling, mixing, sink, output.
 */
object PlaybackChain {

    /** One step of the chain: its [section] (`Source`, `Video`, `Audio`, `Player`), its name, what it does. */
    data class Stage(val section: String, val step: String, val detail: String)

    /** What the player and the platform report about the playing video. */
    data class Facts(
        /** The file's path or location URI. */
        val location: String,
        /** The extractor Media3 chose (its class name), or `ffmpeg:<format>` for FFmpeg's demuxer. */
        val demuxer: String?,
        val video: Video?,
        val audio: Audio?,
        /** The playback engine and its version. */
        val player: String,
    )

    /** The picture's way from the decoder to the screen. */
    data class Video(
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
    )

    /** The sound's way from the decoder to the output. */
    data class Audio(
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
    )

    fun of(facts: Facts): List<Stage> = buildList {
        add(Stage(SOURCE, "Read from", sourceOf(facts.location)))
        add(Stage(SOURCE, "Demuxer", demuxerOf(facts.demuxer)))
        facts.video?.let { addAll(videoStages(it)) }
        facts.audio?.let { addAll(audioStages(it)) }
        add(Stage(PLAYER, "Engine", facts.player))
    }

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

    private fun videoStages(video: Video): List<Stage> {
        val ffmpeg = video.decoder?.let(::isFfmpeg) == true
        val shownWidth = (video.width * video.pixelRatio).toInt()
        val pixels = when {
            video.width <= 0 -> "not known yet"
            video.pixelRatio == 1f || shownWidth == video.width -> "${video.width} × ${video.height}, square"
            else -> "${video.width} × ${video.height}, shown $shownWidth × ${video.height}"
        }
        val adjustments = pictureOf(video.picture).ifEmpty { listOf("none") }.joinToString(", ")
        val framing = if (video.zoom != 1f) "${video.fit}, zoom ${number(video.zoom)}×" else video.fit
        val output = buildList {
            add(video.screen)
            if (video.screenWidth > 0) add("${video.screenWidth} × ${video.screenHeight}")
            if (video.refreshHz > 0) add("${number(video.refreshHz)} Hz")
        }.joinToString(" · ")
        return listOf(
            Stage(VIDEO, "Decoder", decoderKind(video.decoder)),
            Stage(VIDEO, "Conversion", if (ffmpeg) FFMPEG_CONVERSION else DIRECT_CONVERSION),
            Stage(VIDEO, "Pixels", pixels),
            Stage(VIDEO, "Filters", "$adjustments · $framing"),
            Stage(VIDEO, "Renderer", if (ffmpeg) "FFmpeg video renderer" else "Media3 MediaCodec video renderer"),
            Stage(VIDEO, "Output", output),
        )
    }

    private fun pictureOf(picture: PictureAdjustment): List<String> = buildList {
        if (picture.brightness != 0f) add("brightness ${signed(picture.brightness * 100)} %")
        if (picture.contrast != 1f) add("contrast ${number(picture.contrast * 100)} %")
        if (picture.saturation != 1f) add("saturation ${number(picture.saturation * 100)} %")
        if (picture.gamma != 1f) add("gamma ${number(picture.gamma)}")
    }

    private fun audioStages(audio: Audio): List<Stage> {
        val processing = buildList {
            if (audio.loudnessDb != 0f) add("loudness ${signed(audio.loudnessDb)} dB")
            if (audio.nightMode) add("night mode")
            if (audio.speed != 1f) add("speed ${number(audio.speed)}× (Sonic)")
            if (audio.delayMs != 0) add("delay ${audio.delayMs} ms")
            if (audio.spatial) add("spatial audio")
        }.ifEmpty { listOf("none") }.joinToString(", ")
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
            else -> "none (${audio.sinkChannels} channels)"
        }
        val sink = when {
            audio.passthrough -> "AudioTrack · passthrough of ${audio.sinkEncoding ?: "encoded sound"}"
            audio.sinkSampleRate <= 0 -> "AudioTrack · not started yet"
            else -> buildList {
                add("AudioTrack")
                add(khz(audio.sinkSampleRate))
                add("${audio.sinkChannels} channels")
                audio.sinkEncoding?.let { add(it) }
                if (audio.offload) add("offloaded")
            }.joinToString(" · ")
        }
        return listOf(
            Stage(AUDIO, "Decoder", decoderKind(audio.decoder)),
            Stage(AUDIO, "Processing", processing),
            Stage(AUDIO, "Resampling", resampling),
            Stage(AUDIO, "Mixing", mixing),
            Stage(AUDIO, "Sink", sink),
            Stage(AUDIO, "Output", audio.device),
        )
    }

    private fun khz(rate: Int): String = number(rate / 1000f) + " kHz"

    private fun number(value: Float): String =
        String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')

    private fun signed(value: Float): String = (if (value > 0) "+" else "") + number(value)

    private const val FFMPEG_CONVERSION = "YUV to the surface's format by libyuv"
    private const val DIRECT_CONVERSION = "none — the decoder draws straight onto the surface"
    private const val SOURCE = "Source"
    private const val VIDEO = "Video"
    private const val AUDIO = "Audio"
    private const val PLAYER = "Player"

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
    )
}
