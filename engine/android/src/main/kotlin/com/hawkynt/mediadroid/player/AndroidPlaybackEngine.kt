package com.hawkynt.mediadroid.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.hawkynt.mediadroid.engine.MediaRequest
import com.hawkynt.mediadroid.engine.PlaybackEngine
import com.hawkynt.mediadroid.engine.PlaybackReport
import com.hawkynt.mediadroid.engine.PlaybackState
import com.hawkynt.mediadroid.engine.PlaybackTuning
import com.hawkynt.mediadroid.engine.Track
import com.hawkynt.mediadroid.engine.TrackFormat
import com.hawkynt.mediadroid.engine.VideoFormat
import com.hawkynt.mediadroid.engine.VideoOutput
import com.hawkynt.mediadroid.library.FsFile

/**
 * The Android playback engine: Media3's ExoPlayer, built as the player has always built it — the bundled FFmpeg
 * preferred for audio and the fallback for video (first with software decoding), FFmpeg's video renderer keeping as
 * many pictures ahead as [options] ask, loudness, night mode and even loudness on the decoded sound, the sound waiting
 * for a filtered picture, sidecar audio merged in, network sources streamed, ASS/SSA subtitles drawn by libass where
 * the native library carries it ([assSubtitles]) — behind [PlaybackEngine]. Its picture outputs are a [SurfaceOutput]
 * and a [SurfaceViewOutput]. What it needs of the app comes in: the household's [options], the [files] behind a
 * network source's location and how late the picture stage shows a picture ([pictureLatencyMs]).
 *
 * Beyond the shared API it offers what only Android has: the FFmpeg decoder's pictures handed to the picture stage as
 * planes ([planesTo]), each picture's release to the screen ([onFrameReleased]), spatial audio ([spatialAudio]), a
 * reload after an error ([reload]) and, for the info sheet and the controls ([PlayerPanel], [PlaybackQueue]), Media3
 * itself ([exo]) — the screens reach Media3 through these, never directly.
 */
