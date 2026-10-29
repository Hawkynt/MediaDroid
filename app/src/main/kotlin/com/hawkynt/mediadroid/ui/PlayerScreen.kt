package com.hawkynt.mediadroid.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.media.AudioManager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.media3.ui.AspectRatioFrameLayout
import com.hawkynt.mediadroid.player.PlaybackMath
import kotlin.math.roundToInt
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.ui.text.font.FontWeight
import com.hawkynt.mediadroid.player.PictureAdjustment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.ui.PlayerView
import com.hawkynt.mediadroid.R
import com.hawkynt.mediadroid.player.PlayerWindow
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.library.SkipRules
import com.hawkynt.mediadroid.player.LoudnessAudioProcessor
import java.io.File
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Info
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.onPreviewKeyEvent


/** How long the controls stay up after the last touch, like the streaming apps' players. */
private const val ControlsTimeoutMs = 3_000

/** How the video fills the screen; maps onto Media3's resize modes. */
enum class VideoFit(val label: String, val resizeMode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Fill", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
}

/**
 * Fit, zoom and picture correction on the video frame inside a player view only — the controls
 * and subtitles drawn above it are neither scaled nor tinted.
 */
private fun PlayerView.applyVideoLook(fit: VideoFit, zoom: Float, pan: Offset, picture: PictureAdjustment) {
    resizeMode = fit.resizeMode
    val frame = findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) ?: return
    frame.scaleX = zoom
    frame.scaleY = zoom
    frame.translationX = pan.x
    frame.translationY = pan.y
    frame.applyPicture(picture)
}

/**
 * Full-screen playback: it remembers where you stopped, a show keeps going, and
 * the household controls live right on the screen — speed from 0.2x to 5x,
 * subtitles on/off, and a picker for audio and subtitle tracks when the file
 * carries several. Picture and sound follow the guarded settings. Nothing but
 * the picture shows while watching: a tap brings every control up together,
 * and they fade out again after a few seconds untouched. On the bare picture a
 * double tap jumps 15 s, vertical swipes set brightness (left) and volume
 * (right), and two fingers zoom. With an external display attached the video
 * can go there while the phone keeps the controls.
 */
