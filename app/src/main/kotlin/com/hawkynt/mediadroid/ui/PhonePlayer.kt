package com.hawkynt.mediadroid.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.media.AudioManager
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import com.hawkynt.mediadroid.player.PictureAdjustment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hawkynt.mediadroid.R
import com.hawkynt.mediadroid.ui.resources.Res
import com.hawkynt.mediadroid.ui.resources.ic_phonelink_lock
import com.hawkynt.mediadroid.ui.resources.ic_tv
import org.jetbrains.compose.resources.painterResource
import com.hawkynt.mediadroid.engine.PlaybackEngine
import com.hawkynt.mediadroid.engine.Track
import com.hawkynt.mediadroid.engine.VideoFormat
import com.hawkynt.mediadroid.player.AndroidPlaybackEngine
import com.hawkynt.mediadroid.player.PlaybackQueue
import com.hawkynt.mediadroid.player.PlayerPanel
import com.hawkynt.mediadroid.player.PlayerWindow
import com.hawkynt.mediadroid.state.StateStore
import com.hawkynt.mediadroid.data.screenKey
import com.hawkynt.mediadroid.player.PictureFit
import com.hawkynt.mediadroid.player.PictureLayout
import com.hawkynt.mediadroid.player.ScreenRect
import com.hawkynt.mediadroid.player.ScreenWarp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.key
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableLongStateOf
import com.hawkynt.mediadroid.player.BurnInDrift


/** How long the controls stay up after the last touch, like the streaming apps' players. */
private const val ControlsTimeoutMs = 3_000

/**
 * The picture's shape on the panel: [video]'s own, narrowed or flattened by the black bars the filters' stage crops —
 * [share] the part of the width and height it still shows — so Fit, Fill and Stretch work on the picture alone — or
 * the shape [aspect] gives the whole frame where the file's flag is wrong; drawn narrower on a screen seen wider than
 * its pixels ([screen], an anamorphic lens), so it is seen as meant; as wide as the stage's [warp] shows it
 * panoramic, or the whole screen where the warp draws all of it (a keystone).
 */
private fun PlayerPanel.applyCropShape(
    video: VideoFormat?,
    share: Pair<Float, Float>,
    aspect: com.hawkynt.mediadroid.player.AspectOverride,
    screen: com.hawkynt.mediadroid.player.ScreenShape,
    warp: ScreenWarp,
) {
    val drawn = drawnAspect(video, share, aspect, screen) ?: return
    shapePicture(if (warp.wholeScreen && panelAspect > 0f) panelAspect else drawn * warp.panorama)
}

/** The shape the picture is drawn in on this panel's screen (see [applyCropShape]); null before its size is known. */
private fun PlayerPanel.drawnAspect(
    video: VideoFormat?,
    share: Pair<Float, Float>,
    aspect: com.hawkynt.mediadroid.player.AspectOverride,
    screen: com.hawkynt.mediadroid.player.ScreenShape,
): Float? {
    if (video == null || video.width == 0 || video.height == 0) return null
    val meant = com.hawkynt.mediadroid.player.PictureShape.aspect(
        video.width, video.height, video.pixelAspect, aspect, share.first, share.second,
    )
    return com.hawkynt.mediadroid.player.PictureShape.drawn(meant, panelAspect, screen)
}

/**
 * Fit, zoom and picture correction on the video frame inside a player panel only — the controls and subtitles drawn
 * above it are neither scaled nor tinted. [drift] moves the picture and the subtitles together (BurnInDrift, an OLED
 * screen's burn-in care).
 */
private fun PlayerPanel.applyVideoLook(
    fit: PictureFit,
    zoom: Float,
    pan: Offset,
    drift: Offset = Offset.Zero,
    bend: ScreenWarp = ScreenWarp.None,
) = fitPicture(fit, zoom, pan.x, pan.y, drift.x, drift.y, bend)

/**
 * The phone's player: the shared [PlayerScreen] over the phone's picture stage — Media3's player panel with its own
 * controls, the filters' stage routed into it — its window (immersive, the camera cutout, rotation, picture in
 * picture, the screen kept on), its media session and notification, and what only a phone does: on the bare picture a
 * double tap jumps 15 s, vertical swipes set brightness (left) and volume (right), two fingers zoom; a held skip steps
 * further; a child lock; with an external display attached the video goes there while the phone keeps the controls,
 * and darkens. Back shrinks it into a corner, playing on.
 */
