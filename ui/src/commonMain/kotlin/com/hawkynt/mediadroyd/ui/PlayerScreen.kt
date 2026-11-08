package com.hawkynt.mediadroyd.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroyd.engine.MediaRequest
import com.hawkynt.mediadroyd.engine.PlaybackEngine
import com.hawkynt.mediadroyd.engine.Track
import com.hawkynt.mediadroyd.engine.Tracks
import com.hawkynt.mediadroyd.library.Chapters
import com.hawkynt.mediadroyd.library.FsFile
import com.hawkynt.mediadroyd.library.Sidecars
import com.hawkynt.mediadroyd.player.ChapterSteps
import com.hawkynt.mediadroyd.player.PictureAdjustment
import com.hawkynt.mediadroyd.player.PictureFit
import com.hawkynt.mediadroyd.player.PlaybackChain
import com.hawkynt.mediadroyd.player.PlaybackMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the player screen needs of its platform: the picture stage it draws the video on (the phone's player panel with
 * its own controls, the desktop's frame loop with the stage's own window), the window around it (full screen, the
 * screen kept on, insets, picture in picture) and the media session, which each app keeps beside the screen; the rows
 * of the gear only one platform offers; how the platform opens a video and what it adds to the info sheet.
 */
interface PlayerPlatform {
    /** The keys (PlayerKeys) this player answers; the rest go to the platform's own controls. */
    val commands: Set<PlayerCommand>

    /**
     * Subtitles switched by picking a track — none being off, the one picked the household's language from then on —
     * as the desktop does; otherwise, as on the phone, a switch of their own beside the track picked.
     */
    val subtitlesByTrack: Boolean

    /** Only the picture shows: picture in picture, or the player small in a corner. */
    val pictureOnly: Boolean get() = false

    /** Whether the player's controls are up; the top bar comes and goes with them. */
    val controlsVisible: Boolean

    /** The controls up for a while (a choice made on them), or gone (a panel or dialog opened over them). */
    fun showControls()
    fun hideControls()

    /** The file behind [location], for its chapters and the files beside it; null where none can be had. */
    fun fileOf(location: String): FsFile?

    /** Opens [request] as the platform's engine wants it opened (on its thread), and plays it. */
    suspend fun open(request: MediaRequest)

    /** The picture on screen saved. */
    fun snapshot()

    /** Full screen on or off (F). */
    fun fullScreen() {}

    /** The top bar's back and "All done": the player left. */
    fun leave()

    /** Esc: out of full screen first where the platform has it, else the player left. */
    fun back() = leave()

    /** A key before the player's own ones (a child lock, a remote's held skip): true when it was taken. */
    fun onKey(event: KeyEvent): Boolean = false

    /** The facts the info sheet draws the chain from: this platform's decoders, screen and sound. */
    fun facts(): PlaybackChain.Facts

    /** How a track picker names [track]; null for the picker's own count. */
    fun trackName(track: Track): String? = null

    /** A picture size chosen (the phone's zoom starts over). */
    fun onFit(fit: PictureFit) {}

    /** A speed chosen: the one played, as the platform keeps it. */
    fun onSpeed(speed: Float): Float = speed

    /** What the speed is multiplied by to play (Even frames). */
    val speedFactor: Float get() = 1f

    /** Tries a choice of deinterlacing, upscaling or smooth motion before it is taken. */
    val pictureCheck: PictureTry

    /** The colourisers offered here. */
    val colorizers: List<PictureAdjustment.Colorize>

    /** Which upscalers offer their networks' models here. */
    val neuralModels: (PictureAdjustment.Upscaler) -> Boolean get() = { it.isEnlarger }

    /** Below the picture sizes: how else the picture is sized here. */
    val fitHint: String get() = ""

    /** Where the controls keep clear of (a camera cutout, a mirrored TV's crop). */
    val safeArea: Modifier get() = Modifier

    /** Where the statistics and the histogram sit over the picture (moved by burn-in care's drift). */
    val overlayPlace: Modifier

    /** What shows where no picture is drawn: black, or all but clear over a picture window of the stage's own. */
    val background: Color get() = Color.Black

    /** The whole screen's own handling: focus, a pointer moved. */
    val screenModifier: Modifier get() = Modifier

    /** The picture, beneath everything: the video drawn, its gestures, and the controls that come with it. */
    @Composable
    fun BoxScope.Stage()