@Composable
fun PlayerScreen(
    queue: List<Playable>,
    startIndex: Int,
    store: StateStore,
    autoAdvance: Boolean,
    onExit: () -> Unit,
    demo: Boolean = false,
) {
    val context = LocalContext.current
    val settings = store.data.settings
    // PREFER lets the bundled FFmpeg decoder win over the device's own audio
    // decoders, so AC3/DTS/TrueHD tracks play on hardware that lacks them. Video
    // stays on the device's (hardware) decoders with FFmpeg only as the fallback,
    // unless the household chose software decoding in Settings.
    // Past the device's own maximum a volume swipe adds loudness gain and a brightness swipe adds gamma.
    var loudnessBoostDb by remember { mutableFloatStateOf(0f) }
    // Rotation for this session: following the sensor, or held in landscape or portrait.
    var rotation by remember { mutableStateOf(Rotation.AUTO) }
    val rotationActivity = remember(context) { context.findActivity() }
    DisposableEffect(rotation) {
        rotationActivity?.requestedOrientation = rotation.orientation
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            rotationActivity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    var subtitleLook by remember { mutableStateOf(store.subtitleLookFor(queue[startIndex].videoPath)) }
    var nightMode by remember { mutableStateOf(false) }

    // Lip-sync, remembered per audio output: a Bluetooth headset lags differently from the speaker.
    var audioOutput by remember { mutableStateOf(com.hawkynt.mediadroid.player.currentAudioOutput(context)) }
    var audioDelayMs by remember { mutableIntStateOf(store.audioDelayFor(audioOutput.key)) }
    DisposableEffect(Unit) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val callback = object : android.media.AudioDeviceCallback() {
            fun refresh() {
                audioOutput = com.hawkynt.mediadroid.player.currentAudioOutput(context)
                audioDelayMs = store.audioDelayFor(audioOutput.key)
            }
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) = refresh()
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }

    var useCutout by remember { mutableStateOf(settings.useCutout) }

    var spatialAudio by remember { mutableStateOf(settings.spatialAudio) }
    val spatializerAvailable = remember {
        android.os.Build.VERSION.SDK_INT >= 33 &&
            context.getSystemService(AudioManager::class.java).spatializer.immersiveAudioLevel !=
            android.media.Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE
    }
    var gammaBoost by remember { mutableFloatStateOf(1f) }
    val player = remember {
        ExoPlayer.Builder(context)
            // Two minutes ahead where memory allows, and a longer start after a stall: a 4K file over
            // Wi-Fi rides out the dips. The byte limit stays Media3's own, which fits any heap.
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(BUFFER_MIN_MS, BUFFER_MAX_MS, BUFFER_START_MS, BUFFER_RESTART_MS)
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
                        built.mapTo(out) {
                            com.hawkynt.mediadroid.player.DelayedTextRenderer(it) { subtitleLook.delayMs }
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
                    ) = super.buildVideoRenderers(
                        context,
                        if (settings.softwareVideoDecoding) {
                            EXTENSION_RENDERER_MODE_PREFER
                        } else {
                            EXTENSION_RENDERER_MODE_ON
                        },
                        mediaCodecSelector,
                        enableDecoderFallback,
                        eventHandler,
                        com.hawkynt.mediadroid.player.PixelShapeListener(eventListener),
                        allowedVideoJoiningTimeMs,
                        out,
                    )

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
                    ): androidx.media3.exoplayer.audio.AudioSink = com.hawkynt.mediadroid.player.DelayedAudioSink(
                        DefaultAudioSink.Builder(context)
                            .setEnableFloatOutput(enableFloatOutput)
                            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                            .setAudioProcessorChain(
                                DefaultAudioSink.DefaultAudioProcessorChain(
                                    LoudnessAudioProcessor({ settings.loudness + loudnessBoostDb }, { nightMode }),
                                ),
                            )
                            .build(),
                        // Blended frames run a little behind; the sound waits as long (see VideoFilterStage).
                        { audioDelayMs + com.hawkynt.mediadroid.player.VideoFilterStage.latencyMs },
                    )
                }
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER),
            )
            .setMediaSourceFactory(
                // Videos on a network source stream through it; local ones as before. Audio files beside
                // the video are merged in (see SidecarAudio).
                com.hawkynt.mediadroid.player.SourceDataSource.Factory(context) {
                    com.hawkynt.mediadroid.data.SourceAccess.resolve(context, it)
                }.let { files ->
                    com.hawkynt.mediadroid.player.SidecarAudioSourceFactory(
                        androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                            files,
                            com.hawkynt.mediadroid.player.AviAudioFraming.extractors,
                        ),
                        files,
                    )
                },
            )
            .build()
    }
    // TV boxes: the display switches to a whole multiple of the film's frame rate (no judder), and back.
    val displayActivity = remember(context) { context.findActivity() }
    // Where it cannot, the speed changes a little so the frames fall evenly (see PlaybackMath.judderSpeed).
    var judderFactor by remember { mutableFloatStateOf(1f) }
    DisposableEffect(player) {
        fun match() {
            if (!settings.matchRefreshRate) return
            val window = displayActivity?.window ?: return
            val display = window.decorView.display ?: return
            val frameRate = player.currentTracks.groups
                .filter { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                .firstNotNullOfOrNull { g ->
                    (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).frameRate }
                }
                ?: return
            val current = display.mode
            val sameSize = display.supportedModes.filter {
                it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
            }
            val rate = PlaybackMath.matchingRefreshRate(frameRate, sameSize.map { it.refreshRate }) ?: return
            val mode = sameSize.firstOrNull { it.refreshRate == rate } ?: return
            window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
        }
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = match()
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            displayActivity?.window?.let { it.attributes = it.attributes.apply { preferredDisplayModeId = 0 } }
        }
    }
    // The frame rate against the refresh rate of the screen the picture is on, checked every few seconds:
    // a display that switched modes, or a second screen, changes it.
    LaunchedEffect(player) {
        while (true) {
            val frameRate = player.videoFormat?.frameRate ?: 0f
            val refresh = PlayerWindow.displayRefreshHz
                ?: displayActivity?.window?.decorView?.display?.refreshRate
                ?: 0f
            val direction = runCatching { PlaybackMath.JudderDirection.valueOf(settings.judderDirection) }
                .getOrDefault(PlaybackMath.JudderDirection.NEAREST)
            judderFactor = PlaybackMath.judderSpeed(frameRate, refresh, settings.judderLimitPercent / 100f, direction)
            delay(3000)
        }
    }
    // Which decoders are at work, as Media3 reports them, for the info sheet.
    var videoDecoder by remember { mutableStateOf<String?>(null) }
    var audioDecoder by remember { mutableStateOf<String?>(null) }
    var audioTrack by remember { mutableStateOf<androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig?>(null) }
    var infoOpen by remember { mutableStateOf(false) }
    // The statistics over the picture (Info › Advanced), for this session only.
    var statisticsShown by remember { mutableStateOf(false) }
    val statistics = remember { com.hawkynt.mediadroid.player.PlaybackStatistics() }
    DisposableEffect(player) {
        val analytics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                videoDecoder = decoderName
            }

            override fun onAudioDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                audioDecoder = decoderName
            }

            override fun onAudioTrackInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig,
            ) {
                audioTrack = audioTrackConfig
            }
        }
        player.addAnalyticsListener(analytics)
        onDispose { player.removeAnalyticsListener(analytics) }
    }
    // A film's sound: other players pause for it, it pauses for a call; spatialized only when switched on.
    LaunchedEffect(spatialAudio) {
        player.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setSpatializationBehavior(
                    if (spatialAudio) C.SPATIALIZATION_BEHAVIOR_AUTO else C.SPATIALIZATION_BEHAVIOR_NEVER,
                )
                .build(),
            true,
        )
    }
    // Picture in picture: leaving the app mid-film shrinks it into a small window; once the app is out of
    // sight (or that window is closed) the film pauses. On a second screen it plays on instead.
    val pipActivity = remember(context) { context.findActivity() }
    // The phone darkened while the film plays on a second screen; leaving the player then locks it.
    val darkPhone = remember { com.hawkynt.mediadroid.player.DarkPhone() }
    var phoneDark by remember { mutableStateOf(false) }
    var askingToLock by remember { mutableStateOf(false) }
    fun leavePlayer() {
        if (darkPhone.leave()) com.hawkynt.mediadroid.player.PhoneLock.lockNow(context)
        phoneDark = false
    }
    DisposableEffect(player) {
        fun publish() {
            PlayerWindow.playing = player.isPlaying
            val size = player.videoSize
            PlayerWindow.aspect = PlaybackMath.pipAspect(size.width, size.height, size.pixelWidthHeightRatio)
                ?.let { (w, h) -> android.util.Rational(w, h) }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                runCatching { pipActivity?.setPictureInPictureParams(PlayerWindow.params()) }
            }
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = publish()
        }
        player.addListener(listener)
        val lifecycle = (pipActivity as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        val stopObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                // Android's own screen asking to allow locking is not leaving the player.
                if (!askingToLock) leavePlayer()
                if (!PlayerWindow.elsewhere) player.pause()
            }
        }
        lifecycle?.addObserver(stopObserver)
        onDispose {
            player.removeListener(listener)
            lifecycle?.removeObserver(stopObserver)
            PlayerWindow.playing = false
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                runCatching { pipActivity?.setPictureInPictureParams(PlayerWindow.params()) }
            }
        }
    }
    val inPip = PlayerWindow.inPictureInPicture

    var index by remember { mutableStateOf(startIndex) }
    // The current video's subtitle and audio files, with their locations, in the order they are merged in.
    var sidecars by remember {
        mutableStateOf(emptyList<Pair<com.hawkynt.mediadroid.library.Sidecars.Sidecar, String>>())
    }
    var finished by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableStateOf(settings.defaultSpeed) }
    var subtitlesOn by remember { mutableStateOf(settings.subtitlesOn) }
    var audioPicker by remember { mutableStateOf(false) }
    var subtitlePicker by remember { mutableStateOf(false) }
    // Once the household picks a track by hand, the settings language steps back.
    var manualTrackPick by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(false) }
    // Per video: its own picture if one was saved, else the household default.
    var picture by remember { mutableStateOf(store.pictureFor(queue[startIndex].videoPath)) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    LaunchedEffect(inPip) {
        if (inPip) {
            panelOpen = false
            playerView?.hideController()
        }
        playerView?.useController = !inPip
    }
    var fit by remember { mutableStateOf(VideoFit.FIT) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // Short-lived on-screen confirmation of a gesture: "+15 s", "Volume 40 %".
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackStamp by remember { mutableIntStateOf(0) }
    fun show(text: String) {
        feedback = text
        feedbackStamp++
    }
    // Holding a skip button (on screen or on a remote) steps further the longer it is held; the target
    // shows while holding and letting go seeks there once.
    val holdScope = androidx.compose.runtime.rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var holdTarget by remember { mutableStateOf<Long?>(null) }
    fun startHold(forward: Boolean) {
        holdJob?.cancel()
        val startedAt = android.os.SystemClock.uptimeMillis()
        var target = player.currentPosition
        holdJob = holdScope.launch {
            while (true) {
                val step = PlaybackMath.holdStepMs(android.os.SystemClock.uptimeMillis() - startedAt)
                val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                target = (target + if (forward) step else -step).coerceIn(0L, end)
                holdTarget = target
                show((if (forward) "» " else "« ") + clock(target))
                playerView?.showController()
                delay(PlaybackMath.HOLD_REPEAT_MS)
            }
        }
    }
    fun endHold() {
        holdJob?.cancel()
        holdJob = null
        holdTarget?.let { player.seekTo(it) }
        holdTarget = null
    }
    val externalDisplay = rememberExternalDisplay()
    var onSecondScreen by remember { mutableStateOf(false) }
    var presentation by remember { mutableStateOf<VideoPresentation?>(null) }

    // Touching one of our own controls must keep Media3's controls up too, or they would time
    // out mid-choice: both are one set of controls to the viewer.
    fun keepControls() {
        playerView?.showController()
    }

    // Immersive while watching: no status or navigation bar over the picture; a swipe from the
    // edge shows them for a moment, leaving the player puts them back.
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        val window = hostView.context.findActivity()?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, hostView) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        val cutoutBefore =
            if (android.os.Build.VERSION.SDK_INT >= 28) window?.attributes?.layoutInDisplayCutoutMode else null
        hostView.keepScreenOn = true
        onDispose {
            if (android.os.Build.VERSION.SDK_INT >= 28 && cutoutBefore != null) {
                window?.attributes = window?.attributes?.apply { layoutInDisplayCutoutMode = cutoutBefore }
            }
            insets?.show(WindowInsetsCompat.Type.systemBars())
            hostView.keepScreenOn = false
        }
    }

    // Intro/credits for the current item: the file's own chapters first, the `.edl` beside it as fallback
    // (see SkipRules). Skipped only while the household has skipping switched on; the credits
    // count towards "watched" either way.
    val skipping = settings.skipIntroCredits
    var introSpanMs by remember { mutableStateOf<LongRange?>(null) }
    var creditsStartMs by remember { mutableStateOf<Long?>(null) }

    // Preferred languages steer automatic selection for each new item. The
    // global preferred language covers audio; the subtitle language is applied
    // as an override once the item's tracks are known (see the listener).
    LaunchedEffect(index) {
        val playable = queue[index]
        manualTrackPick = false
        picture = store.pictureFor(playable.videoPath)
        if (!demo && store.data.lastPlayed != playable.videoPath) {
            store.data.lastPlayed = playable.videoPath
            store.save()
        }
        subtitleLook = store.subtitleLookFor(playable.videoPath)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(settings.audioLanguage.ifEmpty { null })
            .build()
        val chapters = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Chapters mark intros and credits for videos on network sources as for local ones.
            com.hawkynt.mediadroid.data.SourceAccess.resolve(context, playable.videoPath)
                ?.let { com.hawkynt.mediadroid.library.Chapters.read(it) }
                .orEmpty()
        }
        // Subtitle and audio files beside it, named after it (see Sidecars).
        sidecars = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val video = com.hawkynt.mediadroid.data.SourceAccess.resolve(context, playable.videoPath)
            val folder = video?.parentFile
            val names = folder?.listFiles()?.map { it.name }.orEmpty()
            com.hawkynt.mediadroid.library.Sidecars.of(video?.name.orEmpty(), names)
                .map { it to folder!!.child(it.name).path }
        }
        introSpanMs = if (skipping) SkipRules.introSpanMs(chapters, playable.intro) else null
        creditsStartMs = SkipRules.creditsStartMs(chapters, playable.credits)
        // Back on the main thread before touching the player: ExoPlayer only answers the thread it was built on.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            val (subtitleFiles, audioFiles) = sidecars.partition { (sidecar, _) ->
                sidecar.kind == com.hawkynt.mediadroid.library.Sidecars.Kind.SUBTITLES
            }
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(com.hawkynt.mediadroid.player.playableUri(playable.videoPath))
                    .setSubtitleConfigurations(
                        subtitleFiles.map { (sidecar, path) ->
                            MediaItem.SubtitleConfiguration.Builder(com.hawkynt.mediadroid.player.playableUri(path))
                                .setMimeType(sidecar.mimeType)
                                .setLanguage(sidecar.language)
                                .setLabel(sidecar.describe())
                                .setSelectionFlags(if (sidecar.forced) C.SELECTION_FLAG_FORCED else 0)
                                .build()
                        },
                    )
                    .setTag(com.hawkynt.mediadroid.player.SidecarAudio(audioFiles.map { it.second }))
                    .build(),
            )
            val resumePos = store.resumeFor(playable.videoPath)?.positionMs ?: 0
            // Resume where you left off — but never back into a known intro.
            val startPos = SkipRules.skipTarget(resumePos, introSpanMs) ?: resumePos
            if (startPos > 0) player.seekTo(startPos)
            // The demo library's videos are placeholders: the screen shows its chrome without playing them.
            if (!demo) {
                player.prepare()
                player.playWhenReady = true
            }
        }
    }

    LaunchedEffect(speed, subtitlesOn, judderFactor) {
        player.setPlaybackSpeed(speed * judderFactor)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesOn)
            .build()
    }

    /** Remembers how far the current item got: watched once only its tail or credits are left. */
    fun saveProgress(position: Long = player.currentPosition, duration: Long = player.duration) {
        if (duration <= 0 || position <= 0) return
        val path = queue[index].videoPath
        // Right after Next/Previous the player still holds the last file: its position is not ours.
        if (player.currentMediaItem?.localConfiguration?.uri?.path != path) return
        if (PlaybackMath.isWatched(position, duration, creditsStartMs)) {
            store.markWatched(path)
        } else {
            store.saveResume(path, position, duration)
        }
    }

    // Next and Previous on the controls move through the queue (see QueuePlayer).
    val queuePlayer = remember {
        QueuePlayer(
            player,
            hasNext = { index + 1 < queue.size },
            onNext = {
                if (index + 1 < queue.size) {
                    saveProgress()
                    index++
                }
            },
            onPrevious = {
                when (val previous = PlaybackMath.previous(player.currentPosition, index)) {
                    PlaybackMath.Previous.Restart -> player.seekTo(0)
                    is PlaybackMath.Previous.GoTo -> {
                        saveProgress()
                        index = previous.index
                    }
                }
            },
        )
    }

    // Media controls everywhere: a session for headset, Bluetooth and the system's media controls, and a
    // media notification that puts them in the shade and on the lock screen. Both drive the queue, so
    // next and previous are episodes and parts.
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !settings.askedForNotifications &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            settings.askedForNotifications = true
            store.save()
            askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    DisposableEffect(queuePlayer) {
        val session = androidx.media3.session.MediaSession.Builder(context, queuePlayer)
            .setId("mediadroid-player")
            .build()
        val notifications = com.hawkynt.mediadroid.player.PlaybackNotification.attach(
            context,
            queuePlayer,
            session,
            title = { queue[index].title },
            subtitle = { queue[index].subtitle },
            posterPath = { queue[index].posterPath },
        )
        onDispose {
            notifications.setPlayer(null)
            session.release()
        }
    }

    var repeat by remember { mutableStateOf(PlaybackMath.Repeat.OFF) }
    // Child lock: while locked every touch and key but volume is ignored; holding the lock symbol unlocks.
    var locked by remember { mutableStateOf(false) }
    var lockHintStamp by remember { mutableIntStateOf(0) }
    // Sleep timer: a moment on the clock (elapsedRealtime) or "at the end of this video"; never kept.
    var sleepAt by remember { mutableStateOf<Long?>(null) }
    var sleepAtEnd by remember { mutableStateOf(false) }
    var sleepLeftMs by remember { mutableStateOf(0L) }
    var sleepChoice by remember { mutableStateOf<Int?>(null) }
    // A device decoder that cannot attach to the picture's surface gets a fresh one, once per item until a
    // frame shows (see routeVideo below for how); the item it was tried for.
    val freshSurface = remember { arrayOfNulls<() -> Unit>(1) }
    val freshSurfaceFor = remember { arrayOfNulls<String>(1) }

    fun finishCurrent() {
        store.markWatched(queue[index].videoPath)
        if (sleepAtEnd) {
            sleepAtEnd = false
            finished = true
            return
        }
        when (val next = PlaybackMath.afterEnd(index, queue.size, autoAdvance, repeat)) {
            null -> finished = true
            index -> {
                // The same video again: the index does not change, so start it over by hand.
                player.seekTo(0)
                player.playWhenReady = true
            }
            else -> index = next
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) finishCurrent()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // Skipping forward into the tail or the credits finishes the item as well.
                if (reason == Player.DISCONTINUITY_REASON_SEEK) saveProgress(newPosition.positionMs)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val item = player.currentMediaItem
                // After a file the bundled FFmpeg decoded, its software renderer still holds the surface as a
                // CPU producer, and the device's decoder for the next file cannot attach: a fresh surface over
                // the same picture, and the item loaded afresh — which lets the software decoder go, and the
                // old surface with it — to play on from where it was.
                val renew = freshSurface[0]
                if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED &&
                    item != null && freshSurfaceFor[0] != item.mediaId && renew != null
                ) {
                    freshSurfaceFor[0] = item.mediaId
                    val position = player.currentPosition
                    renew()
                    player.setMediaItem(item, position)
                    player.prepare()
                    return
                }
                // An audio file beside the video that cannot go along with it (one that cannot seek, say)
                // must not stop the film: it plays again without them, from where it was.
                val audio = item?.localConfiguration?.tag as? com.hawkynt.mediadroid.player.SidecarAudio
                if (item != null && audio != null && audio.locations.isNotEmpty()) {
                    val position = player.currentPosition
                    sidecars = sidecars.filter { it.first.kind != com.hawkynt.mediadroid.library.Sidecars.Kind.AUDIO }
                    player.setMediaItem(item.buildUpon().setTag(null).build(), position)
                    player.prepare()
                    return
                }
                playError = error.errorCodeName
            }

            override fun onRenderedFirstFrame() {
                freshSurfaceFor[0] = null
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val wanted = settings.subtitleLanguage
                if (wanted.isEmpty() || manualTrackPick) return
                val group = tracks.groups.firstOrNull { g ->
                    g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.getTrackFormat(it).language == wanted }
                } ?: return
                val track = (0 until group.length).first { group.getTrackFormat(it).language == wanted }
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
                    .build()
            }
        }
        player.addListener(listener)
        onDispose {
            saveProgress()
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            delay(500)
            if (!player.isPlaying) continue
            val position = player.currentPosition
            // Inside a known intro (also one after a cold open): jump to its end.
            SkipRules.skipTarget(position, introSpanMs)?.let {
                player.seekTo(it)
                continue
            }
            val credits = creditsStartMs
            if (skipping && credits != null && position >= credits) {
                // The credits have begun: end the episode, binge mode takes it from there.
                player.seekTo(player.duration)
                continue
            }
            if (++tick % 6 == 0) saveProgress(position)
        }
    }

    LaunchedEffect(sleepAt) {
        val at = sleepAt ?: run {
            player.volume = 1f
            return@LaunchedEffect
        }
        while (true) {
            val left = at - android.os.SystemClock.elapsedRealtime()
            sleepLeftMs = left
            player.volume = PlaybackMath.sleepFade(left)
            if (left <= 0) {
                player.pause()
                player.volume = 1f
                sleepAt = null
                show("Sleep timer: paused")
                break
            }
            delay(250)
        }
    }

    // The camera cutout: the window may reach into it (short edges) or keep clear of it.
    LaunchedEffect(useCutout) {
        val window = hostView.context.findActivity()?.window ?: return@LaunchedEffect
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (useCutout) {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
    }

    val activityWindow = remember(hostView) { hostView.context.findActivity()?.window }
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var brightness by remember {
        mutableFloatStateOf(
            activityWindow?.attributes?.screenBrightness?.takeIf { it >= 0f }
                ?: (android.provider.Settings.System.getInt(
                    context.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                    128,
                ) / 255f).coerceIn(0f, 1f),
        )
    }
    var volume by remember {
        mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume)
    }
    // The swipe-set brightness belongs to the player; leaving it hands control back to the system.
    DisposableEffect(activityWindow) {
        onDispose {
            activityWindow?.let { window ->
                window.attributes = window.attributes.apply { screenBrightness = -1f }
            }
        }
    }

    fun swipe(side: Side, upFraction: Float) {
        when (side) {
            Side.LEFT -> {
                brightness = PlaybackMath.boostedLevel(brightness, upFraction)
                gammaBoost = PlaybackMath.gammaBoost(brightness)
                activityWindow?.let { window ->
                    window.attributes = window.attributes.apply {
                        screenBrightness = brightness.coerceIn(0.01f, 1f)
                    }
                }
                show("Brightness ${PlaybackMath.percent(brightness)} %")
            }
            Side.RIGHT -> {
                volume = PlaybackMath.boostedLevel(volume, upFraction)
                loudnessBoostDb = PlaybackMath.boostDb(volume)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume.coerceAtMost(1f) * maxVolume).roundToInt(), 0)
                show("Volume ${PlaybackMath.percent(volume)} %")
            }
        }
    }

    // The video on the external display while the phone keeps the controls; back to the phone
    // when switched off or when the display goes away.
    DisposableEffect(onSecondScreen, externalDisplay?.displayId) {
        val display = externalDisplay
        if (onSecondScreen && display != null) {
            presentation = VideoPresentation(context, display, player).also { it.show() }
            PlayerWindow.elsewhere = true
            PlayerWindow.displayRefreshHz = display.refreshRate
            com.hawkynt.mediadroid.player.PlaybackService.start(context)
        } else if (onSecondScreen) {
            onSecondScreen = false
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            runCatching { pipActivity?.setPictureInPictureParams(PlayerWindow.params()) }
        }
        onDispose {
            darkPhone.secondScreenGone()
            phoneDark = false
            PlayerWindow.displayRefreshHz = null
            if (PlayerWindow.elsewhere) com.hawkynt.mediadroid.player.PlaybackService.stop(context)
            PlayerWindow.elsewhere = false
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                runCatching { pipActivity?.setPictureInPictureParams(PlayerWindow.params()) }
            }
            presentation?.let {
                it.release()
                presentation = null
                // The phone's view takes the picture back.
                playerView?.videoSurfaceView?.visibility = View.VISIBLE
                playerView?.player = null
                playerView?.player = queuePlayer
            }
        }
    }

    LaunchedEffect(feedbackStamp) {
        delay(800)
        feedback = null
    }

    // Darkening asks once for permission to lock the phone; it darkens whatever the answer, locking only
    // when allowed.
    val askToLock = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) {
        askingToLock = false
        darkPhone.darken()
        phoneDark = true
    }
    DisposableEffect(phoneDark) {
        val window = pipActivity?.window
        if (phoneDark) playerView?.hideController()
        window?.attributes = window?.attributes?.apply {
            screenBrightness = if (phoneDark) {
                0f
            } else {
                android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
        onDispose {
            window?.attributes = window?.attributes?.apply {
                screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    BackHandler {
        when {
            locked -> lockHintStamp++
            panelOpen -> panelOpen = false
            else -> {
                leavePlayer()
                onExit()
            }
        }
    }

    val current = queue[index]
    // The video's own picture correction, with the brightness swipe's gamma on top once past the maximum.
    val shownPicture = picture.copy(gamma = picture.gamma * gammaBoost)
    // The picture filters' GL stage (see VideoFilterStage): the player draws into it while a filter is
    // set — not for HDR, which it would flatten — and it draws into the view that shows the picture,
    // on the phone or the second screen; otherwise the player draws straight into that view.
    val filterStage = remember { arrayOfNulls<com.hawkynt.mediadroid.player.VideoFilterStage>(1) }
    val routedTo = remember { arrayOfNulls<android.view.TextureView>(1) }
    fun routeVideo() {
        val target = ((presentation?.takeIf { it.isShowing }?.playerView ?: playerView)?.videoSurfaceView)
            as? android.view.TextureView
        val transfer = player.videoFormat?.colorInfo?.colorTransfer
        val hdr = transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG
        val wanted = shownPicture.hasFilters && !hdr && target != null && target.isAvailable
        val stage = filterStage[0]
        if (wanted) {
            val active = stage ?: com.hawkynt.mediadroid.player.VideoFilterStage().also { filterStage[0] = it }
            active.statistics = if (statisticsShown) statistics else null
            active.filters = shownPicture
            active.setVideoSize(player.videoSize.width, player.videoSize.height)
            if (routedTo[0] !== target) {
                player.setVideoSurface(active.inputSurface)
                // Taken on a later round when the view's surface is not free yet.
                if (active.setOutput(android.view.Surface(target!!.surfaceTexture))) routedTo[0] = target
            }
        } else if (routedTo[0] != null) {
            stage?.setOutput(null)
            routedTo[0] = null
            target?.let { player.setVideoTextureView(it) }
        }
    }
    freshSurface[0] = {
        val stage = filterStage[0]
        val view = routedTo[0] ?: ((presentation?.takeIf { it.isShowing }?.playerView ?: playerView)
            ?.videoSurfaceView as? android.view.TextureView)
        if (stage != null && routedTo[0] != null) {
            stage.renewInput { player.setVideoSurface(it) }
        } else if (view != null) {
            // The player makes its own surface over the view's, and lets the old one go.
            player.clearVideoTextureView(view)
            player.setVideoTextureView(view)
        }
    }
    androidx.compose.runtime.SideEffect { filterStage[0]?.filters = shownPicture }
    // Without the filters' stage, the statistics take each frame as the player releases it to the screen.
    DisposableEffect(player, statisticsShown) {
        val listener = androidx.media3.exoplayer.video.VideoFrameMetadataListener { _, releaseTimeNs, _, _ ->
            if (routedTo[0] == null) {
                statistics.frameArrived(releaseTimeNs)
                statistics.pictureShown(releaseTimeNs)
            }
        }
        if (statisticsShown) player.setVideoFrameMetadataListener(listener)
        onDispose { if (statisticsShown) player.clearVideoFrameMetadataListener(listener) }
    }
    LaunchedEffect(index) { statistics.clear() }
    // The loop asks the latest composition's routing, with the picture and screens as they are now.
    val routeNow by androidx.compose.runtime.rememberUpdatedState({ routeVideo() })
    LaunchedEffect(player) {
        while (true) {
            routeNow()
            delay(400)
        }
    }
    DisposableEffect(player) {
        onDispose {
            filterStage[0]?.setOutput(null)
            filterStage[0]?.release()
            filterStage[0] = null
        }
    }

    // While the phone's own screen is mirrored to a TV (Smart View and friends show up as a presentation
    // display we are not using), the TV crops a long phone screen to 16:9: overlays stay inside that part.
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val mirrored = externalDisplay != null && !onSecondScreen
    val (insetX, insetY) = if (mirrored) {
        PlaybackMath.mirrorSafeInset(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat())
    } else {
        0f to 0f
    }
    val safeArea = Modifier.padding(horizontal = insetX.dp, vertical = insetY.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // A remote's left/right (with the controls hidden) and its rewind/fast-forward keys: held,
            // they step like the on-screen buttons.
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (locked) {
                    val volumeKey = native.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                        native.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
                        native.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_MUTE
                    if (!volumeKey && native.action == android.view.KeyEvent.ACTION_DOWN) lockHintStamp++
                    return@onPreviewKeyEvent !volumeKey
                }
                val forward = when (native.keyCode) {
                    android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> true
                    android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> false
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (controlsVisible) null else true
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (controlsVisible) null else false
                    else -> null
                } ?: return@onPreviewKeyEvent false
                when {
                    native.action == android.view.KeyEvent.ACTION_DOWN && native.repeatCount == 1 -> {
                        startHold(forward)
                        true
                    }
                    native.action == android.view.KeyEvent.ACTION_DOWN && native.repeatCount > 1 -> true
                    native.action == android.view.KeyEvent.ACTION_UP && holdJob != null -> {
                        endHold()
                        true
                    }
                    else -> false
                }
            },
    ) {
        // The demo library's videos are empty placeholders: their picture stands in for the video,
        // showing through the player's (then transparent) shutter beneath its controls.
        if (demo) PosterImage(queue[index].posterPath, contentScale = ContentScale.Crop)
        AndroidView(
            factory = { androidContext ->
                // Inflated, not constructed: the texture surface (see the layout)
                // is only reachable through the XML attribute.
                (
                    android.view.LayoutInflater.from(androidContext)
                        .inflate(R.layout.player_view, null, false) as PlayerView
                    ).apply {
                    this.player = queuePlayer
                    useController = true
                    // Start on the bare picture; a tap shows the controls, inactivity hides them.
                    controllerAutoShow = false
                    controllerShowTimeoutMs = ControlsTimeoutMs
                    controllerHideOnTouch = true
                    // Speed, subtitles and tracks are ours (wider speed range, language rules);
                    // Media3's gear would offer a second, conflicting set.
                    findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
                    setShowSubtitleButton(false)
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == View.VISIBLE
                        },
                    )
                    if (demo) setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    findViewById<View>(androidx.media3.ui.R.id.exo_ffwd)?.onHold({ startHold(true) }, ::endHold)
                    findViewById<View>(androidx.media3.ui.R.id.exo_rew)?.onHold({ startHold(false) }, ::endHold)
                    playerView = this
                }
            },
            update = { view ->
                view.subtitleView?.applyLook(subtitleLook)
                // While the picture is on the other screen the phone's surface would keep showing the
                // last frame it got; it is hidden so the phone stays black behind the controls.
                val elsewhere = presentation?.isShowing == true
                view.videoSurfaceView?.visibility = if (elsewhere) View.INVISIBLE else View.VISIBLE
                if (!elsewhere) view.applyVideoLook(fit, zoom, pan, shownPicture)
                val density = view.resources.displayMetrics.density
                view.findViewById<View>(androidx.media3.ui.R.id.exo_controller)?.setPadding(
                    (insetX * density).toInt(),
                    (insetY * density).toInt(),
                    (insetX * density).toInt(),
                    (insetY * density).toInt(),
                )
            },
            modifier = Modifier.fillMaxSize(),
        )

        presentation?.let { shown ->
            if (shown.isShowing) shown.playerView.applyVideoLook(fit, zoom, pan, shownPicture)
            if (!controlsVisible) Text(
                "Playing on ${externalDisplay?.name ?: "the other screen"}",
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // The bare picture listens for gestures; with the controls up, touches go to the controls.
        if (!controlsVisible && !panelOpen && !inPip) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .playerGestures(
                        onTap = { playerView?.showController() },
                        onDoubleTap = { side ->
                            val forward = side == Side.RIGHT
                            player.seekTo(PlaybackMath.seekBy(player.currentPosition, player.duration, forward))
                            show(if (forward) "+15 s" else "−15 s")
                        },
                        onSwipe = ::swipe,
                        onSwipeEnd = {},
                        onPinch = { factor, move ->
                            val view = playerView ?: return@playerGestures
                            zoom = PlaybackMath.zoom(zoom, factor)
                            pan = Offset(
                                PlaybackMath.pan(pan.x + move.x, zoom, view.width.toFloat()),
                                PlaybackMath.pan(pan.y + move.y, zoom, view.height.toFloat()),
                            )
                            show("Zoom %.1f×".format(zoom))
                        },
                    ),
            )
        }
        feedback?.takeIf { !inPip }?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 22.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        // When the film will be over on the clock, on the free right end of Media3's time row.
        if (controlsVisible && !inPip) {
            var endsAt by remember { mutableStateOf<Long?>(null) }
            LaunchedEffect(Unit) {
                while (true) {
                    endsAt = PlaybackMath.endsAtMs(
                        System.currentTimeMillis(),
                        player.currentPosition,
                        player.duration,
                        player.playbackParameters.speed,
                    )
                    delay(1000)
                }
            }
            endsAt?.let { at ->
                Text(
                    "Ends " + android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(at)),
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .then(safeArea)
                        .padding(end = 16.dp, bottom = 14.dp),
                )
            }
        }
        // Our controls ride on top, in the band Media3 leaves free, and come and go with its own.
        AnimatedVisibility(
            visible = controlsVisible && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).then(safeArea),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { leavePlayer(); onExit() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        buildString {
                            append(current.title)
                            current.subtitle?.let { append("  ·  $it") }
                        },
                        color = Color.White,
                        fontSize = 18.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    val sleepNote = when {
                        sleepAtEnd -> "Sleep at the end"
                        sleepAt != null -> "Sleep in ${PlaybackMath.sleepMinutesLeft(sleepLeftMs)} min"
                        else -> null
                    }
                    sleepNote?.let { Text(it, color = Color.White, fontSize = 15.sp, maxLines = 1) }
                    if (externalDisplay != null) {
                        IconButton(
                            onClick = {
                                onSecondScreen = !onSecondScreen
                                keepControls()
                            },
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_tv),
                                contentDescription =
                                    if (onSecondScreen) "Back to this screen" else "Play on the other screen",
                                tint = if (onSecondScreen) MaterialTheme.colorScheme.primary else Color.White,
                            )
                        }
                    }
                    if (onSecondScreen) {
                        IconButton(
                            onClick = {
                                if (com.hawkynt.mediadroid.player.PhoneLock.allowed(context)) {
                                    darkPhone.darken()
                                    phoneDark = true
                                } else {
                                    askingToLock = true
                                    askToLock.launch(com.hawkynt.mediadroid.player.PhoneLock.askIntent(context))
                                }
                            },
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_phonelink_lock),
                                contentDescription = "Darken this phone",
                                tint = Color.White,
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            infoOpen = true
                            playerView?.hideController()
                        },
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = "What is playing", tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            panelOpen = true
                            playerView?.hideController()
                        },
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "Playback and picture", tint = Color.White)
                    }
                }
            }
        }
        if (panelOpen) {
            // Tapping beside the panel closes it, like the players everyone knows.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = null, indication = null) { panelOpen = false },
            )
            PlayerPanel(
                speed = speed,
                onSpeed = { speed = it },
                fit = fit,
                onFit = {
                    fit = it
                    zoom = 1f
                    pan = Offset.Zero
                },
                subtitlesOn = subtitlesOn,
                onSubtitles = { subtitlesOn = it },
                picture = picture,
                onPicture = { picture = it },
                onPictureDone = { store.savePicture(queue[index].videoPath, picture) },
                onResetPicture = {
                    store.savePicture(queue[index].videoPath, null)
                    picture = store.pictureFor(queue[index].videoPath)
                },
                onAudioTrack = { audioPicker = true },
                onSubtitleTrack = { subtitlePicker = true },
                onClose = { panelOpen = false },
                afterPictureSize = {
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Use the camera cutout", fontSize = 16.sp)
                                Text(
                                    "The picture may reach into the notch or punch hole, mostly useful with Fill.",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            androidx.compose.material3.Switch(
                                checked = useCutout,
                                onCheckedChange = {
                                    useCutout = it
                                    store.data.settings.useCutout = it
                                    store.save()
                                },
                            )
                        }
                    }
                },
                sections = {
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            locked = true
                            panelOpen = false
                            playerView?.hideController()
                            lockHintStamp++
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Lock the player", fontSize = 16.sp)
                    }
                    Text(
                        "Touches and buttons do nothing until the lock symbol is held for two seconds.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    PanelTitle("Sound")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Night mode", fontSize = 16.sp)
                            Text(
                                "Quiet dialogue comes up, loud scenes come down.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.Switch(checked = nightMode, onCheckedChange = { nightMode = it })
                    }


                    PanelTitle("Audio delay · ${audioOutput.name}")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        fun nudge(steps: Int) {
                            audioDelayMs = PlaybackMath.nudgeAudioDelay(audioDelayMs, steps)
                            store.saveAudioDelay(audioOutput.key, audioDelayMs)
                        }
                        androidx.compose.material3.OutlinedButton(onClick = { nudge(-1) }) {
                            Text("Sooner", fontSize = 16.sp)
                        }
                        Text(
                            if (audioDelayMs == 0) "in sync" else "%+d ms".format(audioDelayMs),
                            fontSize = 18.sp,
                            modifier = Modifier.widthIn(min = 90.dp),
                        )
                        androidx.compose.material3.OutlinedButton(onClick = { nudge(1) }) {
                            Text("Later", fontSize = 16.sp)
                        }
                        if (audioDelayMs != 0) {
                            androidx.compose.material3.TextButton(
                                onClick = { nudge(-audioDelayMs / PlaybackMath.AUDIO_DELAY_STEP_MS) },
                            ) {
                                Text("Reset")
                            }
                        }
                    }
                    Text(
                        PlaybackMath.syncText("sound", audioDelayMs).replaceFirstChar { it.uppercase() } +
                            ". Later when you hear a word before the lips move, sooner when after. " +
                            "Kept for this output.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    PanelTitle("Subtitles")
                    fun changeLook(look: com.hawkynt.mediadroid.player.SubtitleLook) {
                        subtitleLook = look
                        store.saveSubtitleLook(queue[index].videoPath, look)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Delay", fontSize = 16.sp, modifier = Modifier.widthIn(min = 56.dp))
                        androidx.compose.material3.OutlinedButton(
                            onClick = { changeLook(subtitleLook.nudgeDelay(-1)) },
                        ) {
                            Text("Sooner", fontSize = 16.sp)
                        }
                        Text(
                            if (subtitleLook.delayMs == 0) {
                                "in sync"
                            } else {
                                "%+.1f s".format(subtitleLook.delayMs / 1000f)
                            },
                            fontSize = 18.sp,
                            modifier = Modifier.widthIn(min = 80.dp),
                        )
                        androidx.compose.material3.OutlinedButton(
                            onClick = { changeLook(subtitleLook.nudgeDelay(1)) },
                        ) {
                            Text("Later", fontSize = 16.sp)
                        }
                    }
                    Text(
                        PlaybackMath.syncText("subtitles", subtitleLook.delayMs).replaceFirstChar { it.uppercase() } +
                            ". Later when a line shows before it is spoken, sooner when after.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        com.hawkynt.mediadroid.player.SubtitleLook.Size.entries.forEach { size ->
                            FilterChip(
                                selected = subtitleLook.size == size,
                                onClick = { changeLook(subtitleLook.copy(size = size)) },
                                label = { Text(size.label, fontSize = 16.sp) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Raised above burned-in text", fontSize = 16.sp, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(
                            checked = subtitleLook.raised,
                            onCheckedChange = { changeLook(subtitleLook.copy(raised = it)) },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Dark box behind the text", fontSize = 16.sp, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(
                            checked = subtitleLook.box,
                            onCheckedChange = { changeLook(subtitleLook.copy(box = it)) },
                        )
                    }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            store.saveSubtitleLook(queue[index].videoPath, null)
                            subtitleLook = store.subtitleLookFor(queue[index].videoPath)
                        },
                    ) { Text("Back to the household's look") }

                    if (spatializerAvailable) {
                        PanelTitle("Spatial audio")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Surround sound through the system's spatializer, for headphones that support it.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            androidx.compose.material3.Switch(
                                checked = spatialAudio,
                                onCheckedChange = {
                                    spatialAudio = it
                                    store.data.settings.spatialAudio = it
                                    store.save()
                                },
                            )
                        }
                    }

                    PanelTitle("Rotation")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Rotation.entries.forEach { option ->
                            FilterChip(
                                selected = rotation == option,
                                onClick = { rotation = option },
                                label = { Text(option.label, fontSize = 16.sp) },
                            )
                        }
                    }

                    PanelTitle("Sleep timer")
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = sleepAt == null && !sleepAtEnd,
                            onClick = {
                                sleepAt = null
                                sleepAtEnd = false
                            },
                            label = { Text("Off", fontSize = 16.sp) },
                        )
                        PlaybackMath.SLEEP_MINUTES.forEach { minutes ->
                            FilterChip(
                                selected = sleepAt != null && sleepChoice == minutes,
                                onClick = {
                                    sleepAtEnd = false
                                    sleepChoice = minutes
                                    sleepLeftMs = minutes * 60_000L
                                    sleepAt = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
                                },
                                label = { Text("$minutes min", fontSize = 16.sp) },
                            )
                        }
                        FilterChip(
                            selected = sleepAtEnd,
                            onClick = {
                                sleepAt = null
                                sleepAtEnd = true
                            },
                            label = { Text("End of this video", fontSize = 16.sp) },
                        )
                    }

                    PanelTitle("Repeat")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PlaybackMath.Repeat.entries.forEach { option ->
                            FilterChip(
                                selected = repeat == option,
                                onClick = { repeat = option },
                                label = { Text(option.label, fontSize = 16.sp) },
                            )
                        }
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd).then(safeArea),
            )
        }
        if (statisticsShown) {
            StatisticsOverlay(statistics, Modifier.align(Alignment.TopStart).then(safeArea).padding(8.dp))
        }
        if (locked) {
            LockOverlay(stamp = lockHintStamp, onTouch = { lockHintStamp++ }, onUnlock = { locked = false })
        }
        if (infoOpen) {
            PlaybackInfoDialog(
                playing = queue[index],
                fileTags = player.mediaMetadata,
                videoPath = queue[index].videoPath,
                tracks = player.currentTracks,
                videoSize = player.videoSize,
                graph = com.hawkynt.mediadroid.player.PlaybackChain.of(
                    playbackFacts(
                        location = queue[index].videoPath,
                        player = player,
                        videoDecoder = videoDecoder,
                        audioDecoder = audioDecoder,
                        audioTrack = audioTrack,
                        picture = shownPicture,
                        fit = fit.label,
                        zoom = zoom,
                        screen = presentation?.takeIf { it.isShowing }?.display ?: hostView.display,
                        screenName = if (presentation?.isShowing == true) {
                            externalDisplay?.name ?: "the other screen"
                        } else {
                            "this screen"
                        },
                        loudnessDb = settings.loudness + loudnessBoostDb,
                        nightMode = nightMode,
                        speed = speed,
                        delayMs = audioDelayMs,
                        spatial = spatialAudio,
                        device = audioOutput.name,
                        subtitleDelayMs = subtitleLook.delayMs,
                        speedFactor = judderFactor,
                        sidecars = sidecars,
                    ),
                ),
                statisticsShown = statisticsShown,
                onStatistics = { statisticsShown = it },
                onDismiss = { infoOpen = false },
            )
        }
        if (audioPicker) {
            // Audio files beside the video are merged in as sources 1, 2 …: their names say what they are.
            val audioFiles = sidecars.filter { it.first.kind == com.hawkynt.mediadroid.library.Sidecars.Kind.AUDIO }
            TrackDialog(
                player = player,
                trackType = C.TRACK_TYPE_AUDIO,
                labelFor = { groupId ->
                    groupId.substringBefore(':', "").toIntOrNull()
                        ?.takeIf { audioFiles.isNotEmpty() && it >= 1 }
                        ?.let { audioFiles.getOrNull(it - 1)?.first?.describe() }
                },
                onDismiss = { audioPicker = false },
                onPick = { picked ->
                    manualTrackPick = true
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .apply { picked?.let { setOverrideForType(it) } }
                        .build()
                    audioPicker = false
                },
            )
        }
        if (subtitlePicker) {
            TrackDialog(
                player = player,
                trackType = C.TRACK_TYPE_TEXT,
                onDismiss = { subtitlePicker = false },
                onPick = { picked ->
                    manualTrackPick = true
                    subtitlesOn = picked != null || subtitlesOn
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .apply { picked?.let { setOverrideForType(it) } }
                        .build()
                    subtitlePicker = false
                },
            )
        }
        if (finished) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = { leavePlayer(); onExit() }) {
                    Text("All done — back to the library", fontSize = 22.sp)
                }
            }
        }
        playError?.let { code ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = { leavePlayer(); onExit() }) {
                    Text("Cannot play this file ($code) — back to the library", fontSize = 22.sp)
                }
            }
        }
        // The darkened phone: black over everything; a touch lights it up and brings the controls back.
        if (phoneDark) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            darkPhone.touch()
                            phoneDark = false
                            playerView?.showController()
                        }
                    },
            )
        }
    }
}

