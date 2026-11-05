package com.hawkynt.mediadroid.engine.desktop

import org.bytedeco.ffmpeg.avfilter.AVFilterContext
import org.bytedeco.ffmpeg.avfilter.AVFilterGraph
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avfilter
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.PointerPointer
import java.io.ByteArrayOutputStream

/**
 * Playback speed for the sound: 16-bit stereo PCM at [MediaReader.SAMPLE_RATE] played faster or slower at the same
 * pitch, by FFmpeg's `atempo` filter. At [speed] 1 the sound passes untouched. Not thread-safe: the reader's thread.
 */
class Tempo : AutoCloseable {

    private var graph: AVFilterGraph? = null
    private var source: AVFilterContext? = null
    private var sink: AVFilterContext? = null
    private val input: AVFrame = avutil.av_frame_alloc()
    private val output: AVFrame = avutil.av_frame_alloc()
    private var samplesIn = 0L

    /** How fast the sound plays: 2 twice as fast, 0.5 half. Changing it starts the filter afresh. */
    var speed: Float = 1f
        set(value) {
            if (value == field) return
            field = value
            reset()
        }

    /** The sound in [pcm] at [speed]; the filter holds a little back, handed out with later calls. */
    fun process(pcm: ByteArray): ByteArray {
        if (speed == 1f || pcm.isEmpty()) return pcm
        val src = source ?: build()
        val frames = pcm.size / 4
        avutil.av_frame_unref(input)
        avutil.av_channel_layout_default(input.ch_layout(), 2)
        input.sample_rate(MediaReader.SAMPLE_RATE).format(avutil.AV_SAMPLE_FMT_S16).nb_samples(frames)
        input.pts(samplesIn)
        samplesIn += frames
        check(avutil.av_frame_get_buffer(input, 0) >= 0) { "no memory for the sound" }
        input.data(0).put(pcm, 0, frames * 4)
        if (avfilter.av_buffersrc_add_frame(src, input) < 0) return ByteArray(0)
        val out = ByteArrayOutputStream()
        while (avfilter.av_buffersink_get_frame(sink, output) >= 0) {
            val bytes = ByteArray(output.nb_samples() * 4)
            output.data(0).get(bytes)
            out.write(bytes)
            avutil.av_frame_unref(output)
        }
        return out.toByteArray()
    }

    /** Forgets what the filter holds back: after a seek, the old sound must not come out. */
    fun reset() {
        graph?.let { avfilter.avfilter_graph_free(it) }
        graph = null
        source = null
        sink = null
        samplesIn = 0
    }

    // abuffer → atempo (one or more: each takes 0.5 to 100) → abuffersink, all 16-bit stereo.
    private fun build(): AVFilterContext {
        val g = checkNotNull(avfilter.avfilter_graph_alloc()) { "no filter graph" }
        graph = g
        fun create(filter: String, name: String, args: String?): AVFilterContext {
            val made = PointerPointer<AVFilterContext>(1)
            val result = avfilter.avfilter_graph_create_filter(
                made, avfilter.avfilter_get_by_name(filter), org.bytedeco.javacpp.BytePointer(name),
                args?.let { org.bytedeco.javacpp.BytePointer(it) }, null, g,
            )
            check(result >= 0) { "cannot make the $filter filter ($result)" }
            return AVFilterContext(made.get(0))
        }
        val rate = MediaReader.SAMPLE_RATE
        val src = create("abuffer", "in", "time_base=1/$rate:sample_rate=$rate:sample_fmt=s16:channel_layout=stereo")
        var last = src
        var left = speed.toDouble()
        var n = 0
        while (left < 0.5) {
            last = create("atempo", "tempo${n++}", tempoArgument(0.5)).also { avfilter.avfilter_link(last, 0, it, 0) }
            left /= 0.5
        }
        last = create("atempo", "tempo$n", tempoArgument(left)).also { avfilter.avfilter_link(last, 0, it, 0) }
        val format = create("aformat", "format", "sample_fmts=s16:channel_layouts=stereo:sample_rates=$rate")
        avfilter.avfilter_link(last, 0, format, 0)
        val out = create("abuffersink", "out", null)
        avfilter.avfilter_link(format, 0, out, 0)
        check(avfilter.avfilter_graph_config(g, null) >= 0) { "the tempo filter does not fit together" }
        source = src
        sink = out
        return src
    }

    override fun close() {
        reset()
        avutil.av_frame_free(input)
        avutil.av_frame_free(output)
    }

    companion object {
        /**
         * The `atempo` argument for [tempo], as a fraction of whole numbers: FFmpeg reads a decimal with the C library's
         * strtod, which follows the locale the JVM set from the environment, so `2.0` is no number where the decimal
         * separator is a comma. Its expression parser reads `2000000/1000000` the same everywhere.
         */
        fun tempoArgument(tempo: Double): String = "tempo=${Math.round(tempo * 1_000_000)}/1000000"
    }
}