    /** The platform's own controls over the picture, with the top bar (the desktop's bottom bar). */
    @Composable
    fun BoxScope.Controls() {}

    /** The top bar's buttons only this platform has (the phone's second screen). */
    @Composable
    fun RowScope.TopBarButtons() {}

    /** The gear's rows after the speed only this platform has (the phone's lock and rotation, sound). */
    @Composable
    fun ColumnScope.GearSections() {}

    /** The gear's rows after the picture sizes (the phone's camera cutout, the desktop's histogram). */
    @Composable
    fun ColumnScope.GearAfterPictureSize() {}

    /** The gear's last rows (the desktop's decoding and its screen's setup). */
    @Composable
    fun ColumnScope.GearEnd() {}

    /** Over everything: a snapshot's note, a child lock, the corner player's marks, the darkened phone. */
    @Composable
    fun BoxScope.Overlays() {}
}

/**
 * The player, the same on every platform: the picture its [platform] draws, the top bar with what plays, the gear with
 * speed, picture, sound and subtitles, the info sheet, the chapter and track pickers, confirmations, "All done" at the
 * queue's end and why a video cannot play; the keys (PlayerKeys); the queue played by the [state]'s session — each
 * video opened with its chapters and the files beside it, resumed, its progress kept, intros skipped, the sleep timer,
 * the part repeated.
 */