/** Lists the audio or subtitle tracks of the current item; "Automatic" clears the pick. */
@Composable
private fun TrackDialog(
    player: Player,
    trackType: @C.TrackType Int,
    labelFor: (String) -> String? = { null },
    onDismiss: () -> Unit,
    onPick: (TrackSelectionOverride?) -> Unit,
) {
    val groups = player.currentTracks.groups.filter { it.type == trackType }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", fontSize = 18.sp) } },
        text = {
            Column {
                TextButton(onClick = { onPick(null) }) { Text("Automatic", fontSize = 20.sp) }
                groups.forEach { group ->
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        val label = labelFor(group.mediaTrackGroup.id)
                            ?: format.label
                            ?: format.language
                            ?: "Track ${i + 1}"
                        TextButton(onClick = { onPick(TrackSelectionOverride(group.mediaTrackGroup, i)) }) {
                            Text(
                                buildString {
                                    append(label)
                                    format.language?.let { append("  ($it)") }
                                },
                                fontSize = 20.sp,
                            )
                        }
                    }
                }
                if (groups.isEmpty()) Text("This file has only one such track.", fontSize = 18.sp)
            }
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The gear's side panel: speed, this video's picture, subtitles and tracks. Picture changes show
 * live and are remembered for this one video when a slider is let go.
 */
@Composable
private fun PlayerPanel(
    speed: Float,
    onSpeed: (Float) -> Unit,
    fit: VideoFit,
    onFit: (VideoFit) -> Unit,
    subtitlesOn: Boolean,
    onSubtitles: (Boolean) -> Unit,
    picture: PictureAdjustment,
    onPicture: (PictureAdjustment) -> Unit,
    onPictureDone: () -> Unit,
    onResetPicture: () -> Unit,
    onAudioTrack: () -> Unit,
    onSubtitleTrack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    sections: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
    afterPictureSize: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(max = 400.dp)
            .fillMaxWidth(0.9f)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .clickable(interactionSource = null, indication = null) {}
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Playback & picture", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        }

        PanelTitle("Speed")
        SpeedSlider(speed, onSpeed, Modifier.fillMaxWidth())

        sections()

        PanelTitle("Picture size")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VideoFit.entries.forEach { option ->
                FilterChip(
                    selected = fit == option,
                    onClick = { onFit(option) },
                    label = { Text(option.label, fontSize = 16.sp) },
                )
            }
        }
        Text(
            "Pinch with two fingers to zoom; choosing a size resets the zoom.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        afterPictureSize()

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 20.dp)) {
            Text(
                "Picture for this video",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onResetPicture) { Text("Reset", fontSize = 16.sp) }
        }
        PictureSliders(picture, onChange = onPicture, onDone = onPictureDone)

        PanelTitle("Subtitles & sound")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Subtitles", fontSize = 17.sp, modifier = Modifier.weight(1f))
            Switch(checked = subtitlesOn, onCheckedChange = onSubtitles)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = onAudioTrack) { Text("Audio track…", fontSize = 16.sp) }
            OutlinedButton(onClick = onSubtitleTrack) { Text("Subtitle track…", fontSize = 16.sp) }
        }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

