package com.hawkynt.mediadroid.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.hawkynt.mediadroid.data.Settings
import com.hawkynt.mediadroid.engine.MediaRequest
import com.hawkynt.mediadroid.engine.PlaybackEngine
import com.hawkynt.mediadroid.engine.PlaybackTuning
import com.hawkynt.mediadroid.engine.Track

/**
 * The Android playback engine: Media3's ExoPlayer, built as the player has always built it — the bundled FFmpeg
 * preferred for audio and the fallback for video (first with software decoding), FFmpeg's video renderer keeping as
 * many pictures ahead as Settings asks, loudness, night mode and even loudness on the decoded sound, the sound waiting
 * for a filtered picture, sidecar audio merged in, network sources streamed, ASS/SSA subtitles drawn by libass where
 * the native library carries it ([assSubtitles]) — behind [PlaybackEngine].
 *
 * [exo] stays reachable while the player screen moves over to the engine's own commands (#283).
 */
class AndroidPlaybackEngine(
    context: Context,
    private val settings: Settings,
    override val tuning: PlaybackTuning,
) : PlaybackEngine {

    /** The video's frame rate, for the sound's wait behind a filtered picture; kept current by the player screen. */
    val videoFrameRate = floatArrayOf(-1f)

    private val appContext = context.applicationContext

    /** ASS/SSA subtitles as libass draws them: the renderer feeds them, an [AssOverlayView] puts them up. */
    val assSubtitles = AssSubtitles({ attachedFonts(openedPath) }, { location -> wholeFile(location) })

    // The fonts of the file read last, kept: switching subtitles on and off reads them once.
    @Volatile private var fontsRead: Pair<String, List<Pair<String, ByteArray>>>? = null

    /** The fonts the file at [path] carries (MatroskaFonts): read from the device or through its network source. */
    private fun attachedFonts(path: String?): List<Pair<String, ByteArray>> {
        if (path == null) return emptyList()
        fontsRead?.takeIf { it.first == path }?.let { return it.second }
        val fonts = if (path.startsWith("/")) {
            java.io.RandomAccessFile(path, "r").use { file ->
                MatroskaFonts.read(file.length()) { at, count ->
                    ByteArray(count).also {
                        file.seek(at)
                        file.readFully(it)
                    }
                }
            }
        } else {
            val file = com.hawkynt.mediadroid.data.SourceAccess.resolve(appContext, path) ?: return emptyList()
            MatroskaFonts.read(file.length()) { at, count -> file.open(at).use { readUpTo(it, count) } }
        }
        fontsRead = path to fonts
        return fonts
    }

    /** A subtitle file beside the video, whole: on the device or its network source; null where it cannot be read. */
    private fun wholeFile(location: String): ByteArray? = if (location.startsWith("/")) {
        java.io.File(location).readBytes()
    } else {
        com.hawkynt.mediadroid.data.SourceAccess.resolve(appContext, location)?.let { file ->
            file.open(0).use { readUpTo(it, file.length().coerceAtMost(MAX_SUBTITLE_FILE).toInt()) }
        }
    }

    /** The bundled FFmpeg's video renderer, which hands its pictures to the filters' stage as YUV planes. */
    val ffmpegVideoRenderer = arrayOfNulls<androidx.media3.exoplayer.Renderer>(1)

    val exo: ExoPlayer =
        ExoPlayer.Builder(context)
            // Two minutes ahead where memory allows, and a longer start after a stall: a 4K file over
            // Wi-Fi rides out the dips. The bytes held stay within a quarter of the app's memory (see LoadBudget):
            // Media3's own 131 MB ran a 256 MB heap out with a raw 4K file.
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(BUFFER_MIN_MS, BUFFER_MAX_MS, BUFFER_START_MS, BUFFER_RESTART_MS)
                    .setTargetBufferBytes(
                        LoadBudget.targetBytes(Runtime.getRuntime().maxMemory()).toInt(),
                    )
                    .build(),
            )
            .setRenderersFactory(
                object : DefaultRenderersFactory(context) {
                    override fun buildTextRenderers(
                        context: android.content.Context,
                        output: androidx.media3.exoplayer.text.TextOutput,
                        outputLooper: android.os.Looper,
                        extensionRendererMode: Int,
                        out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
                    ) {
                        val built = java.util.ArrayList<androidx.media3.exoplayer.Renderer>()
                        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, built)
                        // libass first: the track selector gives a track to the first renderer that handles it, so
                        // SSA goes to libass and everything else to Media3's.
                        if (AssSubtitles.available) out += assSubtitles.renderer()
                        built.mapTo(out) {
                            DelayedTextRenderer(it) { tuning.subtitleDelayMs }
                        }
                    }

                    override fun buildVideoRenderers(
                        context: android.content.Context,
                        extensionRendererMode: Int,
                        mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                        enableDecoderFallback: Boolean,
                        eventHandler: android.os.Handler,
                        eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                        allowedVideoJoiningTimeMs: Long,
                        out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
                    ) {
                        super.buildVideoRenderers(
                            context,
                            if (settings.softwareVideoDecoding) {
                                EXTENSION_RENDERER_MODE_PREFER
                            } else {
                                EXTENSION_RENDERER_MODE_ON
                            },
                            mediaCodecSelector,
                            enableDecoderFallback,
                            eventHandler,
                            PixelShapeListener(eventListener),
                            allowedVideoJoiningTimeMs,
                            out,
                        )
                        // FFmpeg's renderer again, keeping as many decoded pictures ahead as the household set
                        // (Settings › Pictures kept ahead), instead of Media3's four.
                        val at = out.indexOfFirst { it.name.startsWith("ExperimentalFfmpegVideo") }
                        if (at >= 0) {
                            val memory = android.app.ActivityManager.MemoryInfo()
                            context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(memory)
                            val buffer = DecodedBuffer(
                                runCatching {
                                    DecodedBuffer.Unit.valueOf(settings.bufferUnit)
                                }.getOrDefault(DecodedBuffer.Unit.SECONDS),
                                settings.bufferValue,
                            )
                            val frames = buffer.decoderFrames(
                                DecodedBuffer.decoderCeilingBytes(
                                    memory.totalMem,
                                    Runtime.getRuntime().maxMemory(),
                                ),
                            )
                            out[at] = androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer(
                                allowedVideoJoiningTimeMs,
                                eventHandler,
                                PixelShapeListener(eventListener),
                                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
                                Runtime.getRuntime().availableProcessors(),
                                FFMPEG_INPUT_BUFFERS,
                                frames,
                            )
                        }
                        ffmpegVideoRenderer[0] = out.getOrNull(at)
                    }

                    // Real loudness fix for unnormalized streams: the settings dB
                    // is applied to the decoded PCM and re-read per buffer, so the
                    // slider takes effect mid-playback without rebuilding anything.
                    // Passthrough for an AV receiver only happens on the device's own audio path, so with it
                    // switched on the bundled FFmpeg decoder becomes the fallback instead of the first choice.
                    override fun buildAudioRenderers(
                        context: android.content.Context,
                        extensionRendererMode: Int,
                        mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                        enableDecoderFallback: Boolean,
                        audioSink: androidx.media3.exoplayer.audio.AudioSink,
                        eventHandler: android.os.Handler,
                        eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
                        out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
                    ) = super.buildAudioRenderers(
                        context,
                        if (settings.audioPassthrough) EXTENSION_RENDERER_MODE_ON else extensionRendererMode,
                        mediaCodecSelector,
                        enableDecoderFallback,
                        audioSink,
                        eventHandler,
                        eventListener,
                        out,
                    )

                    override fun buildAudioSink(
                        context: android.content.Context,
                        enableFloatOutput: Boolean,
                        enableAudioOutputPlaybackParams: Boolean,
                    ): androidx.media3.exoplayer.audio.AudioSink = DelayedAudioSink(
                        DefaultAudioSink.Builder(context)
                            .setEnableFloatOutput(enableFloatOutput)
                            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                            .setAudioProcessorChain(
                                DefaultAudioSink.DefaultAudioProcessorChain(
                                    LoudnessAudioProcessor(
                                        { tuning.loudnessDb },
                                        { tuning.nightMode },
                                        { tuning.normalizeLoudness },
                                        { fileLoudness },
                                    ),
                                ),
                            )
                            .build(),
                        // A filtered picture can run a few frames behind; the sound waits as long, counted by the
                        // video's own frame rate (see VideoFilterStage.latencyFrames).
                        {
                            tuning.audioDelayMs + PlaybackMath.pictureLatencyMs(
                                VideoFilterStage.latencyFrames,
                                videoFrameRate[0],
                                VideoFilterStage.latencyMs,
                            )
                        },
                    )
                }
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER),
            )
            .setMediaSourceFactory(
                // Videos on a network source stream through it; local ones as before. Audio files beside
                // the video are merged in (see SidecarAudio).
                SourceDataSource.Factory(context) {
                    com.hawkynt.mediadroid.data.SourceAccess.resolve(context, it)
                }.let { files ->
                    SidecarAudioSourceFactory(
                        // SSA left unparsed where libass draws it (AssSubtitles.parsers), in the file and beside it.
                        androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                            files,
                            AviAudioFraming.extractors(assSubtitles.parsers),
                        ).setSubtitleParserFactory(assSubtitles.parsers),
                        files,
                    )
                },
            )
            .build()

    private val listeners = mutableMapOf<PlaybackEngine.Listener, Player.Listener>()

    /** The file and audio track playing and its ReplayGain, for the even-loudness correction on the audio thread. */
    @Volatile private var fileLoudness: FileLoudness? = null

    /** The ReplayGain of the file and audio track playing, if its tags carry one. */
    val replayGainDb: Float? get() = fileLoudness?.tagGainDb

    init {
        // The audio track playing and its ReplayGain tags, as Media3 read them from the file.
        exo.addListener(
            object : Player.Listener {
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    val path = openedPath ?: return
                    val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                    val index = group?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) } }
                    val format = if (group != null && index != null) group.getTrackFormat(index) else null
                    val tags = ReplayGainTags.of(format?.metadata)
                    val loudness = FileLoudness(
                        "$path#${tracks.groups.indexOf(group)}:$index",
                        ReplayGain.parse(tags)?.gainDb,
                    )
                    if (loudness != fileLoudness) fileLoudness = loudness
                }
            },
        )
    }

    override var openedPath: String? = null
        private set

    override fun open(request: MediaRequest, autoPlay: Boolean) {
        openedPath = request.path
        fileLoudness = FileLoudness(request.path, null)
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(request.audioLanguage.ifEmpty { null })
            .build()
        exo.setMediaItem(
            MediaItem.Builder()
                .setUri(playableUri(request.path))
                .setSubtitleConfigurations(
                    request.subtitles.map { file ->
                        MediaItem.SubtitleConfiguration.Builder(playableUri(file.path))
                            // Its location as the track's id: libass reads an ASS file itself (AssSubtitles).
                            .setId(file.path)
                            .setMimeType(file.mimeType)
                            .setLanguage(file.language)
                            .setLabel(file.label)
                            .setSelectionFlags(if (file.forced) C.SELECTION_FLAG_FORCED else 0)
                            .build()
                    },
                )
                .setTag(SidecarAudio(request.audio))
                .build(),
        )
        if (request.startMs > 0) exo.seekTo(request.startMs)
        if (autoPlay) play()
    }

    /** Starts or resumes; what was just opened is prepared first. */
    override fun play() {
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        exo.playWhenReady = true
    }

    override fun pause() = exo.pause()

    override fun seekTo(positionMs: Long) = exo.seekTo(positionMs)

    override val positionMs: Long get() = exo.currentPosition

    override val durationMs: Long get() = exo.duration.coerceAtLeast(0)

    override val isPlaying: Boolean get() = exo.isPlaying

    override val frameRate: Float get() = exo.videoFormat?.frameRate?.takeIf { it > 0f } ?: 0f

    override var speed: Float
        get() = exo.playbackParameters.speed
        set(value) = exo.setPlaybackSpeed(value)

    override var volume: Float
        get() = exo.volume
        set(value) {
            exo.volume = value
        }

    override val tracks: List<Track> get() = tracksOf(exo.currentTracks)

    override fun select(kind: Track.Kind, id: String?) {
        val type = typeOf(kind)
        val builder = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
        if (id != null) {
            val (group, index) = id.split(':').map { it.toInt() }
            builder.setOverrideForType(TrackSelectionOverride(exo.currentTracks.groups[group].mediaTrackGroup, index))
        }
        exo.trackSelectionParameters = builder.build()
    }

    override var subtitlesOn: Boolean
        get() = C.TRACK_TYPE_TEXT !in exo.trackSelectionParameters.disabledTrackTypes
        set(value) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !value)
                .build()
        }

    override fun addListener(listener: PlaybackEngine.Listener) {
        val bridge = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onPlayingChanged(isPlaying)

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) listener.onEnded()
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) =
                listener.onTracksChanged(tracksOf(tracks))

            override fun onVideoSizeChanged(size: VideoSize) =
                listener.onVideoSize(size.width, size.height, size.pixelWidthHeightRatio)

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) =
                listener.onError(error.errorCodeName)
        }
        listeners[listener] = bridge
        exo.addListener(bridge)
    }

    override fun removeListener(listener: PlaybackEngine.Listener) {
        listeners.remove(listener)?.let { exo.removeListener(it) }
    }

    override fun close() = exo.release()

    companion object {
        /**
         * Compressed samples waiting for FFmpeg's video decoder, as Media3 keeps them: each grows to the largest
         * sample, in the app's heap — 12 MB for a raw 4K picture.
         */
        private const val FFMPEG_INPUT_BUFFERS = 4

        /** The most of a subtitle file read: scripts with drawn signs run to a few megabytes, never near this. */
        private const val MAX_SUBTITLE_FILE = 64L shl 20

        /** Up to [count] bytes from [input], fewer where it ends first. */
        private fun readUpTo(input: java.io.InputStream, count: Int): ByteArray {
            val bytes = ByteArray(count)
            var got = 0
            while (got < count) {
                val n = input.read(bytes, got, count - got)
                if (n < 0) break
                got += n
            }
            return bytes.copyOf(got)
        }

        /** At least half a minute of video buffered, two minutes at most, as memory allows. */
        private const val BUFFER_MIN_MS = 30_000
        private const val BUFFER_MAX_MS = 120_000

        /** Playback starts with 2.5 s buffered, and again after a stall with 5 s. */
        private const val BUFFER_START_MS = 2_500
        private const val BUFFER_RESTART_MS = 5_000

        private fun typeOf(kind: Track.Kind) = when (kind) {
            Track.Kind.AUDIO -> C.TRACK_TYPE_AUDIO
            Track.Kind.TEXT -> C.TRACK_TYPE_TEXT
            Track.Kind.VIDEO -> C.TRACK_TYPE_VIDEO
        }

        /** Media3's tracks as the engine's: id "group:index", the group's type as the kind. */
        fun tracksOf(tracks: androidx.media3.common.Tracks): List<Track> =
            tracks.groups.flatMapIndexed { g, group ->
                val kind = when (group.type) {
                    C.TRACK_TYPE_AUDIO -> Track.Kind.AUDIO
                    C.TRACK_TYPE_TEXT -> Track.Kind.TEXT
                    C.TRACK_TYPE_VIDEO -> Track.Kind.VIDEO
                    else -> null
                } ?: return@flatMapIndexed emptyList()
                // Merged sources prefix their groups' ids with their index: 0 the video, n the n-th sidecar file.
                val source = group.mediaTrackGroup.id.substringBefore(':', "").toIntOrNull() ?: 0
                (0 until group.length).map { i ->
                    val format = group.getTrackFormat(i)
                    Track("$g:$i", kind, format.language, format.label, group.isTrackSelected(i), source)
                }
            }
    }
}