@Composable
fun PlayerScreen(state: PlayerScreenState, platform: PlayerPlatform, modifier: Modifier = Modifier.fillMaxSize()) {
    val session = state.session
    val engine = state.engine
    val scope = rememberCoroutineScope()

    // Each video: its picture and look, its chapters (intros and credits with them) and the files beside it, then
    // opened where it was left — never back into a known intro.
    LaunchedEffect(session.index) {
        val playable = session.current
        state.videoChanged()
        state.statistics.clear()
        val (chapters, sidecars) = withContext(Dispatchers.IO) {
            val video = platform.fileOf(playable.videoPath)
            val chapters = video?.let { runCatching { Chapters.read(it) }.getOrNull() }.orEmpty()
            val folder = video?.parentFile
            val names = folder?.listFiles()?.map { it.name }.orEmpty()
            val sidecars = if (folder == null) {
                emptyList()
            } else {
                Sidecars.of(video.name, names).map { it to folder.child(it.name).path }
            }
            chapters to sidecars
        }
        state.sidecars = sidecars
        val startMs = session.begin(chapters)
        runCatching {
            platform.open(
                PlayerScreenState.request(
                    playable.videoPath, sidecars, startMs, state.store.data.settings.audioLanguage,
                ),
            )
        }.onFailure { state.error = it.message ?: it.toString() }
    }

    // What the engine tells, taken on this screen's thread: the end, a seek, the tracks, playing or not.
    DisposableEffect(engine) {
        val listener = object : PlaybackEngine.Listener {
            override fun onEnded() {
                scope.launch { session.finishCurrent() }
            }

            // Skipping forward into the tail or the credits finishes the item as well; a seek is no frame time.
            override fun onSeeked(positionMs: Long) {
                state.statistics.interrupted()
                scope.launch { session.saveProgress(positionMs) }
            }

            override fun onTracksChanged(tracks: List<Track>) {
                scope.launch {
                    state.tracks = tracks
                    session.tracksChanged(tracks, state.householdSubtitleLanguage(platform.subtitlesByTrack))
                }
            }

            override fun onPlayingChanged(playing: Boolean) {
                if (!playing) state.statistics.interrupted()
                scope.launch { state.playing = playing }
            }
        }
        engine.addListener(listener)
        onDispose {
            engine.removeListener(listener)
            session.saveProgress()
        }
    }

    // The speed chosen, times Even frames' small change; subtitles on or off.
    LaunchedEffect(state.speed, platform.speedFactor, state.subtitlesOn) {
        engine.speed = state.speed * platform.speedFactor
        if (!platform.subtitlesByTrack) engine.subtitlesOn = state.subtitlesOn
    }

    // Intros skipped, the credits ending the video, the progress kept (PlayerSession.tick); the time played counted at
    // its speed, for whether a video is worth a resume point.
    LaunchedEffect(engine) {
        var last = System.currentTimeMillis()
        while (true) {
            delay(TICK_MS)
            val now = System.currentTimeMillis()
            session.countPlayed(now - last, state.speed)
            last = now
            session.tick()
            state.positionMs = engine.positionMs
        }
    }

    // Back to the part's start once its end is reached, checked often enough to land within a few frames.
    LaunchedEffect(session.abLoop) {
        if (!session.abLoop.repeating) return@LaunchedEffect
        while (true) {
            delay(AB_CHECK_MS)
            session.keepRepeating()
        }
    }

    // The sleep timer: the sound fades over its last seconds, then the video pauses; never kept.
    val baseVolume = remember { engine.volume }
    LaunchedEffect(session.sleepAtMs) {
        if (session.sleepAtMs == null) {
            engine.volume = baseVolume
            return@LaunchedEffect
        }
        while (true) {
            val ranOut = session.sleepTick(monotonicMs())
            engine.volume = baseVolume * session.sleepVolume
            if (ranOut) {
                state.show("Sleep timer: paused")
                break
            }
            delay(SLEEP_TICK_MS)
        }
    }

    LaunchedEffect(state.feedbackStamp) {
        delay(FEEDBACK_MS)
        state.hideFeedback()
    }

    // Only the picture: no panel or dialog stays open over it.
    LaunchedEffect(platform.pictureOnly) {
        if (platform.pictureOnly) {
            state.panelOpen = false
            state.infoOpen = false
            state.chapterPicker = false
            state.trackPicker = null
        }
    }

    fun togglePlay() = if (engine.isPlaying) engine.pause() else engine.play()

    fun jumpChapter(forward: Boolean) {
        session.jumpChapter(forward)?.let { state.show(it) }
    }

    fun changeSpeed(speed: Float) {
        state.speed = platform.onSpeed(speed)
    }

    // S: the next subtitle track, after the last one off again.
    fun nextSubtitles() {
        val text = engine.tracks.filter { it.kind == Track.Kind.TEXT }
        val at = text.indexOfFirst { it.selected }
        state.pick(Track.Kind.TEXT, text.getOrNull(at + 1)?.id, platform.subtitlesByTrack)
    }

    fun run(command: PlayerCommand) {
        when (command) {
            PlayerCommand.PLAY_PAUSE -> togglePlay()
            PlayerCommand.SEEK_FORWARD -> engine.seekTo(PlaybackMath.seekBy(engine.positionMs, engine.durationMs, true))
            PlayerCommand.SEEK_BACK -> engine.seekTo(PlaybackMath.seekBy(engine.positionMs, engine.durationMs, false))
            PlayerCommand.FULL_SCREEN -> platform.fullScreen()
            PlayerCommand.NEXT -> session.next()
            PlayerCommand.PANEL -> state.panelOpen = !state.panelOpen
            PlayerCommand.NEXT_SUBTITLES -> nextSubtitles()
            PlayerCommand.SNAPSHOT -> platform.snapshot()
            PlayerCommand.FRAME_BACK -> session.stepFrame(false)
            PlayerCommand.FRAME_FORWARD -> session.stepFrame(true)
            PlayerCommand.CHAPTER_NEXT -> jumpChapter(true)
            PlayerCommand.CHAPTER_BACK -> jumpChapter(false)
            PlayerCommand.REPEAT -> state.show(session.markRepeat())
            PlayerCommand.SLOWER -> changeSpeed(state.steppedSpeed(-1))
            PlayerCommand.FASTER -> changeSpeed(state.steppedSpeed(+1))
            PlayerCommand.LEAVE -> platform.back()
        }
    }

    val current = session.current
    val chapters = session.chapters
    Box(
        modifier = modifier
            .background(platform.background)
            .onPreviewKeyEvent { event ->
                if (platform.onKey(event)) return@onPreviewKeyEvent true
                val command = PlayerKeys.commandOf(event.key)?.takeIf { it in platform.commands }
                    ?: return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) run(command)
                true
            }
            .then(platform.screenModifier),
    ) {
        with(platform) { Stage() }
        state.feedback?.takeIf { !platform.pictureOnly }?.let {
            FeedbackNote(it, Modifier.align(Alignment.Center), accent = state.feedbackBoosted)
        }
        state.level?.takeIf { !platform.pictureOnly }?.let { SwipeLevel(it, state.levelSide) }
        // The top bar comes and goes with the platform's own controls.
        AnimatedVisibility(
            visible = platform.controlsVisible && !platform.pictureOnly,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).then(platform.safeArea),
        ) {
            PlayerTopBar(
                // What plays, and the chapter it is in.
                title = current.title + (current.subtitle?.let { "  ·  $it" } ?: "") +
                    (ChapterSteps.current(chapters, state.positionMs)?.let { "  ·  " + ChapterSteps.name(chapters, it) }
                        ?: ""),
                note = sleepNote(session),
                paused = !state.playing,
                abLoop = session.abLoop,
                hasChapters = chapters.isNotEmpty(),
                onBack = platform::leave,
                onStepFrame = { forward ->
                    session.stepFrame(forward)
                    platform.showControls()
                },
                onRepeat = {
                    state.show(session.markRepeat())
                    platform.showControls()
                },
                onChapters = {
                    state.chapterPicker = true
                    platform.hideControls()
                },
                onSnapshot = {
                    platform.snapshot()
                    platform.showControls()
                },
                onInfo = {
                    state.infoOpen = true
                    platform.hideControls()
                },
                onGear = {
                    state.panelOpen = !state.panelOpen
                    platform.hideControls()
                },
                screens = { with(platform) { TopBarButtons() } },
            )
        }
        if (!platform.pictureOnly) with(platform) { Controls() }
        if (state.panelOpen) {
            // Tapping beside the panel closes it, like the players everyone knows.
            Box(
                Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) {
                    state.panelOpen = false
                },
            )
            Gear(state, platform, Modifier.align(Alignment.CenterEnd).then(platform.safeArea))
        }
        if (state.statisticsShown || state.histogramShown) {
            // The statistics, and beneath them the histogram — on the left, beside the gear's picture controls.
            Column(Modifier.align(Alignment.TopStart).then(platform.overlayPlace)) {
                if (state.statisticsShown) StatisticsOverlay(state.statistics, Modifier)
                if (state.histogramShown) {
                    HistogramOverlay(state.histogram, Modifier.padding(top = if (state.statisticsShown) 8.dp else 0.dp))
                }
            }
        }
        Dialogs(state, platform)
        // Not over the open panel: the household is still choosing there; the prompt shows once it is closed.
        if (session.finished && !state.panelOpen && !platform.pictureOnly) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = platform::leave) { Text("All done — back to the library", fontSize = 22.sp) }
            }
        }
        state.error?.takeIf { !platform.pictureOnly }?.let { why ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = platform::leave) {
                    Text("Cannot play this file ($why) — back to the library", fontSize = 22.sp)
                }
            }
        }
        with(platform) { Overlays() }
    }
}