class AndroidPlaybackEngine(
    context: Context,
    private val options: Options,
    override val tuning: PlaybackTuning,
    /** The file behind a location: a device path's or a network source's (SourceAccess), null where none is. */
    private val files: (String) -> FsFile?,
    /**
     * How long the picture stage holds a picture back at the video's [frame rate][Float] (-1 while unknown), in
     * milliseconds: the sound waits as long. Asked on the audio thread, as often as it plays.
     */
    private val pictureLatencyMs: (Float) -> Int = { 0 },
    /**
     * The demo library's placeholder videos (README screenshots): what is opened is set and its start shown on the
     * controls, never loaded — a placeholder is no video to play.
     */
    private val placeholders: Boolean = false,
) : PlaybackEngine {

    /**
     * The household's choices the engine is built with (Settings): FFmpeg's video decoder before the device's
     * ([softwareVideoDecoding]), compressed surround to an AV receiver ([audioPassthrough]), and how many decoded
     * pictures are kept ahead ([bufferUnit], a DecodedBuffer.Unit name, and [bufferValue]).
     */
    data class Options(
        val softwareVideoDecoding: Boolean = false,
        val audioPassthrough: Boolean = false,
        val bufferUnit: String = DecodedBuffer.Unit.SECONDS.name,
        val bufferValue: Float = 5f,
    )

    /** The video's frame rate, for the sound's wait behind a filtered picture; kept current by the player screen. */
    val videoFrameRate = floatArrayOf(-1f)

    /** ASS/SSA subtitles as libass draws them: the renderer feeds them, an [AssOverlayView] puts them up. */
    val assSubtitles = AssSubtitles({ attachedFonts(openedPath) }, { location -> wholeFile(location) })

    /** The subtitle track shown second, at the top: its renderers, its track selection, its cues ([secondSubtitle]). */
    val second = SecondSubtitles(androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context))

    /** ASS shown second, drawn by libass at the top by an overlay of its own. */
    val secondAss = AssSubtitles({ attachedFonts(openedPath) }, { location -> wholeFile(location) }, top = true)

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
            val file = files(path) ?: return emptyList()
            MatroskaFonts.read(file.length()) { at, count -> file.open(at).use { readUpTo(it, count) } }
        }
        fontsRead = path to fonts
        return fonts
    }

    /** A subtitle file beside the video, whole: on the device or its network source; null where it cannot be read. */
    private fun wholeFile(location: String): ByteArray? = if (location.startsWith("/")) {
        java.io.File(location).readBytes()
    } else {
        files(location)?.let { file ->
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
            .setTrackSelector(second.selector)
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
                        // The second set, after the first: given only the track picked to show second.
                        if (AssSubtitles.available) out += secondAss.renderer().also(second::addRenderer)
                        val secondText = androidx.media3.exoplayer.text.TextRenderer(second.output, outputLooper)
                        second.addRenderer(secondText)
                        out += DelayedTextRenderer(secondText) { tuning.subtitleDelayMs }
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
                            if (options.softwareVideoDecoding) {
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
                                    DecodedBuffer.Unit.valueOf(options.bufferUnit)
                                }.getOrDefault(DecodedBuffer.Unit.SECONDS),
                                options.bufferValue,
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
                        if (options.audioPassthrough) EXTENSION_RENDERER_MODE_ON else extensionRendererMode,
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
                        // video's own frame rate ([pictureLatencyMs]).
                        { tuning.audioDelayMs + pictureLatencyMs(videoFrameRate[0]) },
                    )
                }
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER),
            )
            .setMediaSourceFactory(
                // Videos on a network source stream through it; local ones as before. Audio files beside
                // the video are merged in (see SidecarAudio).
                SourceDataSource.Factory(context) {
                    files(it)
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

    private val listeners = mutableMapOf<PlaybackEngine.Listener, Pair<Player.Listener, AnalyticsListener>>()

    /** The file and audio track playing and its ReplayGain, for the even-loudness correction on the audio thread. */
    @Volatile private var fileLoudness: FileLoudness? = null

    /** The sound output as the audio track was set up (for the info sheet); null before. */
    var audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig? = null
        private set

    /** What was opened last, for the report's files. */
    private var request: MediaRequest? = null

    /** The decoders at work, by their own names, for the report: FFmpeg's say what they decode to. */
    @Volatile private var videoDecoder: String? = null
    @Volatile private var audioDecoder: String? = null

    /** The ReplayGain of the file and audio track playing, if its tags carry one. */
    val replayGainDb: Float? get() = fileLoudness?.tagGainDb

    init {
        exo.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onAudioTrackInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig,
                ) {
                    this@AndroidPlaybackEngine.audioTrackConfig = audioTrackConfig
                }

                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    videoDecoder = decoderName
                }

                override fun onAudioDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    audioDecoder = decoderName
                }
            },
        )
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

    /** A surface the pictures are drawn into: the picture stage's input, say. */
    class SurfaceOutput(val surface: android.view.Surface) : VideoOutput

    /** A view whose surface the pictures are drawn into, the player making its own over the view's as it needs. */
    class SurfaceViewOutput(val view: android.view.SurfaceView) : VideoOutput

    override var videoOutput: VideoOutput? = null
        set(value) {
            when (value) {
                null -> exo.clearVideoSurface()
                is SurfaceOutput -> exo.setVideoSurface(value.surface)
                is SurfaceViewOutput -> exo.setVideoSurfaceView(value.view)
                else -> throw IllegalArgumentException("Media3 draws into a SurfaceOutput or a SurfaceViewOutput")
            }
            field = value
        }

    override val state: PlaybackState get() = stateOf(exo.playbackState)

    override fun open(request: MediaRequest, autoPlay: Boolean) {
        openedPath = request.path
        this.request = request
        secondSubtitle = null
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
        // Prepared at once, playing or not: a paused video stands ready at its first picture.
        if (!placeholders) exo.prepare()
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

    override val videoFormat: VideoFormat?
        get() {
            val size = exo.videoSize
            if (size.width <= 0 || size.height <= 0) return null
            val format = exo.videoFormat
            return VideoFormat(
                size.width,
                size.height,
                size.pixelWidthHeightRatio,
                format?.frameRate?.takeIf { it > 0f } ?: 0f,
                when (format?.colorInfo?.colorTransfer) {
                    C.COLOR_TRANSFER_ST2084 -> VideoFormat.Transfer.PQ
                    C.COLOR_TRANSFER_HLG -> VideoFormat.Transfer.HLG
                    else -> VideoFormat.Transfer.SDR
                },
                format?.colorInfo?.lumaBitdepth?.takeIf { it > 0 } ?: 8,
            )
        }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override val report: PlaybackReport?
        get() {
            val request = request ?: return null
            val videoFormat = exo.videoFormat
            val audioFormat = exo.audioFormat
            if (videoFormat == null && audioFormat == null) return null
            val subtitleFiles = request.subtitles.size
            val audioFiles = request.audio.size
            val playing = { type: Int -> exo.currentTracks.groups.firstOrNull { it.type == type && it.isSelected } }
            val inputOf = { type: Int -> inputOf(playing(type)?.mediaTrackGroup?.id, subtitleFiles, audioFiles) }
            val textGroup = playing(C.TRACK_TYPE_TEXT)
            val textFormat = textGroup?.let { group ->
                (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat)
            }
            val size = exo.videoSize
            val audioTrack = audioTrackConfig
            val encoding = audioTrack?.encoding
            val tags = exo.mediaMetadata
            val location = request.path
            return PlaybackReport(
                tags = PlaybackReport.FileTags(
                    title = tags.title?.toString(),
                    displayTitle = tags.displayTitle?.toString(),
                    subtitle = tags.subtitle?.toString(),
                    artist = tags.artist?.toString(),
                    album = tags.albumTitle?.toString(),
                    genre = tags.genre?.toString(),
                    year = tags.recordingYear ?: tags.releaseYear,
                    writer = tags.writer?.toString(),
                    description = tags.description?.toString(),
                ),
                // The file itself, then its subtitle files and its audio files, in the order they are merged in.
                inputs = (listOf(location) + request.subtitles.map { it.path } + request.audio).mapIndexed { i, file ->
                    PlaybackReport.Input(
                        file,
                        DemuxerLog.of(playableUri(file).toString())
                            ?: if (i in 1..subtitleFiles) "SubtitleExtractor" else null,
                    )
                },
                video = videoFormat?.let {
                    val profile = com.hawkynt.mediadroid.library.CodecProfile.of(it.codecs)
                    PlaybackReport.Video(
                        input = inputOf(C.TRACK_TYPE_VIDEO),
                        codecs = it.codecs,
                        width = size.width.takeIf { w -> w > 0 } ?: it.width,
                        height = size.height.takeIf { h -> h > 0 } ?: it.height,
                        pixelAspect = size.pixelWidthHeightRatio,
                        frameRate = it.frameRate.takeIf { rate -> rate > 0f } ?: 0f,
                        bitDepth = it.colorInfo?.lumaBitdepth?.takeIf { d -> d > 0 }
                            ?: if (profile?.contains("10") == true) 10 else 8,
                        colorSpace = when (it.colorInfo?.colorSpace) {
                            C.COLOR_SPACE_BT709 -> "BT.709"
                            C.COLOR_SPACE_BT601 -> "BT.601"
                            C.COLOR_SPACE_BT2020 -> "BT.2020"
                            else -> null
                        },
                        transfer = when (it.colorInfo?.colorTransfer) {
                            C.COLOR_TRANSFER_SDR, C.COLOR_TRANSFER_GAMMA_2_2 -> "SDR"
                            C.COLOR_TRANSFER_ST2084 -> "PQ (HDR10)"
                            C.COLOR_TRANSFER_HLG -> "HLG"
                            else -> null
                        },
                        range = when (it.colorInfo?.colorRange) {
                            C.COLOR_RANGE_LIMITED -> "limited range"
                            C.COLOR_RANGE_FULL -> "full range"
                            else -> null
                        },
                        decodedFormat = ffmpegFormat(videoDecoder, location, "video"),
                    )
                },
                audio = audioFormat?.let {
                    PlaybackReport.Audio(
                        input = inputOf(C.TRACK_TYPE_AUDIO),
                        sampleRate = it.sampleRate.coerceAtLeast(0),
                        channels = it.channelCount.coerceAtLeast(0),
                        sinkEncoding = encoding?.let(::encodingName),
                        sinkSampleRate = audioTrack?.sampleRate ?: 0,
                        sinkChannels = audioTrack?.let { t -> Integer.bitCount(t.channelConfig) } ?: 0,
                        passthrough = encoding != null &&
                            !androidx.media3.common.util.Util.isEncodingLinearPcm(encoding),
                        offload = audioTrack?.offload == true,
                        decodedFormat = ffmpegFormat(audioDecoder, location, "audio"),
                        pcmFormat = DelayedAudioSink.lastInput?.pcmEncoding
                            ?.takeIf { e -> e != androidx.media3.common.Format.NO_VALUE }
                            ?.let(::encodingName),
                    )
                },
                subtitles = textFormat?.let {
                    PlaybackReport.Subtitles(
                        input = inputOf(C.TRACK_TYPE_TEXT),
                        mimeType = subtitleMimeType(it),
                        language = it.language,
                        // SSA reaches Media3's renderers raw only where libass takes it (AssSubtitles).
                        libass = AssSubtitles.available && AssEvents.isAss(it.sampleMimeType, it.codecs),
                    )
                },
                player = "Media3 ExoPlayer ${androidx.media3.common.MediaLibraryInfo.VERSION}",
            )
        }

    /**
     * A film's sound: other players pause for it, it pauses for a call — spatialized by the device's spatializer only
     * when this is on.
     */
    var spatialAudio: Boolean = false
        set(value) {
            field = value
            exo.setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setSpatializationBehavior(
                        if (value) C.SPATIALIZATION_BEHAVIOR_AUTO else C.SPATIALIZATION_BEHAVIOR_NEVER,
                    )
                    .build(),
                true,
            )
        }

    /**
     * The bundled FFmpeg's pictures handed as YUV planes to [output] (the picture stage), sparing its decoder a
     * window's buffer queue; idle while a device decoder plays. Asked again on every routing: any new surface given
     * to the player points FFmpeg's renderer back at it.
     */
    fun planesTo(output: androidx.media3.exoplayer.video.VideoDecoderOutputBufferRenderer) {
        val renderer = ffmpegVideoRenderer[0] ?: return
        exo.createMessage(renderer)
            .setType(androidx.media3.exoplayer.Renderer.MSG_SET_VIDEO_OUTPUT)
            .setPayload(output)
            .send()
    }

    /** Told each picture's release to the screen, in System.nanoTime's time, while set; null for none. */
    var onFrameReleased: ((releaseTimeNs: Long) -> Unit)? = null
        set(value) {
            field?.let { exo.clearVideoFrameMetadataListener(frameListener) }
            field = value
            if (value != null) exo.setVideoFrameMetadataListener(frameListener)
        }

    private val frameListener = androidx.media3.exoplayer.video.VideoFrameMetadataListener { _, releaseTimeNs, _, _ ->
        onFrameReleased?.invoke(releaseTimeNs)
    }

    /** Whether what is open plays with sound files beside it merged in. */
    val sidecarAudio: Boolean
        get() = (exo.currentMediaItem?.localConfiguration?.tag as? SidecarAudio)?.locations?.isNotEmpty() == true

    /**
     * What is open loaded afresh and played on from where it was — after an error, with a fresh picture surface in
     * place, say; [withoutSidecarAudio] leaves the sound files beside it out (one that cannot go along with the video).
     */
    fun reload(withoutSidecarAudio: Boolean = false) {
        val item = exo.currentMediaItem ?: return
        val position = exo.currentPosition
        exo.setMediaItem(if (withoutSidecarAudio) item.buildUpon().setTag(null).build() else item, position)
        exo.prepare()
    }

    override var speed: Float
        get() = exo.playbackParameters.speed
        set(value) = exo.setPlaybackSpeed(value)

    override var volume: Float
        get() = exo.volume
        set(value) {
            exo.volume = value
        }

    override val tracks: List<Track> get() = marked(tracksOf(exo.currentTracks))

    // The second track's id ("group:index" of the tracks playing).
    private var secondId: String? = null

    override var secondSubtitle: String?
        get() = secondId
        set(value) {
            val pick = value?.let { id ->
                val (group, index) = id.split(':').map { it.toInt() }
                exo.currentTracks.groups.getOrNull(group)?.takeIf { it.type == C.TRACK_TYPE_TEXT }
                    ?.let { it.mediaTrackGroup to index }
            }
            secondId = value.takeIf { pick != null }
            second.wanted = pick
        }

    /** [tracks] with the one shown second marked. */
    private fun marked(tracks: List<Track>): List<Track> =
        tracks.map { if (it.kind == Track.Kind.TEXT && it.id == secondId) it.copy(second = true) else it }

    override fun select(kind: Track.Kind, id: String?) {
        // The one shown second, picked first, is first only.
        if (kind == Track.Kind.TEXT && id != null && id == secondId) secondSubtitle = null
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
                listener.onStateChanged(stateOf(state))
                if (state == Player.STATE_ENDED) listener.onEnded()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) listener.onSeeked(newPosition.positionMs)
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) =
                listener.onTracksChanged(marked(tracksOf(tracks)))

            override fun onVideoSizeChanged(size: VideoSize) =
                listener.onVideoSize(size.width, size.height, size.pixelWidthHeightRatio)

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) =
                listener.onError(error.errorCodeName)
        }
        // The first picture and the decoders, whatever output they reach: the stage's included, which Player.Listener
        // does not hear of.
        val analytics = object : AnalyticsListener {
            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) = listener.onFirstFrame()

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) = listener.onDecoder(Track.Kind.VIDEO, decoderName)

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) = listener.onDecoder(Track.Kind.AUDIO, decoderName)
        }
        listeners[listener] = bridge to analytics
        exo.addListener(bridge)
        exo.addAnalyticsListener(analytics)
    }

    override fun removeListener(listener: PlaybackEngine.Listener) {
        listeners.remove(listener)?.let { (bridge, analytics) ->
            exo.removeListener(bridge)
            exo.removeAnalyticsListener(analytics)
        }
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

        private fun stateOf(state: Int) = when (state) {
            Player.STATE_BUFFERING -> PlaybackState.BUFFERING
            Player.STATE_READY -> PlaybackState.READY
            Player.STATE_ENDED -> PlaybackState.ENDED
            else -> PlaybackState.IDLE
        }

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
                    Track(
                        "$g:$i", kind, format.language, format.label, group.isTrackSelected(i), source,
                        format = formatOf(format, kind),
                    )
                }
            }

        /** A Media3 track format as the engine's: a subtitle track's own format, never Media3's cues. */
        fun formatOf(format: androidx.media3.common.Format, kind: Track.Kind): TrackFormat = TrackFormat(
            mimeType = if (kind == Track.Kind.TEXT) subtitleMimeType(format) else format.sampleMimeType,
            codecs = format.codecs,
            width = format.width.coerceAtLeast(0),
            height = format.height.coerceAtLeast(0),
            frameRate = format.frameRate.coerceAtLeast(0f),
            channels = format.channelCount.coerceAtLeast(0),
            sampleRate = format.sampleRate.coerceAtLeast(0),
        )

        /**
         * A subtitle track's own format: subtitles Media3 parses while extracting are cues by then and a file beside
         * the video left to libass is unread by Media3 (text/x-unknown); codecs keeps their format in both.
         */
        private fun subtitleMimeType(format: androidx.media3.common.Format): String? =
            if (format.sampleMimeType == androidx.media3.common.MimeTypes.APPLICATION_MEDIA3_CUES ||
                format.sampleMimeType == AssEvents.UNKNOWN_MIME_TYPE
            ) {
                format.codecs
            } else {
                format.sampleMimeType
            }

        /**
         * FFmpeg's own pixel or sample format of the [kind] stream at [location], when FFmpeg both demuxed and
         * decodes it; other decoders do not say what they decode to.
         */
        private fun ffmpegFormat(decoder: String?, location: String, kind: String): String? {
            if (decoder == null || !(decoder.startsWith("ffmpeg", ignoreCase = true) || decoder.contains("Ffmpeg"))) {
                return null
            }
            return DemuxerLog.formatOf(playableUri(location).toString(), kind)
        }

        /**
         * The input a track group comes from, by the prefixes Media3 gives merged sources' group ids: with
         * [subtitles] subtitle files the video is `0:` and subtitle file k `k:`; with [audio] audio files merged
         * around that, the video is `0:0:`, subtitle file k `0:k:` and audio file j `j:`.
         */
        private fun inputOf(groupId: String?, subtitles: Int, audio: Int): Int {
            val prefixes = groupId?.split(':')?.dropLast(1)?.map { it.toIntOrNull() ?: return 0 } ?: return 0
            return when {
                audio > 0 -> {
                    val outer = prefixes.getOrNull(0) ?: return 0
                    if (outer > 0) subtitles + outer else if (subtitles > 0) prefixes.getOrNull(1) ?: 0 else 0
                }
                subtitles > 0 -> prefixes.getOrNull(0) ?: 0
                else -> 0
            }
        }

        /** A `C.ENCODING_*` as people call it. */
        private fun encodingName(encoding: Int): String = when (encoding) {
            C.ENCODING_PCM_8BIT -> "8-bit PCM"
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> "16-bit PCM"
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> "24-bit PCM"
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> "32-bit PCM"
            C.ENCODING_PCM_FLOAT -> "float PCM"
            C.ENCODING_AC3 -> "Dolby Digital"
            C.ENCODING_E_AC3 -> "Dolby Digital Plus"
            C.ENCODING_E_AC3_JOC -> "Dolby Digital Plus with Atmos"
            C.ENCODING_AC4 -> "Dolby AC-4"
            C.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"
            C.ENCODING_DTS -> "DTS"
            C.ENCODING_DTS_HD -> "DTS-HD"
            C.ENCODING_MP3 -> "MP3"
            C.ENCODING_AAC_LC -> "AAC"
            C.ENCODING_OPUS -> "Opus"
            else -> "encoding $encoding"
        }
    }
}
