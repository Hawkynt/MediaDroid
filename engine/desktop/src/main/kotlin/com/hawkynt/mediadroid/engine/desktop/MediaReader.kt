package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.engine.Track
import com.hawkynt.mediadroid.library.FsFile
import com.hawkynt.mediadroid.player.AssEvents
import com.hawkynt.mediadroid.player.AssFonts
import com.hawkynt.mediadroid.player.ReplayGain
import com.hawkynt.mediadroid.player.SubtitleText
import com.hawkynt.mediadroid.player.Teletext
import com.hawkynt.mediadroid.player.ToneMap
import com.hawkynt.mediadroid.player.YuvMatrix
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
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
 * [SAMPLE_RATE], each with its time in milliseconds. Not thread-safe: one reader, one thread.
 */
class MediaReader(path: String, from: FsFile? = null) : AutoCloseable {

    /**
     * A decoded picture: [rgba] is [width] × [height] × 4 bytes, rows from the top — or, for an HDR video ([hdr] its
     * transfer), its coded BT.2020 values at 16 bits, 8 bytes a pixel (little-endian), for the stage to tone-map.
     * [planes]: the 4:2:0 frame it was converted from, where the reader was asked to keep them ([keepPlanes]) — for the
     * stage to enlarge its chroma itself.
     */
    class Picture(
        val ptsMs: Long,
        val width: Int,
        val height: Int,
        val rgba: ByteArray,
        val hdr: ToneMap.Transfer? = null,
        val planes: Planes? = null,
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

    /** The picture's frames a second (the stream's average), 0 where the file does not say or has no picture. */
    val frameRate: Float

    /** The file's video, audio and subtitle streams as tracks, id the stream's index. */
    val tracks: List<Track>

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
            } ?: listOf(Track("$i", kind, language?.takeIf { it != "und" }, title, i == videoIndex || i == audioIndex))
        }.flatten()
        if (videoIndex >= 0) video = openDecoder(videoIndex)
        if (audioIndex >= 0) audio = openDecoder(audioIndex)
        width = video?.width() ?: 0
        height = video?.height() ?: 0
        hdr = if (videoIndex < 0) null else when (format.streams(videoIndex).codecpar().color_trc()) {
            avutil.AVCOL_TRC_SMPTE2084 -> ToneMap.Transfer.PQ
            avutil.AVCOL_TRC_ARIB_STD_B67 -> ToneMap.Transfer.HLG
            else -> null
        }
        frameRate = if (videoIndex < 0) 0f else {
            val rate = format.streams(videoIndex).avg_frame_rate()
            if (rate.num() > 0 && rate.den() > 0) rate.num().toFloat() / rate.den() else 0f
        }
        pixelAspect = if (videoIndex < 0) 1f else {
            val guessed = avformat.av_guess_sample_aspect_ratio(format, format.streams(videoIndex), null)
            if (guessed.num() > 0 && guessed.den() > 0) guessed.num().toFloat() / guessed.den() else 1f
        }
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
                if (ms >= skipBeforeMs) pending.addLast(picture(ms))
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

    private fun picture(ptsMs: Long): Picture {
        val w = frame.width()
        val h = frame.height()
        // HDR keeps its coded values at 16 bits, through BT.2020's matrix to full-range R'G'B'.
        val deep = hdr != null
        val bytes = if (deep) 8 else 4
        if (sws == null || rgbaFrame.width() != w || rgbaFrame.height() != h) {
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
        return Picture(ptsMs, w, h, out, hdr, if (keepPlanes && !deep) planes(w, h) else null)
    }

    /** The decoded frame's planes, where it is 8-bit 4:2:0 (yuv420p, or MJPEG's yuvj420p); else null. */
    private fun planes(w: Int, h: Int): Planes? {
        val format = frame.format()
        if (format != avutil.AV_PIX_FMT_YUV420P && format != avutil.AV_PIX_FMT_YUVJ420P) return null
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
        return Planes(plane(0, w, h), plane(1, cw, ch), plane(2, cw, ch), YuvMatrix.of(signalled, h), full)
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
        avcodec.av_packet_free(packet)
        avformat.avformat_close_input(format)
        input?.close()
    }

    companion object {
        /** The rate all sound is resampled to for the output. */
        const val SAMPLE_RATE = 48_000

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