/** The gear: speed, picture size, this video's picture, subtitles and sound, and what only the platform offers. */
@Composable
private fun Gear(state: PlayerScreenState, platform: PlayerPlatform, modifier: Modifier) {
    val byTrack = platform.subtitlesByTrack
    GearPanel(
        speed = state.speed,
        onSpeed = { state.speed = platform.onSpeed(it) },
        fit = state.fit,
        onFit = {
            state.fit = it
            platform.onFit(it)
        },
        fitHint = platform.fitHint,
        subtitlesOn = if (byTrack) {
            state.tracks.any { it.kind == Track.Kind.TEXT && it.selected }
        } else {
            state.subtitlesOn
        },
        onSubtitles = { on ->
            if (byTrack) {
                val text = Tracks.of(state.engine.tracks, Track.Kind.TEXT)
                val chosen = if (on) {
                    Tracks.preferredText(text, state.store.data.settings.subtitleLanguage) ?: text.firstOrNull()
                } else {
                    null
                }
                state.pick(Track.Kind.TEXT, chosen?.id, byTrack = true)
            } else {
                state.subtitlesOn = on
            }
        },
        // This video's picture: live while a slider moves, kept when it is let go. The household's lives in Settings.
        picture = state.picture,
        onPicture = { state.picture = it },
        onPictureDone = state::keepPicture,
        onResetPicture = state::resetPicture,
        check = platform.pictureCheck,
        colorizers = platform.colorizers,
        neuralModels = platform.neuralModels,
        folder = state.folderLabel,
        pictureFolder = state.pictureFolderLabel,
        onUseForFolder = state::useForFolder,
        onForgetFolder = state::forgetFolder,
        onAudioTrack = { state.trackPicker = TrackPicker.SOUND },
        onSubtitleTrack = { state.trackPicker = TrackPicker.SUBTITLES },
        onSecondSubtitleTrack = { state.trackPicker = TrackPicker.SECOND },
        onClose = { state.panelOpen = false },
        modifier = modifier,
        sections = { with(platform) { GearSections() } },
        afterPictureSize = { with(platform) { GearAfterPictureSize() } },
        end = {
            SubtitleLookRows(state.subtitleLook, onLook = state::look, onReset = state::resetLook)
            SleepChoice(state.session, ::monotonicMs)
            RepeatChoice(state.session.repeat) { state.session.repeat = it }
            with(platform) { GearEnd() }
        },
    )
}

