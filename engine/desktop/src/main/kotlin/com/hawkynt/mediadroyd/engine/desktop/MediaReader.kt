package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.engine.PlaybackReport
import com.hawkynt.mediadroyd.engine.Track
import com.hawkynt.mediadroyd.engine.TrackFormat
import com.hawkynt.mediadroyd.library.FsFile
import com.hawkynt.mediadroyd.player.AssEvents
import com.hawkynt.mediadroyd.player.AssFonts
import com.hawkynt.mediadroyd.player.ReplayGain
import com.hawkynt.mediadroyd.player.SubtitleText
import com.hawkynt.mediadroyd.player.Teletext
import com.hawkynt.mediadroyd.player.ToneMap
import com.hawkynt.mediadroyd.player.YuvMatrix
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVBufferRef
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swresample
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer

/**
 * A media file read and decoded by FFmpeg, one item at a time — the one at [path], or [from] a network source with
 * [path] its location there: the pictures as RGBA bytes at the file's own size, the sound as 16-bit stereo PCM at
 * [SAMPLE_RATE], each with its time in milliseconds. The picture is decoded on the first of the [decoders]
 * (HardwareDecoding) that opens for its codec and copied back from the graphics chip, else in software — and in
 * software from there on should the chip fail midway. Not thread-safe: one reader, one thread.
 */
