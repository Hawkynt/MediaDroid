package com.hawkynt.mediadroid.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * ASS/SSA subtitles drawn as authored by libass (the native library's, [LibAss]) instead of Media3's SSA parser, which
 * draws a reduced subset: Media3 is told to leave SSA unparsed ([parsers]), so the samples reach [renderer] as the file
 * holds them — a Matroska stream's header and events — and it feeds them to the [track] the screen draws
 * ([AssOverlayView]). A file beside the video Media3 leaves unread; it is read whole through [file], by its location
 * (the track's id). The file's attached fonts come from [fonts] (MatroskaFonts); both are read off the player's
 * thread. Chinese, Japanese or Korean text no font of the file's draws falls back to the device's own face for its
 * script ([ScriptFallback]), loaded once the first such line arrives. Where the native library is missing,
 * [available] is false and Media3 draws SSA as before. A track shown second ([top]) has its lines at the top.
 */
@OptIn(UnstableApi::class)
class AssSubtitles(
    private val fonts: () -> List<Pair<String, ByteArray>>,
    private val file: (String) -> ByteArray?,
    private val top: Boolean = false,
) {

    /** The track being drawn: the picked ASS track while the renderer is on, else null. */
    @Volatile
    var track: AssTrack? = null
        private set

    /** Media3's subtitle parsers, SSA left out where libass draws it. */
    val parsers: SubtitleParser.Factory = parsers(available)

    /** The renderer for SSA tracks, to stand before Media3's text renderers; only where [available]. */
    fun renderer(): BaseRenderer = AssRenderer()

    /**
     * A new track with [header] (a stream's), or the whole script at [script] (a file beside the video), the default
     * font and the file's fonts as they are read.
     */
    private fun start(header: ByteArray?, script: String?) {
        stop()
        val made = AssTrack()
        if (!made.open) return
        defaultFont?.let { made.defaultFont(path = it) }
        if (top) made.linePosition(TOP)
        val fallback = ScriptFallback.Track(Locale.getDefault().toLanguageTag())
        track = made
        scripts = fallback
        header?.let {
            made.header(it)
            notice(made, fallback, it)
        }
        Thread({
            script?.let { location ->
                runCatching { file(location) }.getOrNull()?.let {
                    made.script(it)
                    notice(made, fallback, it)
                }
            }
            runCatching { fonts() }.getOrDefault(emptyList()).forEach { (name, data) ->
                if (track === made) made.addFont(name, data)
            }
        }, "mediadroid-ass-fonts").apply {
            isDaemon = true
            start()
        }
    }

    private fun stop() {
        track?.close()
        track = null
        scripts = null
    }

    /** The scripts seen in the track being drawn, for its fallback face. */
    @Volatile
    private var scripts: ScriptFallback.Track? = null

    /** [text] fed to [made]: where it calls for another fallback face than before, that face is loaded. */
    private fun notice(made: AssTrack, fallback: ScriptFallback.Track, text: ByteArray) {
        val language = synchronized(fallback) { fallback.see(String(text, Charsets.UTF_8)) } ?: return
        fallbackLoader.execute {
            val face = systemFace(language) ?: return@execute
            if (track === made) made.fallbackFont(face.first, face.second)
        }
    }

    /** Media3's samples of an SSA track into [track]: the stream's events with their times, or a whole file. */
    private inner class AssRenderer : BaseRenderer(C.TRACK_TYPE_TEXT) {
        private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        private var ended = false

        override fun getName() = "AssRenderer"

        override fun supportsFormat(format: Format): Int = RendererCapabilities.create(
            if (AssEvents.isAss(format.sampleMimeType, format.codecs)) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE,
        )

        override fun onStreamChanged(
            formats: Array<out Format>,
            startPositionUs: Long,
            offsetUs: Long,
            mediaPeriodId: MediaSource.MediaPeriodId,
        ) {
            ended = false
            val format = formats.firstOrNull()
            val sidecar = format?.sampleMimeType == AssEvents.UNKNOWN_MIME_TYPE
            start(AssEvents.header(format?.initializationData.orEmpty()), format?.id?.takeIf { sidecar })
        }

        override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
            // libass keeps every event it was given; those read again are not doubled.
            ended = false
        }

        override fun onDisabled() = stop()

        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            while (!ended) {
                buffer.clear()
                when (readSource(formatHolder, buffer, 0)) {
                    C.RESULT_BUFFER_READ -> if (buffer.isEndOfStream) ended = true else feed()
                    C.RESULT_FORMAT_READ -> Unit
                    else -> return
                }
            }
        }

        private fun feed() {
            val data = buffer.data ?: return
            data.flip()
            val sample = ByteArray(data.remaining()).also(data::get)
            val current = track ?: return
            val fallback = scripts
            if (AssEvents.isScript(sample)) {
                current.script(sample)
                fallback?.let { notice(current, it, sample) }
                return
            }
            val event = AssEvents.fromMatroskaSample(sample) ?: return
            val startMs = (buffer.timeUs - streamOffsetUs) / 1000
            current.event(event.data, startMs, event.durationMs)
            fallback?.let { notice(current, it, event.data) }
        }

        override fun isReady() = true

        override fun isEnded() = ended
    }

    companion object {
        /** Media3's own subtitle parsers, without SSA where [libass] draws it: SSA then reaches the renderer raw. */
        fun parsers(libass: Boolean): SubtitleParser.Factory = if (!libass) DefaultSubtitleParserFactory() else {
            object : SubtitleParser.Factory {
                private val parsers = DefaultSubtitleParserFactory()

                override fun supportsFormat(format: Format) =
                    !AssEvents.isAss(format.sampleMimeType) && parsers.supportsFormat(format)

                override fun getCueReplacementBehavior(format: Format) = parsers.getCueReplacementBehavior(format)

                override fun create(format: Format) = parsers.create(format)
            }
        }

        /** Whether libass is there: the app's native library loaded, carrying it. */
        val available: Boolean by lazy {
            runCatching { System.loadLibrary("mediadroid_neural") }.isSuccess && LibAss.available
        }

        /**
         * The device's own sans face, for what the file's fonts do not draw: Roboto on every Android since 5, Noto or
         * Droid on older or unusual builds. Android has no font provider libass can ask.
         */
        val defaultFont: String? by lazy {
            listOf(
                "/system/fonts/Roboto-Regular.ttf",
                "/system/fonts/NotoSans-Regular.ttf",
                "/system/fonts/DroidSans.ttf",
            ).firstOrNull { File(it).isFile }
        }

        /** libass's line position for a track shown second: its lines at the top. */
        private const val TOP = 100.0

        /** The one thread the fallback faces load on: a system's CJK font is large. */
        private val fallbackLoader = Executors.newSingleThreadExecutor {
            Thread(it, "mediadroid-ass-fallback").apply { isDaemon = true }
        }

        /** The folders a device's fonts lie in, the system's first. */
        private val fontFolders = listOf("/system/fonts", "/product/fonts")

        /**
         * The device's fallback families as its font configuration lists them: Android 15's font_fallback.xml, else
         * fonts.xml.
         */
        private val systemFamilies: List<ScriptFallback.Family> by lazy {
            listOf("/system/etc/font_fallback.xml", "/system/etc/fonts.xml").asSequence()
                .mapNotNull { runCatching { File(it).readText() }.getOrNull() }
                .map(ScriptFallback::families)
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
        }

        private fun fontFile(name: String) = fontFolders.map { File(it, name) }.firstOrNull { it.isFile }

        /** The device's face for [language] (ScriptFallback's tags): its file's path and its index, or null. */
        private fun systemFace(language: String): Pair<String, Int>? {
            val face = ScriptFallback.face(language, systemFamilies) { fontFile(it) != null } ?: return null
            return fontFile(face.file)?.let { it.path to face.index }
        }
    }
}
