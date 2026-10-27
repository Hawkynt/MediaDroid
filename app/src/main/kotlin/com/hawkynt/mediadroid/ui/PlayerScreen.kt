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
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.library.SkipRules
import com.hawkynt.mediadroid.player.LoudnessAudioProcessor
import java.io.File
import kotlinx.coroutines.delay

/** Playback speeds the player offers, slowest to fastest. */
val Speeds = listOf(0.2f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 5.0f)

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
    val player = remember {
        ExoPlayer.Builder(context)
            .setRenderersFactory(
                object : DefaultRenderersFactory(context) {
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
                        if (settings.softwareVideoDecoding) EXTENSION_RENDERER_MODE_PREFER else EXTENSION_RENDERER_MODE_ON,
                        mediaCodecSelector,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        allowedVideoJoiningTimeMs,
                        out,
                    )

                    // Real loudness fix for unnormalized streams: the settings dB
                    // is applied to the decoded PCM and re-read per buffer, so the
                    // slider takes effect mid-playback without rebuilding anything.
                    override fun buildAudioSink(
                        context: android.content.Context,
                        enableFloatOutput: Boolean,
                        enableAudioOutputPlaybackParams: Boolean,
                    ): androidx.media3.exoplayer.audio.AudioSink =
                        DefaultAudioSink.Builder(context)
                            .setEnableFloatOutput(enableFloatOutput)
                            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                            .setAudioProcessorChain(
                                DefaultAudioSink.DefaultAudioProcessorChain(
                                    LoudnessAudioProcessor { settings.loudness },
                                ),
                            )
                            .build()
                }
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER),
            )
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    context,
                    com.hawkynt.mediadroid.player.AviAudioFraming.extractors,
                ),
            )
            .build()
    }
    var index by remember { mutableStateOf(startIndex) }
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
        hostView.keepScreenOn = true
        onDispose {
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
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(settings.audioLanguage.ifEmpty { null })
            .build()
        val chapters = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.hawkynt.mediadroid.library.Chapters.read(File(playable.videoPath))
        }
        introSpanMs = if (skipping) SkipRules.introSpanMs(chapters, playable.intro) else null
        creditsStartMs = SkipRules.creditsStartMs(chapters, playable.credits)
        // Back on the main thread before touching the player: ExoPlayer only answers the thread it was built on.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(playable.videoPath))))
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

    LaunchedEffect(speed, subtitlesOn) {
        player.setPlaybackSpeed(speed)
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

    fun finishCurrent() {
        store.markWatched(queue[index].videoPath)
        if (autoAdvance && index + 1 < queue.size) index++ else finished = true
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
                playError = error.errorCodeName
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
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume) }
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
                brightness = PlaybackMath.level(brightness, upFraction)
                activityWindow?.let { window ->
                    window.attributes = window.attributes.apply { screenBrightness = brightness.coerceAtLeast(0.01f) }
                }
                show("Brightness ${(brightness * 100).roundToInt()} %")
            }
            Side.RIGHT -> {
                volume = PlaybackMath.level(volume, upFraction)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * maxVolume).roundToInt(), 0)
                show("Volume ${(volume * 100).roundToInt()} %")
            }
        }
    }

    // The video on the external display while the phone keeps the controls; back to the phone
    // when switched off or when the display goes away.
    DisposableEffect(onSecondScreen, externalDisplay?.displayId) {
        val display = externalDisplay
        if (onSecondScreen && display != null) {
            presentation = VideoPresentation(context, display, player).also { it.show() }
        } else if (onSecondScreen) {
            onSecondScreen = false
        }
        onDispose {
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

    BackHandler {
        if (panelOpen) panelOpen = false else onExit()
    }

    val current = queue[index]
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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
                    playerView = this
                }
            },
            update = { view ->
                // While the picture is on the other screen the phone's surface would keep showing the
                // last frame it got; it is hidden so the phone stays black behind the controls.
                val elsewhere = presentation?.isShowing == true
                view.videoSurfaceView?.visibility = if (elsewhere) View.INVISIBLE else View.VISIBLE
                if (!elsewhere) view.applyVideoLook(fit, zoom, pan, picture)
            },
            modifier = Modifier.fillMaxSize(),
        )

        presentation?.let { shown ->
            if (shown.isShowing) shown.playerView.applyVideoLook(fit, zoom, pan, picture)
            Text(
                "Playing on ${externalDisplay?.name ?: "the other screen"}",
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // The bare picture listens for gestures; with the controls up, touches go to the controls.
        if (!controlsVisible && !panelOpen) {
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
        feedback?.let {
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
        // Our controls ride on top, in the band Media3 leaves free, and come and go with its own.
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onExit) {
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
                    if (externalDisplay != null) {
                        IconButton(
                            onClick = {
                                onSecondScreen = !onSecondScreen
                                keepControls()
                            },
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_tv),
                                contentDescription = if (onSecondScreen) "Back to this screen" else "Play on the other screen",
                                tint = if (onSecondScreen) MaterialTheme.colorScheme.primary else Color.White,
                            )
                        }
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
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        if (audioPicker) {
            TrackDialog(
                player = player,
                trackType = C.TRACK_TYPE_AUDIO,
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
                Button(onClick = onExit) {
                    Text("All done — back to the library", fontSize = 22.sp)
                }
            }
        }
        playError?.let { code ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = onExit) {
                    Text("Cannot play this file ($code) — back to the library", fontSize = 22.sp)
                }
            }
        }
    }
}

/** Lists the audio or subtitle tracks of the current item; "Automatic" clears the pick. */
@Composable
private fun TrackDialog(
    player: Player,
    trackType: @C.TrackType Int,
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
                        val label = format.label ?: format.language ?: "Track ${i + 1}"
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
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Speeds.forEach { s ->
                FilterChip(
                    selected = speed == s,
                    onClick = { onSpeed(s) },
                    label = { Text(if (s == 1.0f) "1×" else "${s}×", fontSize = 16.sp) },
                )
            }
        }

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