class MediaReader(
    path: String,
    from: FsFile? = null,
    private val decoders: List<HardwareDecoding.Device> = HardwareDecoding.candidates(softwareFirst = false),
) : AutoCloseable {

    /**
     * A decoded picture: [rgba] is [width] × [height] × 4 bytes, rows from the top — or, for an HDR video ([hdr] its
     * transfer), its coded BT.2020 values at 16 bits, 8 bytes a pixel (little-endian), for the stage to tone-map.
     * [planes]: the 4:2:0 frame it was converted from, where the reader was asked to keep them ([keepPlanes]) — for the
     * stage to enlarge its chroma itself. [palColour]: the video's colour tags name PAL's colour
     * ([MediaReader.palColour]).
     */
    class Picture(
        val ptsMs: Long,
        val width: Int,
        val height: Int,
        val rgba: ByteArray,
        val hdr: ToneMap.Transfer? = null,
        val planes: Planes? = null,
        val palColour: Boolean = false,
    ) {
        /** The picture as 8-bit RGBA: an HDR one's coded values cut to 8 bits, untone-mapped (no stage to map it). */
        fun eightBit(): Picture =
            if (hdr == null) this else Picture(ptsMs, width, height, ByteArray(width * height * 4) { rgba[it * 2 + 1] })
    }

    /**
     * An 8-bit 4:2:0 frame as decoded: [y] at the picture's size, [u] and [v] half that each way (rounded up), rows
     * from the top without padding; its colours through [matrix], [fullRange] where coded over 0..255 (JPEG's range).
     */
    class Planes(val y: ByteArray, val u: ByteArray, val v: ByteArray, val matrix: YuvMatrix, val fullRange: Boolean)

    /**
     * Whether pictures carry their 4:2:0 [Planes] beside the RGBA — for a chroma upscaler other than bilinear (see
     * ChromaUpscaling), which the stage runs on them; set from any thread, taken from the next picture.
     */
    @Volatile var keepPlanes = false

    /** Decoded sound: [pcm] is interleaved 16-bit little-endian stereo at [SAMPLE_RATE]. */
    class Sound(val ptsMs: Long, val pcm: ByteArray)

    /**
     * A subtitle line shown from [startMs] to [endMs], as text (SubtitleText). A teletext page's ([UNTIL_REPLACED])
     * shows until the next one of its track: empty [text] where the page was cleared.
     */
    data class Cue(val startMs: Long, val endMs: Long, val text: String, val second: Boolean = false)

    /**
     * An ASS/SSA event as libass takes it ([data]: `ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text`),
     * shown from [startMs] for [durationMs]: read alongside the [Cue] while the selected stream is ASS (AssEvents).
     */
    class AssEvent(val startMs: Long, val durationMs: Long, val data: String, val second: Boolean = false)

    // A file from a network source is read through FsInput; a local one by FFmpeg itself.
    private val input: FsInput? = from?.let(::FsInput)
    private val format: AVFormatContext = if (input == null) {
        AVFormatContext(null)
    } else {
        // Given before opening, FFmpeg marks it custom I/O itself and leaves it to us to free.
        avformat.avformat_alloc_context().pb(input.context)
    }
    private val packet: AVPacket = avcodec.av_packet_alloc()
    private val frame: AVFrame = avutil.av_frame_alloc()
    // A hardware decoder's picture copied back to the processor, the device it is decoded on and the pixel format of
    // the pictures it hands over; the devices after it, tried where its driver refuses the stream before it ever
    // decoded a picture ([proven]); and those to reopen the decoder on, where it failed.
    private val copied: AVFrame = avutil.av_frame_alloc()
    private var device: AVBufferRef? = null
    private var deviceFormat = avutil.AV_PIX_FMT_NONE
    private var opened: HardwareDecoding.Device? = null
    private var untried: List<HardwareDecoding.Device> = emptyList()
    private var proven = false
    private var retry: List<HardwareDecoding.Device> = emptyList()
    private val videoIndex: Int
    private var audioIndex: Int
    private var video: AVCodecContext? = null
    private var audio: AVCodecContext? = null
    // The subtitle track read and the one read second ([selectSecondText]): its stream, its decoder (or a teletext
    // page's), and whether it is ASS.
    private inner class TextSlot(val second: Boolean) {
        var index = -1
        var isAss = false
        var teletext: Teletext.Decoder? = null
        var context: AVCodecContext? = null

        fun select(id: String?) {
            val page = id?.let(Teletext::ofTrackId)
            if (page == null) {
                select(id?.toIntOrNull() ?: -1)
                return
            }
            select(-1)
            if (page.first !in 0 until format.nb_streams()) return
            index = page.first
            teletext = Teletext.Decoder(page.second)
        }

        fun select(stream: Int) {
            if (stream == index && teletext == null) return
            close()
            if (stream < 0 || format.streams(stream).codecpar().codec_type() != avutil.AVMEDIA_TYPE_SUBTITLE) return
            index = stream
            context = openDecoder(stream)
            isAss = assHeader(stream) != null
        }

        fun packet(input: AVPacket) {
            teletext?.let { page(this, it, input) } ?: context?.let { cue(this, it, input) }
        }

        fun close() {
            context?.let { avcodec.avcodec_free_context(it) }
            context = null
            teletext = null
            index = -1
            isAss = false
        }
    }

    private val text = TextSlot(second = false)
    private val secondText = TextSlot(second = true)
    private val subtitle = org.bytedeco.ffmpeg.avcodec.AVSubtitle()
    private val gotSubtitle = org.bytedeco.javacpp.IntPointer(1)
    private var sws: SwsContext? = null
    private var swsFormat = avutil.AV_PIX_FMT_NONE
    private var swr: SwrContext? = null
    private val rgbaFrame: AVFrame = avutil.av_frame_alloc()
    private val soundFrame: AVFrame = avutil.av_frame_alloc()
    private val pending = ArrayDeque<Any>()
    private var skipBeforeMs = Long.MIN_VALUE
    private var ended = false

    /** How long the file lasts, in milliseconds (0 when it does not say). */
    val durationMs: Long

    /** The picture's size; 0 without a video stream. */
    val width: Int
    val height: Int

    /** The shape of the picture's pixels, width over height (16/15 for PAL DV), 1 where the file does not say. */
    val pixelAspect: Float

    /** How the video is coded where it is HDR (SMPTE ST 2084's PQ, or HLG), else null. */
    val hdr: ToneMap.Transfer?

    /** The bits of each of the picture's samples as coded (8, 10, 12), 8 where the file does not say. */
    val bitDepth: Int

    /** The picture's frames a second (the stream's average), 0 where the file does not say or has no picture. */
    val frameRate: Float

    /**
     * Whether the video's colour tags name PAL's colour — BT.470 System B/G primaries or transfer (FFmpeg's
     * `bt470bg`, `gamma28`) — which at NTSC's rate and line count is PAL-M, for the dot-crawl comb (DotCrawl).
     */
    val palColour: Boolean

    /** The file's video, audio and subtitle streams as tracks, id the stream's index, each with its format. */
    val tracks: List<Track>

    /**
     * What FFmpeg read of the file, for the engine's report: the demuxer, the file's tags, and the picture's and the
     * sound's formats as coded and decoded.
     */
    val facts: Facts

    /**
     * What FFmpeg read of the file: its [demuxer], its [tags]; the picture's codec, colour space, range and pixel
     * format; the sound's sample rate, channels and sample format (null or 0 without one, or where it does not say).
     */
    data class Facts(
        val demuxer: String?,
        val tags: PlaybackReport.FileTags,
        val videoCodec: String?,
        val colorSpace: String?,
        val range: String?,
        val pixelFormat: String?,
        val sampleRate: Int,
        val channels: Int,
        val sampleFormat: String?,
    )

    /**
     * The hardware decoder the picture is decoded on, null for software: from the first picture on, what actually
     * decodes it — a driver that opens but refuses the stream's profile leaves it to software.
     */
    @Volatile var hardware: HardwareDecoding.Device? = null
        private set

    /** The picture's decoder by FFmpeg's name for it (h264, hevc …), as opened; null without a picture. */
    val videoDecoder: String?

    /** The sound's decoder by FFmpeg's name for it (aac, ac3 …), as opened; null without sound. */
    val audioDecoder: String?

    /**
     * The fonts the file carries for its subtitles (Matroska attachments), by file name: those said to be fonts by
     * their MIME type or, without one, by their extension.
     */
    val fonts: List<Pair<String, ByteArray>> by lazy {
        (0 until format.nb_streams()).mapNotNull { i ->
            val stream = format.streams(i)
            val parameters = stream.codecpar()
            if (parameters.codec_type() != avutil.AVMEDIA_TYPE_ATTACHMENT || parameters.extradata_size() <= 0) {
                return@mapNotNull null
            }
            val name = avutil.av_dict_get(stream.metadata(), "filename", null, 0)?.value()?.string.orEmpty()
            val mime = avutil.av_dict_get(stream.metadata(), "mimetype", null, 0)?.value()?.string
            if (!AssFonts.isFont(mime, name)) return@mapNotNull null
            val data = ByteArray(parameters.extradata_size())
            parameters.extradata().get(data)
            name to data
        }
    }

    init {
        FfmpegNatives.load()
        if (avformat.avformat_open_input(format, path, null, null as AVDictionary?) < 0) {
            input?.close()
            error("cannot open $path")
        }
        check(avformat.avformat_find_stream_info(format, null as PointerPointer<*>?) >= 0) { "no streams in $path" }
        durationMs = if (format.duration() > 0) format.duration() / 1000 else 0
        videoIndex = best(avutil.AVMEDIA_TYPE_VIDEO)
        audioIndex = best(avutil.AVMEDIA_TYPE_AUDIO)
        tracks = (0 until format.nb_streams()).mapNotNull { i ->
            val stream = format.streams(i)
            val kind = when (stream.codecpar().codec_type()) {
                avutil.AVMEDIA_TYPE_VIDEO -> Track.Kind.VIDEO
                avutil.AVMEDIA_TYPE_AUDIO -> Track.Kind.AUDIO
                avutil.AVMEDIA_TYPE_SUBTITLE -> Track.Kind.TEXT
                else -> return@mapNotNull null
            }
            val language = avutil.av_dict_get(stream.metadata(), "language", null, 0)?.value()?.string
            val title = avutil.av_dict_get(stream.metadata(), "title", null, 0)?.value()?.string
            // A teletext stream: a track for each subtitle page its descriptor lists, none for the rest.
            teletextPages(i)?.map { page ->
                Track(Teletext.trackId(i, page), kind, page.language.trim(), "Teletext ${page.label}")
            } ?: listOf(
                Track(
                    "$i", kind, language?.takeIf { it != "und" }, title, i == videoIndex || i == audioIndex,
                    format = formatOf(i, kind),
                ),
            )
        }.flatten()
        if (videoIndex >= 0) video = openVideoDecoder(decoders)
        if (audioIndex >= 0) audio = openDecoder(audioIndex)
        // Read now: the decoders' contexts go with the reader, from another thread.
        videoDecoder = video?.codec()?.name()?.string
        audioDecoder = audio?.codec()?.name()?.string
        width = video?.width() ?: 0
        height = video?.height() ?: 0
        bitDepth = if (videoIndex < 0) {
            8
        } else {
            avutil.av_pix_fmt_desc_get(format.streams(videoIndex).codecpar().format())?.comp(0)?.depth()
                ?.takeIf { it > 0 } ?: 8
        }
        hdr = if (videoIndex < 0) null else when (format.streams(videoIndex).codecpar().color_trc()) {
            avutil.AVCOL_TRC_SMPTE2084 -> ToneMap.Transfer.PQ
            avutil.AVCOL_TRC_ARIB_STD_B67 -> ToneMap.Transfer.HLG
            else -> null
        }
        // The colour matrix is left out: BT.470 B/G's and SMPTE 170M's are the same, and files swap their tags.
        palColour = videoIndex >= 0 && format.streams(videoIndex).codecpar().let {
            it.color_primaries() == avutil.AVCOL_PRI_BT470BG || it.color_trc() == avutil.AVCOL_TRC_GAMMA28
        }
        frameRate = if (videoIndex < 0) 0f else {
            val rate = format.streams(videoIndex).avg_frame_rate()
            if (rate.num() > 0 && rate.den() > 0) rate.num().toFloat() / rate.den() else 0f
        }
        pixelAspect = if (videoIndex < 0) 1f else {
            val guessed = avformat.av_guess_sample_aspect_ratio(format, format.streams(videoIndex), null)
            if (guessed.num() > 0 && guessed.den() > 0) guessed.num().toFloat() / guessed.den() else 1f
        }
        facts = readFacts()
    }

    /** Stream [index]'s format as the file holds it: FFmpeg's codec name, the picture's size, the sound's layout. */
    private fun formatOf(index: Int, kind: Track.Kind): TrackFormat {
        val parameters = format.streams(index).codecpar()
        val codec = avcodec.avcodec_get_name(parameters.codec_id())?.string
        return when (kind) {
            Track.Kind.VIDEO -> {
                val rate = format.streams(index).avg_frame_rate()
                TrackFormat(
                    "video/x-ffmpeg-$codec", codec, parameters.width(), parameters.height(),
                    if (rate.num() > 0 && rate.den() > 0) rate.num().toFloat() / rate.den() else 0f,
                )
            }
            Track.Kind.AUDIO -> TrackFormat(
                "audio/x-ffmpeg-$codec", codec,
                channels = parameters.ch_layout().nb_channels(), sampleRate = parameters.sample_rate(),
            )
            Track.Kind.TEXT -> TrackFormat(codec?.let { SUBTITLE_MIME_TYPES[it] ?: it }, codec)
        }
    }

    private fun readFacts(): Facts {
        fun tag(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            avutil.av_dict_get(format.metadata(), key, null, 0)?.value()?.string?.takeIf { it.isNotBlank() }
        }
        val video = videoIndex.takeIf { it >= 0 }?.let { format.streams(it).codecpar() }
        val sound = audioIndex.takeIf { it >= 0 }?.let { format.streams(it).codecpar() }
        return Facts(
            demuxer = format.iformat()?.name()?.string,
            tags = PlaybackReport.FileTags(
                title = tag("title"),
                artist = tag("artist", "album_artist"),
                album = tag("album"),
                genre = tag("genre"),
                year = tag("date", "year", "creation_time")?.take(4)?.toIntOrNull(),
                writer = tag("composer"),
                description = tag("description", "comment", "synopsis"),
            ),
            videoCodec = video?.let { avcodec.avcodec_get_name(it.codec_id())?.string },
            colorSpace = when (video?.color_space()) {
                avutil.AVCOL_SPC_BT709 -> "BT.709"
                avutil.AVCOL_SPC_BT470BG, avutil.AVCOL_SPC_SMPTE170M -> "BT.601"
                avutil.AVCOL_SPC_BT2020_NCL, avutil.AVCOL_SPC_BT2020_CL -> "BT.2020"
                else -> null
            },
            range = when (video?.color_range()) {
                avutil.AVCOL_RANGE_MPEG -> "limited range"
                avutil.AVCOL_RANGE_JPEG -> "full range"
                else -> null
            },
            pixelFormat = video?.format()?.takeIf { it >= 0 }?.let { avutil.av_get_pix_fmt_name(it)?.string },
            sampleRate = sound?.sample_rate() ?: 0,
            channels = sound?.ch_layout()?.nb_channels() ?: 0,
            sampleFormat = sound?.format()?.takeIf { it >= 0 }?.let { avutil.av_get_sample_fmt_name(it)?.string },
        )
    }

    /** The subtitle pages of stream [index] where it is DVB teletext, else null. */
    private fun teletextPages(index: Int): List<Teletext.Page>? {
        val parameters = format.streams(index).codecpar()
        if (avcodec.avcodec_get_name(parameters.codec_id()).string != Teletext.FFMPEG_CODEC) return null
        val extradata = ByteArray(maxOf(parameters.extradata_size(), 0)).also { parameters.extradata()?.get(it) }
        val languages = avutil.av_dict_get(format.streams(index).metadata(), "language", null, 0)?.value()?.string
        return Teletext.ffmpegPages(extradata, languages).filter { it.isSubtitles }.distinct()
    }

    private fun best(type: Int): Int = avformat.av_find_best_stream(format, type, -1, -1, null as PointerPointer<*>?, 0)

    /**
     * The picture's decoder: on the first of [devices] its codec can be decoded on and that opens (FFmpeg's own
     * get_format then takes the device's pictures, or the software ones where the driver refuses the stream), else in
     * software. Converted from FFmpeg's doc/examples/hw_decode.c (MIT).
     */
    private fun openVideoDecoder(devices: List<HardwareDecoding.Device>): AVCodecContext {
        val parameters = format.streams(videoIndex).codecpar()
        val codec = checkNotNull(avcodec.avcodec_find_decoder(parameters.codec_id())) { "no decoder for the picture" }
        val picked = HardwareDecoding.pick(
            devices,
            supports = { deviceFormatOf(codec, it) != avutil.AV_PIX_FMT_NONE },
            open = ::openDevice,
        )
        val context = avcodec.avcodec_alloc_context3(codec)
        avcodec.avcodec_parameters_to_context(context, parameters)
        if (picked != null) {
            context.hw_device_ctx(avutil.av_buffer_ref(picked.second))
            // One thread on a chip: frame threads would only hold more of its pictures back.
            context.thread_count(1)
        } else {
            context.thread_count(0)
        }
        if (avcodec.avcodec_open2(context, codec, null as AVDictionary?) >= 0) {
            device = picked?.second
            deviceFormat = picked?.let { deviceFormatOf(codec, it.first) } ?: avutil.AV_PIX_FMT_NONE
            opened = picked?.first
            hardware = picked?.first
            untried = picked?.let { devices.drop(devices.indexOf(it.first) + 1) }.orEmpty()
            proven = false
            return context
        }
        avcodec.avcodec_free_context(context)
        check(picked != null) { "cannot open the decoder" }
        unref(picked.second)
        return openVideoDecoder(emptyList())
    }

    /** The pixel format [codec] hands its pictures over in on [device], NONE where it cannot decode there. */
    private fun deviceFormatOf(codec: AVCodec, device: HardwareDecoding.Device): Int {
        val type = avutil.av_hwdevice_find_type_by_name(device.ffmpegName)
        if (type == avutil.AV_HWDEVICE_TYPE_NONE) return avutil.AV_PIX_FMT_NONE
        var i = 0
        while (true) {
            val config = avcodec.avcodec_get_hw_config(codec, i++) ?: return avutil.AV_PIX_FMT_NONE
            if ((config.methods() and avcodec.AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) != 0 &&
                config.device_type() == type
            ) {
                return config.pix_fmt()
            }
        }
    }

    /** [device] opened on the system's default chip for it; null where there is none or its driver will not open. */
    private fun openDevice(device: HardwareDecoding.Device): AVBufferRef? {
        val type = avutil.av_hwdevice_find_type_by_name(device.ffmpegName)
        if (type == avutil.AV_HWDEVICE_TYPE_NONE) return null
        val opened = PointerPointer<AVBufferRef>(1L)
        if (avutil.av_hwdevice_ctx_create(opened, type, null as BytePointer?, null, 0) < 0) return null
        return opened.get(AVBufferRef::class.java, 0L)
    }

    private fun unref(buffer: AVBufferRef) = avutil.av_buffer_unref(PointerPointer<AVBufferRef>(1L).put(buffer))

    /**
     * The picture decoded on [devices] from here (software where none opens), the reader back at [atMs] — the picture
     * the device before could not decode or copy back the first to come.
     */
    private fun reopenVideo(devices: List<HardwareDecoding.Device>, atMs: Long) {
        video?.let { avcodec.avcodec_free_context(it) }
        device?.let(::unref)
        device = null
        video = openVideoDecoder(devices)
        seek(atMs)
    }

    private fun openDecoder(index: Int): AVCodecContext {
        val parameters = format.streams(index).codecpar()
        val codec = checkNotNull(avcodec.avcodec_find_decoder(parameters.codec_id())) { "no decoder for stream $index" }
        val context = avcodec.avcodec_alloc_context3(codec)
        avcodec.avcodec_parameters_to_context(context, parameters)
        context.thread_count(0)
        check(avcodec.avcodec_open2(context, codec, null as AVDictionary?) >= 0) { "cannot open the decoder" }
        return context
    }

    /**
     * The ReplayGain of the sound playing, from its tags as FFmpeg reads them — the file's (FLAC, MP3, MP4) and then
     * its audio stream's own (Ogg, Opus, Matroska), so the stream's wins — or null without one.
     */
    val replayGain: ReplayGain?
        get() {
            if (audioIndex < 0) return null
            return ReplayGain.parse(tagsOf(format.metadata()) + tagsOf(format.streams(audioIndex).metadata()))
        }

    private fun tagsOf(dictionary: AVDictionary?): List<Pair<String, String>> = buildList {
        if (dictionary == null) return@buildList
        var entry = avutil.av_dict_iterate(dictionary, null)
        while (entry != null) {
            val key = entry.key()?.string
            val value = entry.value()?.string
            if (key != null && value != null) add(key to value)
            entry = avutil.av_dict_iterate(dictionary, entry)
        }
    }

    /** Plays the audio stream [index] (a track id) from the next read on; -1 for no sound from this file. */
    fun selectAudio(index: Int) {
        if (index == audioIndex) return
        if (index >= 0 && format.streams(index).codecpar().codec_type() != avutil.AVMEDIA_TYPE_AUDIO) return
        audio?.let { avcodec.avcodec_free_context(it) }
        audio = null
        swr?.let { swresample.swr_free(it) }
        swr = null
        audioIndex = index
        if (index >= 0) audio = openDecoder(index)
    }

    /**
     * Decodes the subtitle track [id] from the next read on, as [Cue]s: a stream's index, or a teletext page's
     * ([Teletext.trackId]); null for none.
     */
    fun selectText(id: String?) = text.select(id)

    /** Decodes the text subtitle stream [index] (a track id) from the next read on, as [Cue]s; -1 for none. */
    fun selectText(index: Int) = text.select(index)

    /**
     * Decodes the subtitle track [id] as well, shown second (as [selectText] does; null for none): its [Cue]s and
     * [AssEvent]s come marked [Cue.second].
     */
    fun selectSecondText(id: String?) = secondText.select(id)

    /** The header of the subtitle stream [index] (its codec private) where it is ASS or SSA, else null. */
    fun assHeader(index: Int): ByteArray? {
        if (index !in 0 until format.nb_streams()) return null
        val parameters = format.streams(index).codecpar()
        if (parameters.codec_type() != avutil.AVMEDIA_TYPE_SUBTITLE) return null
        if (!AssEvents.isAssCodec(avcodec.avcodec_get_name(parameters.codec_id()).string)) {
            return null
        }
        if (parameters.extradata_size() <= 0) return null
        return ByteArray(parameters.extradata_size()).also { parameters.extradata().get(it) }
    }

    /**
     * Goes to [positionMs]: from the key frame before it, the pictures and sound before it decoded but not handed
     * out, so the next picture is the one at that time.
     */
    fun seek(positionMs: Long) {
        avformat.av_seek_frame(format, -1, positionMs * 1000, avformat.AVSEEK_FLAG_BACKWARD)
        video?.let { avcodec.avcodec_flush_buffers(it) }
        audio?.let { avcodec.avcodec_flush_buffers(it) }
        text.teletext?.reset()
        secondText.teletext?.reset()
        pending.clear()
        ended = false
        skipBeforeMs = positionMs
    }

    /**
     * Every event of the ASS stream [index] from here to the end, handed to [each] as it is read — the rest of the file
     * demuxed but not decoded — until [cancelled]. What a seek cannot bring back (events before the point it lands on)
     * comes this way: libass keeps them all and shows each at its time.
     */
    fun forEachAssEvent(index: Int, cancelled: () -> Boolean, each: (AssEvent) -> Unit) {
        selectText(index)
        if (!text.isAss) return
        val context = text.context ?: return
        while (!cancelled() && avformat.av_read_frame(format, packet) >= 0) {
            if (packet.stream_index() == text.index) cue(text, context, packet)
            avcodec.av_packet_unref(packet)
            while (pending.isNotEmpty()) (pending.removeFirst() as? AssEvent)?.let(each)
        }
    }

    /** The next decoded picture or sound, in the file's order; null at the end. */
    fun read(): Any? {
        while (pending.isEmpty()) {
            if (ended) return null
            if (avformat.av_read_frame(format, packet) < 0) {
                ended = true
                video?.let { drain(it, null) }
                audio?.let { drain(it, null) }
                continue
            }
            val index = packet.stream_index()
            when (index) {
                videoIndex -> video?.let { drain(it, packet) }
                audioIndex -> audio?.let { drain(it, packet) }
            }
            // Both subtitle tracks may be pages of one teletext stream.
            if (index == text.index) text.packet(packet)
            if (index == secondText.index) secondText.packet(packet)
            avcodec.av_packet_unref(packet)
        }
        return pending.removeFirst()
    }

    private fun drain(context: AVCodecContext, input: AVPacket?) {
        avcodec.avcodec_send_packet(context, input)
        while (avcodec.avcodec_receive_frame(context, frame) == 0) {
            val stream = format.streams(if (context == video) videoIndex else audioIndex)
            val base = stream.time_base()
            val pts = frame.best_effort_timestamp().takeIf { it != avutil.AV_NOPTS_VALUE } ?: 0
            val start = if (stream.start_time() != avutil.AV_NOPTS_VALUE) stream.start_time() else 0
            val ms = Math.round((pts - start) * 1000.0 * base.num() / base.den())
            if (context == video) {
                if (ms >= skipBeforeMs) {
                    val decoded = decodedPicture() ?: run {
                        avutil.av_frame_unref(frame)
                        reopenVideo(retry, ms)
                        return
                    }
                    pending.addLast(picture(ms, decoded))
                }
            } else {
                sound(ms)?.let { if (it.ptsMs + it.pcm.size / 4 * 1000L / SAMPLE_RATE >= skipBeforeMs) pending += it }
            }
            avutil.av_frame_unref(frame)
        }
    }

    private fun cue(slot: TextSlot, context: AVCodecContext, input: AVPacket) {
        val decoded = avcodec.avcodec_decode_subtitle2(context, subtitle, gotSubtitle, input)
        if (decoded < 0 || gotSubtitle.get() == 0) return
        val stream = format.streams(slot.index)
        val base = stream.time_base()
        val pts = if (input.pts() != avutil.AV_NOPTS_VALUE) input.pts() else input.dts()
        val atMs = Math.round(pts * 1000.0 * base.num() / base.den())
        val startMs = atMs + subtitle.start_display_time()
        val endMs = if (subtitle.end_display_time() > subtitle.start_display_time()) {
            atMs + subtitle.end_display_time()
        } else {
            atMs + Math.round(input.duration() * 1000.0 * base.num() / base.den())
        }
        val events = (0 until subtitle.num_rects()).mapNotNull { i -> subtitle.rects(i).ass()?.string }
        val lines = events.map { SubtitleText.fromAssEvent(it) }.filter { it.isNotEmpty() }
        if (endMs >= skipBeforeMs) {
            if (slot.isAss) events.forEach { pending.addLast(AssEvent(startMs, endMs - startMs, it, slot.second)) }
            if (lines.isNotEmpty()) pending.addLast(Cue(startMs, endMs, lines.joinToString("\n"), slot.second))
        }
        avcodec.avsubtitle_free(subtitle)
    }

    /** A teletext packet into [decoder]: where its page shows something new, that as a [Cue] until replaced. */
    private fun page(slot: TextSlot, decoder: Teletext.Decoder, input: AVPacket) {
        val size = input.size()
        if (size <= 0) return
        val data = ByteArray(size).also { input.data().get(it) }
        val shown = decoder.feed(data) ?: return
        val stream = format.streams(slot.index)
        val base = stream.time_base()
        val pts = if (input.pts() != avutil.AV_NOPTS_VALUE) input.pts() else input.dts()
        if (pts == avutil.AV_NOPTS_VALUE) return
        // On the pictures' clock: from the video's start, as they are.
        val origin = if (videoIndex >= 0) {
            val video = format.streams(videoIndex)
            if (video.start_time() != avutil.AV_NOPTS_VALUE) {
                avutil.av_rescale_q(video.start_time(), video.time_base(), base)
            } else {
                0
            }
        } else {
            0
        }
        val atMs = Math.round((pts - origin) * 1000.0 * base.num() / base.den())
        pending.addLast(Cue(atMs, UNTIL_REPLACED, shown.text, slot.second))
    }

    /**
     * The frame decoded, in the processor's memory: a hardware decoder's copied back (as NV12, or P010 for more than 8
     * bits). Null where the decoder is to be opened again on [retry]: the next device where the driver refused the
     * stream before decoding any of it, software where the copy failed.
     */
    private fun decodedPicture(): AVFrame? {
        if (deviceFormat == avutil.AV_PIX_FMT_NONE) return frame
        if (frame.format() != deviceFormat) {
            // The device opened but its driver refused the stream (a codec or profile its chip lacks, no chip behind
            // it): FFmpeg decodes it in software. Before any picture of the chip's, the next device is tried.
            if (!proven && untried.isNotEmpty()) {
                retry = untried
                return null
            }
            hardware = null
            return frame
        }
        avutil.av_frame_unref(copied)
        if (avutil.av_hwframe_transfer_data(copied, frame, 0) < 0) {
            retry = emptyList()
            return null
        }
        avutil.av_frame_copy_props(copied, frame)
        proven = true
        hardware = opened
        return copied
    }

    private fun picture(ptsMs: Long, frame: AVFrame): Picture {
        val w = frame.width()
        val h = frame.height()
        // HDR keeps its coded values at 16 bits, through BT.2020's matrix to full-range R'G'B'.
        val deep = hdr != null
        val bytes = if (deep) 8 else 4
        if (sws == null || rgbaFrame.width() != w || rgbaFrame.height() != h || swsFormat != frame.format()) {
            swsFormat = frame.format()
            sws?.let { swscale.sws_freeContext(it) }
            val target = if (deep) avutil.AV_PIX_FMT_RGBA64LE else avutil.AV_PIX_FMT_RGBA
            sws = swscale.sws_getContext(
                w, h, frame.format(), w, h, target, swscale.SWS_BILINEAR,
                null, null, null as DoublePointer?,
            )
            // The source's range from the frame: sws_getContext assumes limited for every YUV format but MJPEG's
            // yuvj, so a full-range yuv420p frame would come out with its blacks and whites clipped.
            val full = frame.color_range() == avutil.AVCOL_RANGE_JPEG || sourceRange(sws) == 1
            val coefficients = swscale.sws_getCoefficients(if (deep) swscale.SWS_CS_BT2020 else swscale.SWS_CS_DEFAULT)
            swscale.sws_setColorspaceDetails(
                sws, coefficients, if (full) 1 else 0, coefficients, 1, 0, 1 shl 16, 1 shl 16,
            )
            avutil.av_frame_unref(rgbaFrame)
            rgbaFrame.width(w).height(h).format(target)
            avutil.av_frame_get_buffer(rgbaFrame, 1)
        }
        swscale.sws_scale(sws, frame.data(), frame.linesize(), 0, h, rgbaFrame.data(), rgbaFrame.linesize())
        val out = ByteArray(w * h * bytes)
        val stride = rgbaFrame.linesize(0)
        val data: BytePointer = rgbaFrame.data(0)
        for (y in 0 until h) data.position((y * stride).toLong()).get(out, y * w * bytes, w * bytes)
        data.position(0)
        return Picture(ptsMs, w, h, out, hdr, if (keepPlanes && !deep) planes(frame, w, h) else null, palColour)
    }

    /**
     * The decoded [frame]'s planes, where it is 8-bit 4:2:0 (yuv420p, MJPEG's yuvj420p, or a hardware decoder's NV12,
     * its interleaved chroma taken apart); else null.
     */
    private fun planes(frame: AVFrame, w: Int, h: Int): Planes? {
        val format = frame.format()
        val nv12 = format == avutil.AV_PIX_FMT_NV12
        if (format != avutil.AV_PIX_FMT_YUV420P && format != avutil.AV_PIX_FMT_YUVJ420P && !nv12) return null
        fun plane(i: Int, pw: Int, ph: Int): ByteArray {
            val out = ByteArray(pw * ph)
            val data = frame.data(i)
            val stride = frame.linesize(i)
            for (y in 0 until ph) data.position(y.toLong() * stride).get(out, y * pw, pw)
            data.position(0)
            return out
        }
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val signalled = when (frame.colorspace()) {
            avutil.AVCOL_SPC_BT709 -> YuvMatrix.BT709
            avutil.AVCOL_SPC_BT470BG, avutil.AVCOL_SPC_SMPTE170M -> YuvMatrix.BT601
            avutil.AVCOL_SPC_BT2020_NCL -> YuvMatrix.BT2020
            else -> null
        }
        val full = format == avutil.AV_PIX_FMT_YUVJ420P || frame.color_range() == avutil.AVCOL_RANGE_JPEG
        val (u, v) = if (nv12) deinterleave(plane(1, cw * 2, ch)) else plane(1, cw, ch) to plane(2, cw, ch)
        return Planes(plane(0, w, h), u, v, YuvMatrix.of(signalled, h), full)
    }

    private fun sound(ptsMs: Long): Sound? {
        if (audio == null) return null
        // swr_convert_frame configures the resampler from the two frames: the decoder's, and 16-bit stereo at
        // SAMPLE_RATE.
        val resampler = swr ?: swresample.swr_alloc().also { swr = it }
        avutil.av_frame_unref(soundFrame)
        avutil.av_channel_layout_default(soundFrame.ch_layout(), 2)
        soundFrame.sample_rate(SAMPLE_RATE).format(avutil.AV_SAMPLE_FMT_S16)
        if (swresample.swr_convert_frame(resampler, soundFrame, frame) < 0) return null
        val samples = soundFrame.nb_samples()
        if (samples <= 0) return null
        val pcm = ByteArray(samples * 4)
        soundFrame.data(0).get(pcm)
        return Sound(ptsMs, pcm)
    }

    override fun close() {
        sws?.let { swscale.sws_freeContext(it) }
        swr?.let { swresample.swr_free(it) }
        video?.let { avcodec.avcodec_free_context(it) }
        audio?.let { avcodec.avcodec_free_context(it) }
        text.close()
        secondText.close()
        avutil.av_frame_free(rgbaFrame)
        avutil.av_frame_free(soundFrame)
        avutil.av_frame_free(frame)
        avutil.av_frame_free(copied)
        device?.let(::unref)
        avcodec.av_packet_free(packet)
        avformat.avformat_close_input(format)
        input?.close()
    }

    companion object {
        /** The rate all sound is resampled to for the output. */
        const val SAMPLE_RATE = 48_000

        /** FFmpeg's subtitle codecs by the MIME types the info sheet names them by (PlaybackChain.subtitleFormat). */
        val SUBTITLE_MIME_TYPES = mapOf(
            "subrip" to "application/x-subrip",
            "srt" to "application/x-subrip",
            "ass" to "text/x-ssa",
            "ssa" to "text/x-ssa",
            "webvtt" to "text/vtt",
            "mov_text" to "application/x-quicktime-tx3g",
            "ttml" to "application/ttml+xml",
            "hdmv_pgs_subtitle" to "application/pgs",
            "dvd_subtitle" to "application/vobsub",
            "dvb_subtitle" to "application/dvbsubs",
            Teletext.FFMPEG_CODEC to Teletext.MIME_TYPE,
        )

        /** NV12's chroma, U and V by turns, as its two planes. */
        internal fun deinterleave(uv: ByteArray): Pair<ByteArray, ByteArray> {
            val u = ByteArray(uv.size / 2)
            val v = ByteArray(uv.size / 2)
            for (i in u.indices) {
                u[i] = uv[2 * i]
                v[i] = uv[2 * i + 1]
            }
            return u to v
        }

        /** A [Cue]'s end where it shows until the next of its track replaces it (a teletext page). */
        const val UNTIL_REPLACED = Long.MAX_VALUE

        /** The source range [context] was set up with: 1 for full (MJPEG's yuvj formats), 0 for limited. */
        private fun sourceRange(context: SwsContext?): Int {
            val range = IntPointer(1L)
            val unused = IntPointer(1L)
            val tables = PointerPointer<IntPointer>(1L)
            swscale.sws_getColorspaceDetails(context, tables, range, tables, unused, unused, unused, unused)
            return range.get()
        }

        init {
            // FFmpeg's notes about estimated rates and durations are for its own command line, not a player's log.
            avutil.av_log_set_level(avutil.AV_LOG_ERROR)
        }
    }
}