/** What is playing, the chapters and the track pickers, each over the player while open. */
@Composable
private fun Dialogs(state: PlayerScreenState, platform: PlayerPlatform) {
    val engine = state.engine
    val session = state.session
    if (state.infoOpen) {
        PlaybackInfoDialog(
            playing = session.current,
            report = engine.report,
            tracks = engine.tracks,
            videoPath = session.current.videoPath,
            graph = PlaybackChain.of(platform.facts()),
            statisticsShown = state.statisticsShown,
            onStatistics = { state.statisticsShown = it },
            onDismiss = { state.infoOpen = false },
            histogramShown = state.histogramShown,
            onHistogram = {
                state.histogramShown = it
                if (!it) state.histogram = null
            },
        )
    }
    if (state.chapterPicker) {
        ChapterDialog(
            chapters = session.chapters,
            current = ChapterSteps.current(session.chapters, engine.positionMs),
            onDismiss = { state.chapterPicker = false },
            onPick = { start ->
                engine.seekTo(start)
                state.chapterPicker = false
            },
        )
    }
    val byTrack = platform.subtitlesByTrack
    when (state.trackPicker) {
        // Sound files beside the video are merged in as sources 1, 2 …: their names say what they are.
        TrackPicker.SOUND -> {
            val soundFiles = state.sidecars.filter { it.first.kind == Sidecars.Kind.AUDIO }
            TrackDialog(
                tracks = engine.tracks,
                kind = Track.Kind.AUDIO,
                labelFor = { track ->
                    track.source.takeIf { it >= 1 }?.let { soundFiles.getOrNull(it - 1)?.first?.describe() }
                        ?: platform.trackName(track)
                },
                marked = { it.selected },
                onDismiss = { state.trackPicker = null },
                onPick = { picked ->
                    // By track, "Automatic" leaves the sound as it is.
                    if (picked != null || !byTrack) state.pick(Track.Kind.AUDIO, picked?.id, byTrack)
                    state.trackPicker = null
                },
            )
        }
        TrackPicker.SUBTITLES -> TrackDialog(
            tracks = engine.tracks,
            kind = Track.Kind.TEXT,
            labelFor = platform::trackName,
            noneLabel = if (byTrack) "Off" else "Automatic",
            marked = { byTrack && it.selected },
            onDismiss = { state.trackPicker = null },
            onPick = { picked ->
                state.pick(Track.Kind.TEXT, picked?.id, byTrack)
                state.trackPicker = null
            },
        )
        // A second track at the top: a language to learn by, or the signs beside the dialogue.
        TrackPicker.SECOND -> TrackDialog(
            tracks = Tracks.secondText(engine.tracks),
            kind = Track.Kind.TEXT,
            labelFor = platform::trackName,
            noneLabel = "None",
            marked = { it.second },
            onDismiss = { state.trackPicker = null },
            onPick = { picked ->
                engine.secondSubtitle = picked?.id
                state.trackPicker = null
            },
        )
        null -> Unit
    }
}

/** A clock that only moves forward, for the sleep timer. */
private fun monotonicMs(): Long = System.nanoTime() / 1_000_000

/** How often the session looks at the position (intros, credits, progress). */
private const val TICK_MS = 500L

/** How often a repeated part's end is looked for. */
private const val AB_CHECK_MS = 40L

/** How often the sleep timer counts down. */
private const val SLEEP_TICK_MS = 250L

/** How long a confirmation stays over the picture. */
private const val FEEDBACK_MS = 800L

/** A swipe's level bar (LevelBar) where it belongs: on the side swiped, in the middle of the height. */
@Composable
fun BoxScope.SwipeLevel(level: Float, side: Side) {
    LevelBar(
        level,
        Modifier
            .align(if (side == Side.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
            .padding(horizontal = 32.dp),
    )
}