@Composable
fun PhonePlayer(
    queue: List<Playable>,
    startIndex: Int,
    store: StateStore,
    autoAdvance: Boolean,
    onExit: () -> Unit,
    demo: Boolean = false,
    modifier: Modifier = Modifier.fillMaxSize(),
    small: Boolean = false,
    onShrink: () -> Unit = onExit,
    onGrow: () -> Unit = {},
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
    // In the corner the menus around it turn as they like; the player's rotation holds only full screen.
    DisposableEffect(rotation, small) {
        rotationActivity?.requestedOrientation = if (small) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            rotation.orientation
        }
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            rotationActivity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    // The shared screen's state, made once the engine is; the engine's knobs read it as it plays.
    val shared = remember { arrayOfNulls<PlayerScreenState>(1) }
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
    // The knobs the engine reads as it plays, answered from this screen's live state (see PlaybackTuning).
    val tuning = remember {
        object : com.hawkynt.mediadroid.engine.PlaybackTuning {
            override val loudnessDb get() = settings.loudness + loudnessBoostDb
            override val nightMode get() = nightMode
            override val normalizeLoudness get() = settings.evenLoudness
            override val audioDelayMs get() = audioDelayMs
            override val subtitleDelayMs get() = shared[0]?.subtitleLook?.delayMs ?: 0
        }
    }
    val engine = remember {
        com.hawkynt.mediadroid.player.AndroidPlaybackEngine(
            context,
            com.hawkynt.mediadroid.player.AndroidPlaybackEngine.Options(
                softwareVideoDecoding = settings.softwareVideoDecoding,
                audioPassthrough = settings.audioPassthrough,
                bufferUnit = settings.bufferUnit,
                bufferValue = settings.bufferValue,
            ),
            tuning,
            files = { com.hawkynt.mediadroid.data.SourceAccess.resolve(context, it) },
            // A filtered picture runs a few frames behind; counted by the video's own frame rate.
            pictureLatencyMs = { frameRate ->
                PlaybackMath.pictureLatencyMs(
                    com.hawkynt.mediadroid.player.VideoFilterStage.latencyFrames,
                    frameRate,
                    com.hawkynt.mediadroid.player.VideoFilterStage.latencyMs,
                )
            },
            placeholders = demo,
        )
    }
    // The queue, the video's picture and look, what is open over the player — the same on both platforms.
    val state = remember {
        PlayerScreenState(queue, startIndex, store, engine, autoAdvance, demo = demo).also { shared[0] = it }
    }
    val session = state.session
    // The video's frame rate, for the sound's wait behind a filtered picture; kept current by the routing loop.
    val videoFrameRate = engine.videoFrameRate
    // Every frame equally long on screen: the film's rate times the chosen speed decides the display mode (TV boxes,
    // "Match the refresh rate") and the small speed change of Even frames, together (see PlaybackMath.evenPlayback).
    val displayActivity = remember(context) { context.findActivity() }
    var judderFactor by remember { mutableFloatStateOf(1f) }
    // False while no even rate is within reach (see PlaybackMath.smoothedWhenUneven).
    var evenReachable by remember { mutableStateOf(true) }
    DisposableEffect(engine) {
        onDispose {
            displayActivity?.window?.let { it.attributes = it.attributes.apply { preferredDisplayModeId = 0 } }
        }
    }
    // Which decoders are at work, as Media3 reports them, for the info sheet.
    var videoDecoder by remember { mutableStateOf<String?>(null) }
    var audioDecoder by remember { mutableStateOf<String?>(null) }
    val statistics = state.statistics
    // The player panel opens its shutter only for a first frame drawn into its own surface; one drawn by the filters'
    // stage from FFmpeg's planes is told only by the engine's first-frame event, so the shutter is opened from here.
    val openShutter = remember { arrayOfNulls<() -> Unit>(1) }
    // The routing of the picture (see routeVideo), asked at once when a decoder starts: FFmpeg's must reach the
    // filters' stage before its first pictures, or they go through the slow window path, late, and are dropped.
    val routeAtOnce = remember { arrayOfNulls<() -> Unit>(1) }
    DisposableEffect(engine) {
        val listener = object : PlaybackEngine.Listener {
            override fun onFirstFrame() {
                openShutter[0]?.invoke()
            }

            override fun onDecoder(kind: Track.Kind, name: String) {
                if (kind == Track.Kind.VIDEO) {
                    videoDecoder = name
                    routeAtOnce[0]?.invoke()
                } else {
                    audioDecoder = name
                }
            }
        }
        engine.addListener(listener)
        onDispose { engine.removeListener(listener) }
    }
    // A film's sound: other players pause for it, it pauses for a call; spatialized only when switched on.
    LaunchedEffect(spatialAudio) { engine.spatialAudio = spatialAudio }
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
    DisposableEffect(engine) {
        fun publish() {
            PlayerWindow.playing = engine.isPlaying
            val size = engine.videoFormat
            PlayerWindow.aspect = PlaybackMath.pipAspect(size?.width ?: 0, size?.height ?: 0, size?.pixelAspect ?: 1f)
                ?.let { (w, h) -> android.util.Rational(w, h) }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                runCatching { pipActivity?.setPictureInPictureParams(PlayerWindow.params()) }
            }
        }
        val listener = object : PlaybackEngine.Listener {
            override fun onPlayingChanged(playing: Boolean) = publish()
            override fun onVideoSize(width: Int, height: Int, pixelAspect: Float) = publish()
        }
        engine.addListener(listener)
        val lifecycle = (pipActivity as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        val stopObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                // Android's own screen asking to allow locking is not leaving the player.
                if (!askingToLock) leavePlayer()
                if (!PlayerWindow.elsewhere) engine.pause()
            }
        }
        lifecycle?.addObserver(stopObserver)
        onDispose {
            engine.removeListener(listener)
            lifecycle?.removeObserver(stopObserver)
            PlayerWindow.playing = false
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                runCatching { pipActivity?.setPictureInPictureParams(PlayerWindow.params()) }
            }
        }
    }
    // Small in the corner (#417) the player is what picture in picture makes it: the picture alone, no controls.
    val inPip = PlayerWindow.inPictureInPicture || small

    val index = session.index
    val speed = state.speed
    // Checked again when the speed changes and every few seconds: a new video, a switched mode, a second screen.
    LaunchedEffect(engine, speed) {
        while (true) {
            val frameRate = engine.frameRate
            val window = displayActivity?.window
            val display = window?.decorView?.display
            val onOtherScreen = PlayerWindow.displayRefreshHz
            val current = onOtherScreen ?: display?.mode?.refreshRate ?: display?.refreshRate ?: 0f
            val sameSize = display?.supportedModes.orEmpty().filter {
                it.physicalWidth == display?.mode?.physicalWidth && it.physicalHeight == display.mode.physicalHeight
            }
            val direction = runCatching { PlaybackMath.JudderDirection.valueOf(settings.judderDirection) }
                .getOrDefault(PlaybackMath.JudderDirection.NEAREST)
            val plan = PlaybackMath.evenPlayback(
                frameRate,
                speed,
                sameSize.map { it.refreshRate },
                current,
                canSwitch = settings.matchRefreshRate && onOtherScreen == null,
                maxChange = settings.judderLimitPercent / 100f,
                direction = direction,
            )
            if (window != null && settings.matchRefreshRate && onOtherScreen == null && plan.refreshHz != current) {
                sameSize.firstOrNull { it.refreshRate == plan.refreshHz }?.let { mode ->
                    window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
                }
            }
            judderFactor = plan.speedFactor
            evenReachable = plan.even
            delay(3000)
        }
    }
    var controlsVisible by remember { mutableStateOf(false) }
    // Per video: its own picture, else its folder's, else the household's (PlayerScreenState).
    val picture = state.picture
    var playerView by remember { mutableStateOf<PlayerPanel?>(null) }
    LaunchedEffect(inPip) {
        if (inPip) playerView?.hideControls()
        playerView?.controlsEnabled = !inPip
    }
    val fit = state.fit
    // The part of the picture's width and height left once the stage has cropped its black bars; 1 and 1 without.
    var cropShare by remember { mutableStateOf(1f to 1f) }
    // Burn-in care on an OLED screen (BurnInDrift): the drift's step, counted from the player's start.
    var driftStep by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            driftStep = elapsed / BurnInDrift.STEP_MS
            delay(BurnInDrift.STEP_MS - elapsed % BurnInDrift.STEP_MS)
        }
    }
    // Where the picture and the overlays have drifted to on this view's screen; nowhere unless it is an OLED one.
    fun PlayerPanel.drift(): Offset {
        if (!store.screenSetup(screenKey(display)).burnInCare) return Offset.Zero
        val (x, y) = BurnInDrift.offset(driftStep * BurnInDrift.STEP_MS, BurnInDrift.amplitude(screenHeight))
        return Offset(x.toFloat(), y.toFloat())
    }
    // How the stage bends the picture's geometry (panoramic, a projector's); none while it does not draw it.
    var warpShown by remember { mutableStateOf(ScreenWarp.None) }
    // The view's frame in the picture's shape: cropped, as this video's shape is set, for the screen it is on.
    fun PlayerPanel.showShape(video: VideoFormat?) =
        applyCropShape(video, cropShare, picture.aspect, store.screenSetup(screenKey(display)).shape, warpShown)
    // What the stage's last pass does to the picture on this view's screen: Panoramic's widening, the screen's
    // mirror and flip — and with a keystone the whole screen, the picture placed on it as its size puts it.
    fun PlayerPanel.warpFor(video: VideoFormat?): ScreenWarp {
        val setup = store.screenSetup(screenKey(display))
        val drawn = drawnAspect(video, cropShare, picture.aspect, setup.shape) ?: return ScreenWarp.None
        val panorama = if (fit == PictureFit.PANORAMIC) PictureLayout.panorama(drawn, panelAspect) else 1f
        val w = screenWidth
        val h = screenHeight
        if (!setup.keystoned || w <= 0 || h <= 0) return ScreenWarp.of(setup, panorama = panorama)
        val (pictureWidth, pictureHeight) = PictureLayout.size(fit, drawn, w, h)
        return ScreenWarp.of(setup, ScreenRect.centred(pictureWidth, pictureHeight, w, h), panorama)
    }
    // Where the picture is drawn on this view when a keystone has the stage draw the whole screen; null otherwise.
    fun PlayerPanel.placeFor(video: VideoFormat?): ScreenRect? {
        val setup = store.screenSetup(screenKey(display))
        if (!setup.keystoned || !warpShown.wholeScreen) return null
        val drawn = drawnAspect(video, cropShare, picture.aspect, setup.shape) ?: return null
        val w = screenWidth
        val h = screenHeight
        if (w <= 0 || h <= 0) return null
        val (pictureWidth, pictureHeight) = PictureLayout.size(fit, drawn, w, h)
        return ScreenRect.centred(pictureWidth, pictureHeight, w, h)
    }
    // The stage's crop itself and the size of the picture it is part of, for the ASS subtitles laid out on the whole
    // video (AssOverlayView); null without.
    val assCrop = remember {
        arrayOfNulls<Pair<com.hawkynt.mediadroid.player.PictureCrop, com.hawkynt.mediadroid.player.PictureSize>>(1)
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // Short-lived on-screen confirmation of a gesture: "+15 s", "Volume 40 %" — in the accent colour past the maximum.
    fun show(text: String, boosted: Boolean = false) = state.show(text, boosted)
    // Holding a skip button (on screen or on a remote) steps further the longer it is held; the target
    // shows while holding and letting go seeks there once.
    val holdScope = androidx.compose.runtime.rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var holdTarget by remember { mutableStateOf<Long?>(null) }
    fun startHold(forward: Boolean) {
        holdJob?.cancel()
        val startedAt = android.os.SystemClock.uptimeMillis()
        var target = engine.positionMs
        holdJob = holdScope.launch {
            while (true) {
                val step = PlaybackMath.holdStepMs(android.os.SystemClock.uptimeMillis() - startedAt)
                val end = engine.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
                target = (target + if (forward) step else -step).coerceIn(0L, end)
                holdTarget = target
                show((if (forward) "» " else "« ") + clock(target))
                playerView?.showControls()
                delay(PlaybackMath.HOLD_REPEAT_MS)
            }
        }
    }
    fun endHold() {
        holdJob?.cancel()
        holdJob = null
        holdTarget?.let { engine.seekTo(it) }
        holdTarget = null
    }
    // Seek-bar thumbnails: the finger on the bar, and the current video's pictures, opened once per video.
    var scrub by remember { mutableStateOf<Scrub?>(null) }
    val seekPreviews = remember(index) {
        if (demo) null else com.hawkynt.mediadroid.data.SeekPreviews.of(context).open(queue[index].videoPath)
    }
    DisposableEffect(seekPreviews) { onDispose { seekPreviews?.close() } }

    // The file's own chapters (Chapters), marked on the time bar.
    val chapters = session.chapters
    val externalDisplay = rememberExternalDisplay()
    var onSecondScreen by remember { mutableStateOf(false) }
    var presentation by remember { mutableStateOf<VideoPresentation?>(null) }
    DisposableEffect(engine) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val listener = object : PlaybackEngine.Listener {
            // After the player views have given their frames the video's own shape, the crop's shape again.
            override fun onVideoSize(width: Int, height: Int, pixelAspect: Float) {
                main.post {
                    listOfNotNull(playerView, presentation?.takeIf { it.isShowing }?.playerView)
                        .forEach { it.showShape(engine.videoFormat) }
                }
            }
        }
        engine.addListener(listener)
        onDispose { engine.removeListener(listener) }
    }
    openShutter[0] = {
        listOfNotNull(playerView, presentation?.takeIf { it.isShowing }?.playerView).forEach { it.openShutter() }
    }

    // Touching one of our own controls must keep Media3's controls up too, or they would time
    // out mid-choice: both are one set of controls to the viewer.
    fun keepControls() {
        playerView?.showControls()
    }

    // Immersive while watching: no status or navigation bar over the picture; a swipe from the
    // edge shows them for a moment, leaving the player puts them back. Small in the corner the menus have their bars;
    // the screen stays on either way while it plays.
    val hostView = LocalView.current
    DisposableEffect(hostView, small) {
        val window = hostView.context.findActivity()?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, hostView) }
        if (!small) {
            insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets?.hide(WindowInsetsCompat.Type.systemBars())
        }
        val cutoutBefore =
            if (android.os.Build.VERSION.SDK_INT >= 28) window?.attributes?.layoutInDisplayCutoutMode else null
        hostView.keepScreenOn = true
        onDispose {
            if (android.os.Build.VERSION.SDK_INT >= 28 && cutoutBefore != null) {
                window?.attributes = window?.attributes?.apply { layoutInDisplayCutoutMode = cutoutBefore }
            }
            window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
            insets?.show(WindowInsetsCompat.Type.systemBars())
            hostView.keepScreenOn = false
        }
    }

    // Each video opened as the shared screen asks (PlayerPlatform.open): on the main thread, which is the only one
    // ExoPlayer answers; the picture routed before it starts. The demo library's videos are placeholders: the screen
    // shows its chrome without playing them.
    suspend fun openOnMain(request: com.hawkynt.mediadroid.engine.MediaRequest) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            engine.open(request, autoPlay = false)
            if (!demo) {
                routeAtOnce[0]?.invoke()
                engine.play()
            }
        }

    // Next and Previous on the controls move through the queue (see QueuePlayer).
    val queuePlayer = remember {
        PlaybackQueue(
            engine,
            hasNext = { session.hasNext },
            onNext = { session.next() },
            onPrevious = { session.previous() },
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
        val controls = queuePlayer.publish(
            context,
            id = "mediadroid-player",
            smallIcon = R.drawable.ic_movie,
            open = android.app.PendingIntent.getActivity(
                context,
                0,
                android.content.Intent(context, com.hawkynt.mediadroid.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            ),
            title = { session.current.title },
            subtitle = { session.current.subtitle },
            posterPath = { session.current.posterPath },
            onPosted = { com.hawkynt.mediadroid.player.PlaybackService.latest = it },
        )
        onDispose { controls.close() }
    }

    // Child lock: while locked every touch and key but volume is ignored; holding the lock symbol unlocks.
    var locked by remember { mutableStateOf(false) }
    var lockHintStamp by remember { mutableIntStateOf(0) }
    // A device decoder that cannot attach to the picture's surface gets a fresh one, once per item until a
    // frame shows (see routeVideo below for how); the item it was tried for.
    val freshSurface = remember { arrayOfNulls<() -> Unit>(1) }
    val freshSurfaceFor = remember { arrayOfNulls<String>(1) }

    // The end, seeks and tracks are the shared screen's; what goes wrong with the phone's decoders is the phone's.
    // The engine closes after the shared screen has kept the progress (it leaves the composition first).
    DisposableEffect(engine) {
        val listener = object : PlaybackEngine.Listener {
            override fun onError(code: String) {
                val item = engine.openedPath
                // After a file the bundled FFmpeg decoded, its software renderer still holds the surface as a
                // CPU producer, and the device's decoder for the next file cannot attach: a fresh surface over
                // the same picture, and the item loaded afresh — which lets the software decoder go, and the
                // old surface with it — to play on from where it was.
                val renew = freshSurface[0]
                if (code == PlaybackEngine.Errors.DECODER_INIT_FAILED &&
                    item != null && freshSurfaceFor[0] != item && renew != null
                ) {
                    freshSurfaceFor[0] = item
                    renew()
                    engine.reload()
                    return
                }
                // An audio file beside the video that cannot go along with it (one that cannot seek, say)
                // must not stop the film: it plays again without them, from where it was.
                if (item != null && engine.sidecarAudio) {
                    state.sidecars =
                        state.sidecars.filter { it.first.kind != com.hawkynt.mediadroid.library.Sidecars.Kind.AUDIO }
                    engine.reload(withoutSidecarAudio = true)
                    return
                }
                state.error = code
            }

            override fun onFirstFrame() {
                freshSurfaceFor[0] = null
            }
        }
        engine.addListener(listener)
        onDispose {
            engine.removeListener(listener)
            engine.close()
        }
    }

    // The chapters marked on the time bar.
    LaunchedEffect(chapters, playerView) {
        val starts = chapters.map { it.startMs }.filter { it > 0 }.sorted().toLongArray()
        playerView?.markChapters(starts)
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
            // A window fitting the system's insets stays out of the cutout, whatever its mode.
            WindowCompat.setDecorFitsSystemWindows(window, !useCutout)
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
                // The darkened phone stays dark: past the maximum the gamma still brightens the other screen.
                if (!phoneDark) activityWindow?.let { window ->
                    window.attributes = window.attributes.apply {
                        screenBrightness = brightness.coerceIn(0.01f, 1f)
                    }
                }
                show("Brightness ${PlaybackMath.percent(brightness)} %", PlaybackMath.isBoosted(brightness))
            }
            Side.RIGHT -> {
                volume = PlaybackMath.boostedLevel(volume, upFraction)
                loudnessBoostDb = PlaybackMath.boostDb(volume)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume.coerceAtMost(1f) * maxVolume).roundToInt(), 0)
                show("Volume ${PlaybackMath.percent(volume)} %", PlaybackMath.isBoosted(volume))
            }
        }
    }

    // Over a player view's picture: the ASS subtitles libass draws, first and second, and the second track's text at
    // the top.
    fun attachSubtitles(view: PlayerPanel) =
        view.showSubtitles(engine, crop = { assCrop[0] }, place = { view.placeFor(engine.videoFormat) })

    // The video on the external display while the phone keeps the controls; back to the phone
    // when switched off or when the display goes away.
    DisposableEffect(onSecondScreen, externalDisplay?.displayId) {
        val display = externalDisplay
        if (onSecondScreen && display != null) {
            presentation = VideoPresentation(context, display, engine).also {
                it.show()
                attachSubtitles(it.playerView)
            }
            darkPhone.secondScreen(true)
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
                playerView?.showPicture(true)
                playerView?.show(null)
                playerView?.show(engine, queuePlayer)
            }
        }
    }

    // With the film on the other screen the phone goes dark by itself once the controls hide — black and empty
    // at the lowest brightness, nothing left on it to burn in — until a touch lights it up again.
    val filmElsewhere = presentation?.isShowing == true
    // On the other screen or in picture-in-picture the film plays on whatever other apps play (#458).
    LaunchedEffect(filmElsewhere, inPip) { engine.playsOnForOthers = filmElsewhere || inPip }
    // What a gesture set shows on the other screen too, where the viewer looks (#459).
    LaunchedEffect(state.feedback, state.feedbackBoosted, presentation) {
        presentation?.takeIf { it.isShowing }?.note(state.feedback, state.feedbackBoosted)
    }
    LaunchedEffect(controlsVisible, state.panelOpen, filmElsewhere, askingToLock) {
        if (filmElsewhere && !controlsVisible && !state.panelOpen && !askingToLock && !small) {
            darkPhone.controlsHidden()
            if (darkPhone.dark) phoneDark = true
        }
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
        if (phoneDark) playerView?.hideControls()
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

    // Back shrinks the player into the corner, playing on, the menus usable beside it (#417); its own close button
    // there, or the end of the queue, ends it.
    BackHandler(enabled = !small) {
        when {
            locked -> lockHintStamp++
            state.panelOpen -> state.panelOpen = false
            else -> onShrink()
        }
    }
    LaunchedEffect(small) {
        if (small) {
            // Lit again, not locked: the household is going to use the phone.
            darkPhone.touch()
            phoneDark = false
        }
    }
    // In the corner there is no "All done" to press: the queue's end ends it; an error is shown full screen.
    LaunchedEffect(small, session.finished) {
        if (small && session.finished) {
            leavePlayer()
            onExit()
        }
    }
    LaunchedEffect(small, state.error) { if (small && state.error != null) onGrow() }

    // The video's own picture correction, with the brightness swipe's gamma on top once past the maximum.
    val reducingJudder = settings.judderLimitPercent > 0 || settings.matchRefreshRate
    val shownPicture = com.hawkynt.mediadroid.player.PlaybackMath.smoothedWhenUneven(
        picture,
        com.hawkynt.mediadroid.player.PlaybackMath.EvenPlan(0f, judderFactor, evenReachable),
        reducingJudder,
    ).copy(gamma = picture.gamma * gammaBoost).let {
        // A neural upscaler saved for a video plays as Standard where this device has not passed its test.
        val neuralSaved = it.upscaler == com.hawkynt.mediadroid.player.PictureAdjustment.Upscaler.NEURAL
        val rifeSaved = it.interpolation.isNeural
        when {
            neuralSaved && !settings.neuralUpscaling ||
                it.upscaler.isEnlarger &&
                    com.hawkynt.mediadroid.player.NeuralEnlarging.choice(it) !in settings.neuralEnlargersOffered ->
                it.copy(upscaler = com.hawkynt.mediadroid.player.PictureAdjustment.Upscaler.STANDARD)
            else -> it
        }.let { p ->
            // A neural network saved for a video plays without smooth motion once the household switched it off.
            if (rifeSaved && it.interpolation !in settings.neuralMotionOffered) {
                p.copy(interpolation = com.hawkynt.mediadroid.player.PictureAdjustment.Interpolation.OFF)
            } else {
                p
            }
        }
    }
    // The picture filters' GL stage (see VideoFilterStage): the player draws into it while a filter is set, and for
    // HDR on a screen without HDR, which the stage tone-maps — HDR on a screen that shows it goes to the screen as it
    // is, unfiltered — and it draws into the view that shows the picture, on the phone or the second screen;
    // otherwise the player draws straight into that view.
    val filterStage = remember { arrayOfNulls<com.hawkynt.mediadroid.player.VideoFilterStage>(1) }
    val routedTo = remember { arrayOfNulls<android.view.SurfaceView>(1) }
    // A SurfaceView's surface goes when the view leaves the screen and comes anew: the stage lets go of it before
    // it is destroyed (setOutput returns once it has), and the picture is routed again into the new one.
    val watchedViews = remember { java.util.WeakHashMap<android.view.SurfaceView, Unit>() }
    fun watch(view: android.view.SurfaceView) {
        if (watchedViews.containsKey(view)) return
        watchedViews[view] = Unit
        view.holder.addCallback(
            object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                    routeAtOnce[0]?.invoke()
                }

                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) = Unit

                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                    if (routedTo[0] === view) {
                        filterStage[0]?.setOutput(null)
                        routedTo[0] = null
                    }
                }
            },
        )
    }
    fun routeVideo() {
        // The demo library's videos never play: no stage, no routing.
        if (demo) return
        val target = (presentation?.takeIf { it.isShowing }?.playerView ?: playerView)?.surfaceView
        target?.let { watch(it) }
        val format = engine.videoFormat
        videoFrameRate[0] = engine.frameRate.takeIf { it > 0f } ?: -1f
        val transfer = when (format?.transfer) {
            VideoFormat.Transfer.PQ -> com.hawkynt.mediadroid.player.ToneMap.Transfer.PQ
            VideoFormat.Transfer.HLG -> com.hawkynt.mediadroid.player.ToneMap.Transfer.HLG
            else -> null
        }
        // FFmpeg's pictures always go through the stage: it takes them as YUV planes, which spares the
        // decoder a window's buffer queue (see VideoFilterStage.setOutputBuffer). FFmpeg's renderer is pointed
        // at the stage before it starts, on every round — any new surface given to the player points it back —
        // so even its first pictures never take the slow window path; idle while a device decoder plays.
        val ffmpegVideo = videoDecoder?.startsWith("ffmpeg", ignoreCase = true) == true
        // HDR is tone-mapped in the stage where the screen cannot show it, unless the household leaves it to the
        // device; FFmpeg's HDR pictures have no device to leave it to.
        val toneMapped = transfer != null &&
            (ffmpegVideo || settings.hdrToneMapping && target?.display?.isHdr != true)
        // Panoramic, a projector's mirror, flip or keystone: the stage bends the picture's geometry.
        val bend = (presentation?.takeIf { it.isShowing }?.playerView ?: playerView)
            ?.warpFor(format) ?: ScreenWarp.None
        val wanted =
            (ffmpegVideo || toneMapped ||
                ((shownPicture.hasFilters || !bend.isIdentity || state.histogramShown) && transfer == null)) &&
                target != null && target.holder.surface.isValid
        val stage = filterStage[0] ?: com.hawkynt.mediadroid.player.VideoFilterStage().also { made ->
            filterStage[0] = made
            // A changed crop reshapes the view at once, not on the next round.
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            made.onCrop = { _, _ -> main.post { routeAtOnce[0]?.invoke() } }
        }
        engine.planesTo(stage)
        if (wanted) {
            val active = stage
            active.statistics = if (state.statisticsShown) statistics else null
            // Counted on the stage's thread from its small sample; shown on the main one.
            active.onScreenSample = if (state.histogramShown) {
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                val take: (com.hawkynt.mediadroid.player.VideoFilterStage.ReadBack) -> Unit = { sample ->
                    val counted = com.hawkynt.mediadroid.player.Histogram.of(
                        sample.rgba, sample.width, sample.height, step = 1,
                    )
                    main.post { state.histogram = counted }
                }
                take
            } else {
                null
            }
            active.appContext = context.applicationContext
            active.neuralMotion = settings.neuralMotionOffered.associateWith { settings.neuralMotionPassed(it)!! }
            active.neuralEnlargers =
                settings.neuralEnlargersOffered.associateWith { settings.neuralEnlargerPassed(it)!! }
            active.colorizer = settings.colorizerPassed
            active.neuralUnit = if (settings.neuralUpscaling) {
                runCatching {
                    com.hawkynt.mediadroid.player.NeuralUpscaler.Unit.valueOf(settings.neuralUnit)
                }.getOrNull()
            } else {
                null
            }
            active.filters = shownPicture
            active.warp = bend
            active.hdr = transfer.takeIf { toneMapped }
            active.sourceBits = format?.bitDepth ?: 8
            active.downscaler = runCatching { com.hawkynt.mediadroid.player.Downscaler.valueOf(settings.downscaler) }
                .getOrDefault(com.hawkynt.mediadroid.player.Downscaler.DEFAULT)
            active.setVideoSize(format?.width ?: 0, format?.height ?: 0)
            if (routedTo[0] !== target) {
                engine.videoOutput = AndroidPlaybackEngine.SurfaceOutput(active.inputSurface)
                engine.planesTo(active)
                // Taken on a later round when the view's surface is not free yet.
                if (active.setOutput(target!!.holder.surface)) routedTo[0] = target
            }
        } else if (routedTo[0] != null) {
            stage.setOutput(null)
            routedTo[0] = null
            target?.let { engine.videoOutput = AndroidPlaybackEngine.SurfaceViewOutput(it) }
        }
        // Only a picture drawn by the stage is cropped, or panoramic.
        cropShare = if (routedTo[0] != null) stage.cropShare else 1f to 1f
        warpShown = if (routedTo[0] != null) stage.warp else ScreenWarp.None
        assCrop[0] = if (routedTo[0] != null) stage.cropArea else null
    }
    freshSurface[0] = {
        val stage = filterStage[0]
        val view = routedTo[0] ?: (presentation?.takeIf { it.isShowing }?.playerView ?: playerView)?.surfaceView
        if (stage != null && routedTo[0] != null) {
            stage.renewInput { engine.videoOutput = AndroidPlaybackEngine.SurfaceOutput(it) }
        } else if (view != null) {
            // The player makes its own surface over the view's, and lets the old one go.
            engine.videoOutput = null
            engine.videoOutput = AndroidPlaybackEngine.SurfaceViewOutput(view)
        }
    }
    androidx.compose.runtime.SideEffect { filterStage[0]?.filters = shownPicture }
    // Without the filters' stage, the statistics take each frame as the player releases it to the screen.
    val statisticsShown = state.statisticsShown
    DisposableEffect(engine, statisticsShown) {
        if (statisticsShown) {
            engine.onFrameReleased = { releaseTimeNs ->
                if (routedTo[0] == null) {
                    statistics.frameArrived(releaseTimeNs)
                    statistics.pictureShown(releaseTimeNs)
                }
            }
        }
        onDispose { if (statisticsShown) engine.onFrameReleased = null }
    }
    // Every video's black bars are its own.
    LaunchedEffect(index) { filterStage[0]?.forgetCrop() }
    // The loop asks the latest composition's routing, with the picture and screens as they are now.
    val routeNow by androidx.compose.runtime.rememberUpdatedState({ routeVideo() })
    routeAtOnce[0] = { routeNow() }
    LaunchedEffect(engine) {
        while (true) {
            routeNow()
            delay(400)
        }
    }
    DisposableEffect(engine) {
        onDispose {
            filterStage[0]?.setOutput(null)
            filterStage[0]?.release()
            filterStage[0] = null
        }
    }

    // A snapshot of the picture on screen, saved into Pictures and offered to share: the stage's own picture, filters
    // and all, read back from it; with the stage standing aside (no filter set, HDR on an HDR screen) the view's
    // picture copied; where neither gives one, the frame at the position decoded afresh.
    var snapshotTaken by remember {
        mutableStateOf<Pair<androidx.compose.ui.graphics.ImageBitmap, Uri?>?>(null)
    }
    var snapshotStamp by remember { mutableIntStateOf(0) }
    var snapshotFlash by remember { mutableStateOf(false) }
    fun takeSnapshot() {
        val playable = queue[index]
        val positionMs = engine.positionMs
        val name = com.hawkynt.mediadroid.player.Snapshot.fileName(
            listOfNotNull(playable.title, playable.subtitle).joinToString(" · "),
            positionMs,
        )
        val stage = filterStage[0]?.takeIf { routedTo[0] != null }
        val view = routedTo[0] ?: (presentation?.takeIf { it.isShowing }?.playerView ?: playerView)?.surfaceView
        holdScope.launch {
            snapshotFlash = true
            val bitmap = stage?.let { stagePicture(it) } ?: viewPicture(view)
                ?: com.hawkynt.mediadroid.data.ThumbnailStore.of(context).frameAt(playable.videoPath, positionMs)
            if (bitmap == null) {
                show("No picture to save")
                return@launch
            }
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.hawkynt.mediadroid.data.SnapshotSaver.save(context, bitmap, name)
                }
            }.onSuccess { uri ->
                snapshotTaken = bitmap.asImageBitmap() to uri
                snapshotStamp++
            }.onFailure { show("The picture could not be saved") }
        }
    }
    // Before Android 10 the shared Pictures folder needs the storage permission; asked for on the first snapshot.
    val askToSave = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) takeSnapshot() else show("Saving pictures needs access to storage") }
    fun askSnapshot() {
        if (com.hawkynt.mediadroid.data.SnapshotSaver.needsPermission(context)) {
            askToSave.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            takeSnapshot()
        }
    }
    LaunchedEffect(snapshotFlash) {
        if (snapshotFlash) {
            delay(SNAPSHOT_FLASH_MS)
            snapshotFlash = false
        }
    }
    LaunchedEffect(snapshotStamp) {
        if (snapshotStamp > 0) {
            delay(SNAPSHOT_SHOWN_MS)
            snapshotTaken = null
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
    // The picture may reach into the camera cutout; the controls keep clear of it.
    val safeArea = Modifier
        .padding(horizontal = insetX.dp, vertical = insetY.dp)
        .then(if (useCutout) Modifier.windowInsetsPadding(WindowInsets.displayCutout) else Modifier)
    val check = pictureCheck(context, store)
    val offeredColorizers = rememberColorizers(context, store)
    val platform = object : PlayerPlatform {
        // Media3's own controls take play, pause and seeking; a remote's keys reach them.
        override val commands = PHONE_COMMANDS
        override val subtitlesByTrack = false
        override val pictureOnly = inPip
        override val controlsVisible = controlsVisible

        // Touching one of our own controls keeps Media3's up too: both are one set of controls to the viewer.
        override fun showControls() {
            playerView?.showControls()
        }

        override fun hideControls() {
            playerView?.hideControls()
        }

        override fun fileOf(location: String) = com.hawkynt.mediadroid.data.SourceAccess.resolve(context, location)

        override suspend fun open(request: com.hawkynt.mediadroid.engine.MediaRequest) = openOnMain(request)

        override fun snapshot() = askSnapshot()

        override fun leave() {
            leavePlayer()
            onExit()
        }

        // While locked every key but volume is ignored; a remote's left and right (the controls hidden) and its
        // rewind and fast-forward keys, held, step like the on-screen buttons.
        override fun onKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
            val native = event.nativeKeyEvent
            if (locked) {
                val volumeKey = native.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                    native.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
                    native.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_MUTE
                if (!volumeKey && native.action == android.view.KeyEvent.ACTION_DOWN) lockHintStamp++
                return !volumeKey
            }
            val forward = when (native.keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> true
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> false
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (controlsVisible) null else true
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (controlsVisible) null else false
                else -> null
            } ?: return false
            return when {
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
        }

        override fun facts(): com.hawkynt.mediadroid.player.PlaybackChain.Facts {
            val mode = (presentation?.takeIf { it.isShowing }?.display ?: hostView.display)?.mode
            return playbackFacts(
                location = session.current.videoPath,
                report = engine.report,
                videoDecoder = videoDecoder,
                audioDecoder = audioDecoder,
                picture = shownPicture,
                fit = fit.label,
                zoom = zoom,
                screen = if (presentation?.isShowing == true) {
                    externalDisplay?.name ?: "the other screen"
                } else {
                    "this screen"
                },
                screenWidth = mode?.physicalWidth ?: 0,
                screenHeight = mode?.physicalHeight ?: 0,
                refreshHz = mode?.refreshRate ?: 0f,
                loudnessDb = settings.loudness + loudnessBoostDb,
                nightMode = nightMode,
                evenLoudness = settings.evenLoudness,
                replayGainDb = engine.replayGainDb,
                speed = speed,
                delayMs = audioDelayMs,
                spatial = spatialAudio,
                device = audioOutput.name,
                subtitleDelayMs = state.subtitleLook.delayMs,
                speedFactor = judderFactor,
            )
        }

        // A size chosen starts the pinch's zoom over.
        override fun onFit(fit: PictureFit) {
            zoom = 1f
            pan = Offset.Zero
        }

        override val speedFactor = judderFactor
        override val pictureCheck = check
        override val colorizers = offeredColorizers
        override val fitHint = "Pinch with two fingers to zoom; choosing a size resets the zoom."
        override val safeArea = safeArea

        // The statistics drift with the picture on an OLED screen.
        override val overlayPlace: Modifier
            get() {
                val overlayDrift = playerView?.drift() ?: Offset.Zero
                return safeArea.padding(8.dp)
                    .offset { IntOffset(overlayDrift.x.toInt(), overlayDrift.y.toInt()) }
            }

        @Composable
        override fun BoxScope.Stage() {
            // The demo library's videos are empty placeholders: their picture stands in for the video,
            // showing through the player's (then transparent) shutter beneath its controls.
            if (demo) PosterImage(queue[index].posterPath, contentScale = ContentScale.Crop)
            AndroidView(
                factory = { androidContext ->
                    PlayerPanel.inflate(androidContext).apply {
                        show(engine, queuePlayer)
                        // Start on the bare picture; a tap shows the controls, inactivity hides them.
                        setUpControls(
                            ControlsTimeoutMs,
                            onVisible = { controlsVisible = it },
                            onHoldForward = { holding -> if (holding) startHold(true) else endHold() },
                            onHoldBack = { holding -> if (holding) startHold(false) else endHold() },
                        )
                        if (demo) clearShutter()
                        onScrub { scrub = it }
                        playerView = this
                        attachSubtitles(this)
                    }.root
                },
                update = { view ->
                    val panel = playerView ?: return@AndroidView
                    panel.applySubtitleLook(engine, state.subtitleLook)
                    // While the picture is on the other screen the phone's surface would keep showing the
                    // last frame it got; it is hidden so the phone stays black behind the controls.
                    val elsewhere = presentation?.isShowing == true
                    panel.showPicture(!elsewhere)
                    if (!elsewhere) panel.applyVideoLook(state.fit, zoom, pan, panel.drift(), warpShown)
                    panel.showShape(engine.videoFormat)
                    val density = view.resources.displayMetrics.density
                    val cutout = view.rootWindowInsets
                        ?.takeIf { useCutout && android.os.Build.VERSION.SDK_INT >= 28 }
                        ?.displayCutout
                    panel.padControls(
                        (insetX * density).toInt() + (cutout?.safeInsetLeft ?: 0),
                        (insetY * density).toInt() + (cutout?.safeInsetTop ?: 0),
                        (insetX * density).toInt() + (cutout?.safeInsetRight ?: 0),
                        (insetY * density).toInt() + (cutout?.safeInsetBottom ?: 0),
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )

            presentation?.let { shown ->
                if (shown.isShowing) {
                    shown.playerView.applyVideoLook(state.fit, zoom, pan, shown.playerView.drift(), warpShown)
                    shown.playerView.showShape(engine.videoFormat)
                    shown.playerView.applySubtitleLook(engine, state.subtitleLook)
                }
            }
            // The bare picture listens for gestures; with the controls up, touches go to the controls.
            if (!controlsVisible && !state.panelOpen && !inPip) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .playerGestures(
                            onTap = { playerView?.showControls() },
                            onDoubleTap = { side ->
                                val forward = side == Side.RIGHT
                                engine.seekTo(PlaybackMath.seekBy(engine.positionMs, engine.durationMs, forward))
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
            scrub?.takeIf { controlsVisible && !inPip }?.let {
                SeekPreviewCard(it, engine.durationMs, seekPreviews, clock(it.positionMs))
            }
            // When the film will be over on the clock, on the free right end of Media3's time row.
            if (controlsVisible && !inPip) {
                var endsAt by remember { mutableStateOf<Long?>(null) }
                LaunchedEffect(Unit) {
                    while (true) {
                        endsAt = PlaybackMath.endsAtMs(
                            System.currentTimeMillis(),
                            engine.positionMs,
                            engine.durationMs,
                            engine.speed,
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
        }

        // The phone's second screen: the film there while the phone keeps the controls, and the phone darkened.
        @Composable
        override fun RowScope.TopBarButtons() {
            if (externalDisplay != null) {
                IconButton(
                    onClick = {
                        onSecondScreen = !onSecondScreen
                        keepControls()
                    },
                ) {
                    Icon(
                        painterResource(Res.drawable.ic_tv),
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
                        painterResource(Res.drawable.ic_phonelink_lock),
                        contentDescription = "Darken this phone",
                        tint = Color.White,
                    )
                }
            }
        }

        // The child lock, the sound (night mode, the output's delay, spatial audio) and the rotation.
        @Composable
        override fun ColumnScope.GearSections() {
            OutlinedButton(
                onClick = {
                    locked = true
                    state.panelOpen = false
                    playerView?.hideControls()
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
                Switch(checked = nightMode, onCheckedChange = { nightMode = it })
            }

            PanelTitle("Audio delay · ${audioOutput.name}")
            // Wraps rather than squeezes: a button pressed narrower than its word shows it a letter a line.
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                fun nudge(steps: Int) {
                    audioDelayMs = PlaybackMath.nudgeAudioDelay(audioDelayMs, steps)
                    store.saveAudioDelay(audioOutput.key, audioDelayMs)
                }
                OutlinedButton(onClick = { nudge(-1) }) {
                    Text("Sooner", fontSize = 16.sp)
                }
                Text(
                    if (audioDelayMs == 0) "in sync" else "%+d ms".format(audioDelayMs),
                    fontSize = 18.sp,
                    modifier = Modifier.widthIn(min = 90.dp),
                )
                OutlinedButton(onClick = { nudge(1) }) {
                    Text("Later", fontSize = 16.sp)
                }
                if (audioDelayMs != 0) {
                    TextButton(onClick = { nudge(-audioDelayMs / PlaybackMath.AUDIO_DELAY_STEP_MS) }) {
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

            if (spatializerAvailable) {
                PanelTitle("Spatial audio")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Surround sound through the system's spatializer, for headphones that support it.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
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
        }

        // The camera cutout, for the picture filling the screen.
        @Composable
        override fun ColumnScope.GearAfterPictureSize() {
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
                    Switch(
                        checked = useCutout,
                        onCheckedChange = {
                            useCutout = it
                            store.data.settings.useCutout = it
                            store.save()
                        },
                    )
                }
            }
        }

        @Composable
        override fun BoxScope.Overlays() {
            // A camera's flash as the picture is taken, then the picture small with a button to share it.
            AnimatedVisibility(visible = snapshotFlash, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.5f)))
            }
            snapshotTaken?.let { (picture, uri) ->
                SnapshotCard(
                    picture,
                    uri,
                    onShare = { com.hawkynt.mediadroid.data.SnapshotSaver.share(context, it) },
                    modifier = Modifier.align(Alignment.TopEnd).then(safeArea).padding(top = 72.dp, end = 16.dp),
                )
            }
            if (locked) {
                LockOverlay(stamp = lockHintStamp, onTouch = { lockHintStamp++ }, onUnlock = { locked = false })
            }
            if (small) {
                // In the corner: a tap brings the player back full screen; the cross ends it. With the film on the
                // other screen the phone shows its picture in its place, the film playing on there.
                if (presentation?.isShowing == true) {
                    // Its poster, else a still from the video itself (a file from Files has no poster), and the
                    // screen it plays on marked.
                    PosterImage(
                        queue[index].posterPath,
                        videoFallback = queue[index].videoPath,
                        contentScale = ContentScale.Crop,
                    )
                    Icon(
                        painterResource(Res.drawable.ic_tv),
                        contentDescription = "Playing on the other screen",
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(18.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                            .padding(2.dp),
                    )
                }
                Box(
                    Modifier.fillMaxSize().clickable(onClickLabel = "Back to the player") { onGrow() },
                )
                IconButton(
                    onClick = {
                        leavePlayer()
                        onExit()
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(24.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Stop playing",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // The darkened phone: black over everything; a tap lights it up and brings the controls back, the
            // picture's other gestures — volume and brightness swipes, double-tap skips — work on it in the dark.
            if (phoneDark && !small) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .playerGestures(
                            onTap = {
                                darkPhone.touch()
                                phoneDark = false
                                playerView?.showControls()
                            },
                            onDoubleTap = { side ->
                                val forward = side == Side.RIGHT
                                engine.seekTo(PlaybackMath.seekBy(engine.positionMs, engine.durationMs, forward))
                                show(if (forward) "+15 s" else "−15 s")
                            },
                            onSwipe = ::swipe,
                            onSwipeEnd = {},
                            onPinch = { _, _ -> },
                        ),
                )
            }
        }
    }
    PlayerScreen(state, platform, modifier)
}

/** The shared keys (PlayerKeys) the phone's player answers itself; Media3's controls answer the rest. */
private val PHONE_COMMANDS = setOf(
    PlayerCommand.FRAME_FORWARD,
    PlayerCommand.FRAME_BACK,
    PlayerCommand.CHAPTER_NEXT,
    PlayerCommand.CHAPTER_BACK,
    PlayerCommand.REPEAT,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** How long the flash of a snapshot lasts. */
private const val SNAPSHOT_FLASH_MS = 150L

/** How long the snapshot just taken stays on screen, with its share button. */
private const val SNAPSHOT_SHOWN_MS = 6_000L

/** How the player screen turns: with the device, or held in one orientation (sensor-flipped either way). */
private enum class Rotation(val label: String, val orientation: Int) {
    AUTO("Auto", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER),
    LANDSCAPE("Landscape", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
    PORTRAIT("Portrait", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT),
}


