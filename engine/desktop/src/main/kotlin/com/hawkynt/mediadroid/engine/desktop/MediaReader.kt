package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.engine.Track
import com.hawkynt.mediadroid.library.FsFile
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
import org.bytedeco.javacpp.PointerPointer

/**
 * A media file read and decoded by FFmpeg, one item at a time — the one at [path], or [from] a network source with
 * [path] its location there: the pictures as RGBA bytes at the file's own size, the sound as 16-bit stereo PCM at
 * [SAMPLE_RATE], each with its time in milliseconds. Not thread-safe: one reader, one thread.
 */
class MediaReader(path: String, from: FsFile? = null) : AutoCloseable {

    /** A decoded picture: [rgba] is [width] × [height] × 4 bytes, rows from the top. */
    class Picture(val ptsMs: Long, val width: Int, val height: Int, val rgba: ByteArray)

    /** Decoded sound: [pcm] is interleaved 16-bit little-endian stereo at [SAMPLE_RATE]. */
    class Sound(val ptsMs: Long, val pcm: ByteArray)

    /** A subtitle line shown from [startMs] to [endMs], as text (SubtitleText). */
    data class Cue(val startMs: Long, val endMs: Long, val text: String)

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
    private var textIndex = -1
    private var text: AVCodecContext? = null
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

    /** The file's video, audio and subtitle streams as tracks, id the stream's index. */
    val tracks: List<Track>

    init {
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
            Track("$i", kind, language?.takeIf { it != "und" }, title, i == videoIndex || i == audioIndex)
        }
        if (videoIndex >= 0) video = openDecoder(videoIndex)
        if (audioIndex >= 0) audio = openDecoder(audioIndex)
        width = video?.width() ?: 0
        height = video?.height() ?: 0
        pixelAspect = if (videoIndex < 0) 1f else {
            val guessed = avformat.av_guess_sample_aspect_ratio(format, format.streams(videoIndex), null)
            if (guessed.num() > 0 && guessed.den() > 0) guessed.num().toFloat() / guessed.den() else 1f
        }
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

    /** Decodes the text subtitle stream [index] (a track id) from the next read on, as [Cue]s; -1 for none. */
    fun selectText(index: Int) {
        if (index == textIndex) return
        text?.let { avcodec.avcodec_free_context(it) }
        text = null
        textIndex = -1
        if (index < 0 || format.streams(index).codecpar().codec_type() != avutil.AVMEDIA_TYPE_SUBTITLE) return
        textIndex = index
        text = openDecoder(index)
    }

    /**
     * Goes to [positionMs]: from the key frame before it, the pictures and sound before it decoded but not handed
     * out, so the next picture is the one at that time.
     */
    fun seek(positionMs: Long) {
        avformat.av_seek_frame(format, -1, positionMs * 1000, avformat.AVSEEK_FLAG_BACKWARD)
        video?.let { avcodec.avcodec_flush_buffers(it) }
        audio?.let { avcodec.avcodec_flush_buffers(it) }
        pending.clear()
        ended = false
        skipBeforeMs = positionMs
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
                textIndex -> text?.let { cue(it, packet) }
            }
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

    private fun cue(context: AVCodecContext, input: AVPacket) {
        val decoded = avcodec.avcodec_decode_subtitle2(context, subtitle, gotSubtitle, input)
        if (decoded < 0 || gotSubtitle.get() == 0) return
        val stream = format.streams(textIndex)
        val base = stream.time_base()
        val pts = if (input.pts() != avutil.AV_NOPTS_VALUE) input.pts() else input.dts()
        val atMs = Math.round(pts * 1000.0 * base.num() / base.den())
        val startMs = atMs + subtitle.start_display_time()
        val endMs = if (subtitle.end_display_time() > subtitle.start_display_time()) {
            atMs + subtitle.end_display_time()
        } else {
            atMs + Math.round(input.duration() * 1000.0 * base.num() / base.den())
        }
        val lines = (0 until subtitle.num_rects()).mapNotNull { i ->
            subtitle.rects(i).ass()?.string?.let { com.hawkynt.mediadroid.player.SubtitleText.fromAssEvent(it) }
        }.filter { it.isNotEmpty() }
        if (lines.isNotEmpty() && endMs >= skipBeforeMs) pending.addLast(Cue(startMs, endMs, lines.joinToString("\n")))
        avcodec.avsubtitle_free(subtitle)
    }

    private fun picture(ptsMs: Long): Picture {
        val w = frame.width()
        val h = frame.height()
        if (sws == null || rgbaFrame.width() != w || rgbaFrame.height() != h) {
            sws?.let { swscale.sws_freeContext(it) }
            sws = swscale.sws_getContext(
                w, h, frame.format(), w, h, avutil.AV_PIX_FMT_RGBA, swscale.SWS_BILINEAR,
                null, null, null as DoublePointer?,
            )
            avutil.av_frame_unref(rgbaFrame)
            rgbaFrame.width(w).height(h).format(avutil.AV_PIX_FMT_RGBA)
            avutil.av_frame_get_buffer(rgbaFrame, 1)
        }
        swscale.sws_scale(sws, frame.data(), frame.linesize(), 0, h, rgbaFrame.data(), rgbaFrame.linesize())
        val out = ByteArray(w * h * 4)
        val stride = rgbaFrame.linesize(0)
        val data: BytePointer = rgbaFrame.data(0)
        for (y in 0 until h) data.position((y * stride).toLong()).get(out, y * w * 4, w * 4)
        data.position(0)
        return Picture(ptsMs, w, h, out)
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
        text?.let { avcodec.avcodec_free_context(it) }
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

        init {
            // FFmpeg's notes about estimated rates and durations are for its own command line, not a player's log.
            avutil.av_log_set_level(avutil.AV_LOG_ERROR)
        }
    }
}