/** `1:23:45` or `23:45` for a position in milliseconds. */
private fun clock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = total / 60 % 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** How long a skip button must be held before it starts stepping on its own. */
private const val HOLD_START_MS = 400L

/**
 * Calls [start] once the view has been held for [HOLD_START_MS] and [end] when it is let go; a short
 * tap stays an ordinary click.
 */
@android.annotation.SuppressLint("ClickableViewAccessibility")
private fun View.onHold(start: () -> Unit, end: () -> Unit) {
    var holding = false
    val starter = Runnable {
        holding = true
        start()
    }
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                holding = false
                view.postDelayed(starter, HOLD_START_MS)
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(starter)
                if (holding) {
                    holding = false
                    end()
                    // The hold replaced the click.
                    view.isPressed = false
                    return@setOnTouchListener true
                }
            }
        }
        false
    }
}

/** How long the lock symbol must be held to unlock the player. */
private const val UNLOCK_HOLD_MS = 2_000L

/** How long the lock symbol stays up after a touch. */
private const val LOCK_HINT_MS = 2_500L

/**
 * The locked player: a layer over everything that swallows every touch. Any touch (or a [stamp] change
 * from a key or Back) shows the lock symbol for a moment; holding it for [UNLOCK_HOLD_MS] unlocks.
 */
@Composable
private fun LockOverlay(stamp: Int, onTouch: () -> Unit, onUnlock: () -> Unit) {
    var visible by remember { mutableStateOf(true) }
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(stamp, holding) {
        visible = true
        if (!holding) {
            delay(LOCK_HINT_MS)
            visible = false
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.any { it.pressed }) onTouch()
                    }
                }
            },
    ) {
        if (visible) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(96.dp)
                    .background(Color.Black.copy(alpha = if (holding) 0.85f else 0.6f), CircleShape)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            holding = true
                            val released = withTimeoutOrNull(UNLOCK_HOLD_MS) { waitForUpOrCancellation() }
                            holding = false
                            if (released == null) onUnlock()
                        }
                    },
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Hold to unlock",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    }
}

/** Size, position and backing of the subtitles as [look] has them. */
private fun androidx.media3.ui.SubtitleView.applyLook(look: com.hawkynt.mediadroid.player.SubtitleLook) {
    setFractionalTextSize(look.textSizeFraction)
    setBottomPaddingFraction(look.bottomPaddingFraction)
    setStyle(
        androidx.media3.ui.CaptionStyleCompat(
            android.graphics.Color.WHITE,
            if (look.box) 0xB0000000.toInt() else android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
            if (look.box) androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
            else androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.BLACK,
            null,
        ),
    )
}

/** How the player screen turns: with the device, or held in one orientation (sensor-flipped either way). */
private enum class Rotation(val label: String, val orientation: Int) {
    AUTO("Auto", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER),
    LANDSCAPE("Landscape", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
    PORTRAIT("Portrait", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT),
}

/** At least half a minute of video buffered, two minutes at most, as memory allows. */
private const val BUFFER_MIN_MS = 30_000
private const val BUFFER_MAX_MS = 120_000

/** Playback starts with 2.5 s buffered, and again after a stall with 5 s. */
private const val BUFFER_START_MS = 2_500
private const val BUFFER_RESTART_MS = 5_000

