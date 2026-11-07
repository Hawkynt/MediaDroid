package com.hawkynt.mediadroid.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import com.hawkynt.mediadroid.ui.PlayerCommand
import com.hawkynt.mediadroid.ui.PlayerKeys
import com.hawkynt.mediadroid.ui.ChapterDialog
import com.hawkynt.mediadroid.ui.FeedbackNote
import com.hawkynt.mediadroid.ui.GearPanel
import com.hawkynt.mediadroid.ui.HistogramOverlay
import com.hawkynt.mediadroid.ui.PlaybackInfoDialog
import com.hawkynt.mediadroid.ui.SleepChoice
import com.hawkynt.mediadroid.ui.SubtitleLookRows
import com.hawkynt.mediadroid.ui.StatisticsOverlay
import com.hawkynt.mediadroid.ui.playbackFacts
import com.hawkynt.mediadroid.ui.sleepNote
import com.hawkynt.mediadroid.ui.Playable
import com.hawkynt.mediadroid.ui.PlayerSession
import com.hawkynt.mediadroid.ui.PlayerTopBar
import com.hawkynt.mediadroid.ui.RepeatChoice
import androidx.compose.material3.Button
import com.hawkynt.mediadroid.ui.TrackDialog
import com.hawkynt.mediadroid.ui.clock
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.engine.MediaRequest
import com.hawkynt.mediadroid.engine.PlaybackTuning
import com.hawkynt.mediadroid.engine.Track
import com.hawkynt.mediadroid.engine.Tracks
import com.hawkynt.mediadroid.engine.desktop.DesktopPlaybackEngine
import com.hawkynt.mediadroid.engine.desktop.MediaReader
import com.hawkynt.mediadroid.library.Chapters
import com.hawkynt.mediadroid.library.Sidecars
import com.hawkynt.mediadroid.player.AssPicture
import com.hawkynt.mediadroid.player.AutoCrop
import com.hawkynt.mediadroid.player.ChapterSteps
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.library.FolderDefaults
import com.hawkynt.mediadroid.player.BurnInDrift
import com.hawkynt.mediadroid.player.Histogram
import com.hawkynt.mediadroid.player.PictureCrop
import com.hawkynt.mediadroid.player.PictureFit
import com.hawkynt.mediadroid.player.PictureLayout
import com.hawkynt.mediadroid.player.PicturePlacement
import com.hawkynt.mediadroid.player.ReadBackDemand
import com.hawkynt.mediadroid.player.PictureShape
import com.hawkynt.mediadroid.player.ScreenRect
import com.hawkynt.mediadroid.player.ScreenWarp
import com.hawkynt.mediadroid.player.bent
import androidx.compose.ui.draw.drawWithContent
import com.hawkynt.mediadroid.player.TestPattern
import com.hawkynt.mediadroid.player.PictureTrial
import com.hawkynt.mediadroid.player.PlaybackMath
import com.hawkynt.mediadroid.player.RefreshPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The player: the picture letterboxed in the window, controls that fade, keys for everything. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(
    store: DesktopStore,
    places: DesktopLibrary,
    mediaKeys: MediaKeys,
    queue: List<Playable>,
    startIndex: Int,
    /** The next video starts when one ends: a film's parts always, episodes with binge-watching on. */
    autoAdvance: Boolean,
    fullScreen: Boolean,
    onFullScreen: () -> Unit,
    onExit: () -> Unit,
    screen: () -> OutputScreen = { OutputScreen.Unknown },
    native: NativePicture? = null,
) {
    // The sound settings the engine reads as it plays (PlaybackTuning): the household's, kept; night mode for this
    // player only, as on Android.
    var sound by remember { mutableStateOf(store.sound) }
    var nightMode by remember { mutableStateOf(false) }
    var softwareDecoding by remember { mutableStateOf(store.softwareDecoding) }
    val tuning = remember { PlaybackTuning.Mutable() }
    tuning.loudnessDb = sound.loudnessDb
    tuning.normalizeLoudness = sound.evenLoudness
    tuning.audioDelayMs = sound.audioDelayMs
    tuning.nightMode = nightMode
    val engine = remember {
        DesktopPlaybackEngine(tuning, files = places::remoteFile).apply {
            // MEDIADROID_MUTE=1: checks and documentation runs play silently.
            if (System.getenv("MEDIADROID_MUTE") == "1") volume = 0f
            speed = store.speed
            softwareDecoding = store.softwareDecoding
        }
    }
    // The queue, where each video starts and what comes after it, as the phone plays it (PlayerSession): binge-
    // watching, intros skipped and credits ending a video as Settings say, resume points, repeats.
    val session = remember {
        PlayerSession(
            queue, startIndex, store.state, engine, autoAdvance,
            skipping = store.state.data.settings.skipIntroCredits,
        )
    }
    val index = session.index
    val playing = session.current
    // The subtitle delay each video keeps, as on Android.
    // How each video's subtitles look — delay, size, raised, a box behind them — its own, else the household's (Settings).
    var subtitleLook by remember(index) { mutableStateOf(store.state.subtitleLookFor(playing.videoPath)) }
    tuning.subtitleDelayMs = subtitleLook.delayMs
    val bitmap = remember { PictureBitmap() }
    // The picture stage (deinterlacing, upscalers, colour) on the graphics card, made once per player.
    val stage = remember { com.hawkynt.mediadroid.engine.desktop.GlStage() }
    // Where the picture is shown by the stage itself (NativePicture, Presentation.NATIVE): its window under this
    // screen's see-through one, nothing read back but what a snapshot or the histogram asks for (ReadBackDemand).
    // Null where this window draws the picture read back, as before.
    val pictureWindow = remember { native?.attach(stage) }
    val demand = remember { ReadBackDemand(HISTOGRAM_MS) }
    // Another video opened: the stage's window shows nothing of the last one.
    val openedAnew = remember { BooleanArray(1) }
    // The neural networks, their files in the app-data folder's models/ (DesktopNeural), and what a choice is tried
    // with before it is taken (PictureTrial, as on Android).
    val neural = remember { com.hawkynt.mediadroid.engine.desktop.DesktopNeural(File(AppFolder.current(), "models")) }
    // Colour is offered where DDColor's files can be had: passed here before, in place, or reachable in the model
    // repository — so its chip appears by itself once they are published.
    val colorizers by androidx.compose.runtime.produceState(
        com.hawkynt.mediadroid.player.Colorizing.offered(store.colorizerPassed != null),
    ) {
        val ddcolor = com.hawkynt.mediadroid.player.NeuralModels.DDCOLOR
        val available = withContext(Dispatchers.IO) {
            store.colorizerPassed != null || neural.ready(ddcolor) ||
                com.hawkynt.mediadroid.player.ModelFolder.reachable(ddcolor)
        }
        value = com.hawkynt.mediadroid.player.Colorizing.offered(available)
    }
    val trial = remember { DesktopTrial(store, neural) }
    // The mpv user shaders in the folder picked in Settings, else the app-data folder's shaders/ (ShaderFolder), read
    // as the player opens.
    remember { ShaderFolder.load(ShaderFolder.of(store.state.data.settings.shaderFolder, AppFolder.current())) }
    // The picture this video plays with: its own (its shape and colour system with it), else its folder's, else the
    // household's (FolderDefaults), as on the phone.
    var picture by remember { mutableStateOf(store.pictureFor(playing.videoPath)) }
    // The folder or share whose picture that is, if any has one.
    var pictureFolder by remember { mutableStateOf(store.pictureFolderOf(playing.videoPath)) }
    LaunchedEffect(index) {
        picture = store.pictureFor(playing.videoPath)
        pictureFolder = store.pictureFolderOf(playing.videoPath)
    }
    // The setup of the screen the window is on (a lens, a projector).
    var screenSetup by remember { mutableStateOf(store.screenSetup(screen().key)) }
    // MEDIADROID_SHOW=panel: the picture panel open from the start (documentation and checks).
    var panel by remember { mutableStateOf(System.getenv("MEDIADROID_SHOW") == "panel") }
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    // How the picture fills the window, for this player only, as the phone's gear; and the size it is drawn at.
    var fit by remember { mutableStateOf(PictureFit.FIT) }
    // A test pattern shown instead of the video, the video paused meanwhile; null for the video.
    var pattern by remember { mutableStateOf<TestPattern?>(null) }
    // The histogram of the picture on screen, off until switched on in the panel; its last count.
    var histogramShown by remember { mutableStateOf(false) }
    var histogram by remember { mutableStateOf<Histogram?>(null) }
    // How the stage bends the picture now, for the text subtitles over it.
    var overlayWarp by remember { mutableStateOf(ScreenWarp.None) }
    // Burn-in care on an OLED screen (BurnInDrift): the drift's step, counted from the player's start, and where the
    // picture, its subtitles and the histogram have drifted to — nowhere unless the screen is set as OLED.
    var driftStep by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            driftStep = elapsed / BurnInDrift.STEP_MS
            delay(BurnInDrift.STEP_MS - elapsed % BurnInDrift.STEP_MS)
        }
    }
    val drift = if (screenSetup.burnInCare) {
        BurnInDrift.offset(driftStep * BurnInDrift.STEP_MS, BurnInDrift.amplitude(screen().height))
    } else {
        0 to 0
    }
    LaunchedEffect(pattern) { if (pattern != null) engine.pause() }
    var shownSize by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    // The picture on screen as the stage drew it (or as decoded, without a filter), for a snapshot.
    val shownPicture = remember { arrayOfNulls<MediaReader.Picture>(1) }
    // What the last snapshot became — its file, or why there is none — shown for a few seconds.
    var snapshotNote by remember { mutableStateOf<String?>(null) }
    var snapshotNoteUntil by remember { mutableLongStateOf(0L) }
    // For the frame loop, which outlives each composition: the title and the drift as they are now.
    val currentTitle by rememberUpdatedState(playing.title)
    val currentDrift by rememberUpdatedState(drift)

    // The picture on screen saved as a PNG in the Pictures folder, its file named on screen.
    val scope = rememberCoroutineScope()
    fun saveSnapshot(shown: MediaReader.Picture) {
        // A copy: the decoder's picture is not ours to keep while the file is written.
        val still = MediaReader.Picture(shown.ptsMs, shown.width, shown.height, shown.rgba.copyOf())
        val title = currentTitle
        scope.launch {
            snapshotNote = runCatching {
                withContext(Dispatchers.IO) { FrameSnapshot.save(still, title, PicturesFolder.current()) }
            }.fold({ "Picture saved: ${it.path}" }, { "The picture could not be saved: ${it.message ?: it}" })
            snapshotNoteUntil = System.currentTimeMillis() + SNAPSHOT_NOTE_MS
        }
    }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf(emptyList<Track>()) }
    // A sound file's track named as the picker shows a sidecar: `EN`, `DE · commentary`.
    var trackPicker by remember { mutableStateOf<TrackPicker?>(null) }
    val soundNames = remember(index) {
        sidecarsOf(places, playing.videoPath).filter { it.kind == Sidecars.Kind.AUDIO }
            .associate { it.name to it.describe() }
    }
    var subtitle by remember { mutableStateOf<String?>(null) }
    // The line of the subtitle track shown second, at the top.
    var secondLine by remember { mutableStateOf<String?>(null) }
    var controlsUntil by remember { mutableLongStateOf(System.currentTimeMillis() + CONTROLS_MS) }
    // The file's own chapters, and the part repeated between two marks — as on the phone.
    val chapters = session.chapters
    var chapterPicker by remember { mutableStateOf(false) }
    // A short confirmation over the picture: a chapter's name, a repeat marked, the sleep timer run out.
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackStamp by remember { mutableIntStateOf(0) }
    fun show(text: String) {
        feedback = text
        feedbackStamp++
    }
    LaunchedEffect(feedbackStamp) {
        delay(FEEDBACK_MS)
        feedback = null
    }
    // What is playing (the info sheet), and the statistics over the picture it switches on, for this player only;
    // MEDIADROID_SHOW=info opens it from the start (documentation and checks).
    var infoOpen by remember { mutableStateOf(System.getenv("MEDIADROID_SHOW") == "info") }
    var statisticsShown by remember { mutableStateOf(false) }
    val statistics = remember { com.hawkynt.mediadroid.player.PlaybackStatistics() }
    LaunchedEffect(index) { statistics.clear() }
    // The sleep timer, as on the phone: the sound fades over its last seconds, then the video pauses.
    val baseVolume = remember { engine.volume }
    LaunchedEffect(session.sleepAtMs) {
        if (session.sleepAtMs == null) {
            engine.volume = baseVolume
            return@LaunchedEffect
        }
        while (true) {
            val ranOut = session.sleepTick(System.nanoTime() / 1_000_000)
            engine.volume = baseVolume * session.sleepVolume
            if (ranOut) {
                show("Sleep timer: paused")
                break
            }
            delay(250)
        }
    }
    val abLoop = session.abLoop
    val focus = remember { FocusRequester() }

    fun next() = session.next()

    // The video before in the queue; a few seconds in, or on the first, its start again.
    fun previous() = session.previous()

    DisposableEffect(Unit) {
        onDispose {
            session.saveProgress()
            engine.close()
            stage.close()
        }
    }
    // Intros skipped, the credits ending the video, the progress kept (PlayerSession.tick); the time played counted,
    // for whether a video is worth a resume point.
    LaunchedEffect(Unit) {
        var last = System.currentTimeMillis()
        while (true) {
            delay(500)
            val now = System.currentTimeMillis()
            session.countPlayed(now - last, engine.speed)
            last = now
            session.tick()
        }
    }
    // A subtitle picked becomes the household's language for the next videos; off stays off.
    fun pick(kind: Track.Kind, id: String?, byHand: Boolean = true) {
        if (byHand) session.manualTrackPick = true
        engine.select(kind, id)
        if (kind == Track.Kind.TEXT) {
            store.subtitleLanguage = engine.tracks.firstOrNull { it.id == id }?.language.orEmpty()
        }
    }

    LaunchedEffect(index) {
        error = null
        tracks = emptyList()
        frame = null
        shownPicture[0] = null
        openedAnew[0] = true
        val read = withContext(Dispatchers.IO) {
            runCatching { Chapters.read(places.file(playing.videoPath)) }.getOrNull()
        }.orEmpty()
        // Where it was left, never back into a known intro.
        val startMs = session.begin(read)
        // Off the window's thread: on a network source, finding the files beside it and opening it take round trips.
        runCatching {
            withContext(Dispatchers.IO) { engine.open(requestFor(places, playing.videoPath, startMs, store.state.data.settings.audioLanguage)) }
        }
            .onFailure { error = it.message ?: it.toString() }
    }
    // Film is undone in the engine, before the pictures queue up (see Film).
    LaunchedEffect(picture.deinterlace) { engine.film = picture.deinterlace == PictureAdjustment.Deinterlace.FILM }
    // A chroma upscaler, and an mpv shader hooking the planes (LUMA, CHROMA), work on the decoded planes, which the
    // engine then keeps beside each picture.
    LaunchedEffect(picture.chroma, picture.shader) {
        val planesHooked = picture.shader?.let { com.hawkynt.mediadroid.player.MpvShaders.library[it] }?.hooksPlanes
        engine.keepPlanes = picture.chroma != PictureAdjustment.Chroma.BILINEAR || planesHooked == true
    }
    // The networks the stage may run: those whose self-tests passed, as of the picture taken last.
    LaunchedEffect(picture) {
        stage.neural = com.hawkynt.mediadroid.engine.desktop.GlStage.Neural(
            neural, store.neuralMotion, store.neuralEnlargers, store.colorizerPassed,
        )
    }
    // Every window frame: the picture due now — through the stage when the household set filters, the video is HDR,
    // which the stage tone-maps, or libass draws its subtitles, which want the size it is shown at — the subtitles
    // libass draws laid over it, the clock, the end. Smooth motion draws anew at every refresh, field rate at the
    // middle of each picture's time.
    // The screen's refresh, measured from the frames asked for back to back (the frame loop below), and Even frames
    // over it (Settings): the film run a little faster or slower so every frame stays on screen equally long.
    val refresh = remember { RefreshPeriod() }
    val evenFrames = remember { EvenFrames(store.state.data.settings) }
    LaunchedEffect(Unit) {
        var lastShown: Any? = null
        var lastLook: Any? = null
        // Black bars (AutoCrop): a sample of the picture every SAMPLE_INTERVAL_MS, judged off the window's thread,
        // afresh for every video; the crop found, for pictures of the size it was found on.
        val autoCrop = AutoCrop()
        var cropVideo: String? = null
        var lastSampleMs = 0L
        var crop: PictureCrop? = null
        var staged: MediaReader.Picture? = null
        var drawn = AssPicture.EMPTY
        var drawnSecond = AssPicture.EMPTY
        // libass's subtitles bent as the stage bent the picture they go over (a projector's mirror, flip, keystone),
        // and what they were bent for.
        var shownWarp = ScreenWarp.None
        var bentFor: List<Any>? = null
        var bent = AssPicture.EMPTY
        var bentSecond = AssPicture.EMPTY
        // The part of the video the staged picture shows, for the subtitles libass lays out on the whole video.
        var shownPart: PictureCrop? = null
        var shownPlace: IntArray? = null
        var shownVisible: IntArray? = null
        // A test pattern drawn at the window's size, kept while the pattern and the size stay.
        var still: MediaReader.Picture? = null
        var histogramAtMs = 0L
        var stillOf: TestPattern? = null
        // The screen's refresh, measured from the frames asked for back to back: each picture is picked for the
        // middle of the refresh it shows at (DesktopPlaybackEngine.moment) — a refresh later where the stage hands
        // its pictures out one call late (Presentation.PBO_LAGGED, which NATIVE reads back by in this window) and the
        // last one went through it. Shown by the stage itself, a picture is swapped in at the next refresh.
        val lagged = pictureWindow == null && com.hawkynt.mediadroid.engine.desktop.Presentation.chosen().readBack ==
            com.hawkynt.mediadroid.engine.desktop.Presentation.PBO_LAGGED
        var staging = false
        // What the stage's own window was last told to show (Presentation.NATIVE).
        var sceneShown: List<Any>? = null
        while (true) {
            refresh.refreshed(withFrameNanos { it })
            val began = System.nanoTime()
            // Another video opened (its frame cleared): nothing of the last one is drawn again.
            if (pictureWindow == null && frame == null || openedAnew[0]) {
                staged = null
                openedAnew[0] = false
                pictureWindow?.scene(null)
                sceneShown = null
            }
            var changed = false
            val aheadMs = (refresh.periodNs / 2 + if (lagged && staging) refresh.periodNs else 0) / 1_000_000
            // A test pattern instead of the video: through the stage with the household's picture and the screen's
            // setup, as the films get them (TestPattern.pictureFor).
            val showing = pattern
            if (showing != null && canvas.width > 0 && canvas.height > 0 && stage.ready) {
                val setup = store.screenSetup(screen().key)
                val look = listOf(showing, canvas, picture, setup)
                if (look != lastLook) {
                    lastLook = look
                    lastShown = null
                    val pictureNow = picture
                    val w = canvas.width
                    val h = canvas.height
                    val shownPattern = still?.takeIf { it.width == w && it.height == h && stillOf == showing }
                        ?: withContext(Dispatchers.Default) { MediaReader.Picture(0, w, h, showing.render(w, h)) }
                    still = shownPattern
                    stillOf = showing
                    staged = withContext(Dispatchers.Default) {
                        val shownWith = TestPattern.pictureFor(pictureNow)
                        if (pictureWindow != null) {
                            stage.processTexture(shownPattern, shownWith, w, h, warp = ScreenWarp.of(setup))
                            MediaReader.Picture(0, w, h, SHOWN_BY_STAGE)
                        } else {
                            stage.process(shownPattern, shownWith, w, h, warp = ScreenWarp.of(setup))
                        }
                    }
                    shownSize = canvas
                    drawn = AssPicture.EMPTY
                    changed = true
                }
            } else engine.moment(aheadMs)?.let { moment ->
                // The picture as shown: smoothed where Even frames finds no even rate (EvenFrames).
                val picture = evenFrames.shown(picture)
                val raw = moment.shown
                val nowMs = System.currentTimeMillis()
                if (engine.openedPath != cropVideo || !picture.cropBlackBars || !stage.ready) {
                    cropVideo = engine.openedPath
                    autoCrop.restart()
                    crop = null
                    lastSampleMs = 0L
                }
                if (picture.cropBlackBars && stage.ready && raw !== lastShown &&
                    nowMs - lastSampleMs >= AutoCrop.SAMPLE_INTERVAL_MS
                ) {
                    lastSampleMs = nowMs
                    crop = withContext(Dispatchers.Default) {
                        val size = AutoCrop.sampleSize(raw.width, raw.height)
                        val deep = if (raw.hdr != null) 2 else 1
                        autoCrop.add(AutoCrop.luma(raw.rgba, raw.width, raw.height, deep, size))
                        autoCrop.crop(raw.width, raw.height).takeUnless { it.isWhole(raw.width, raw.height) }
                    }
                }
                val part = crop?.takeIf { it.left + it.width <= raw.width && it.top + it.height <= raw.height }
                val timing = when {
                    picture.interpolation != PictureAdjustment.Interpolation.OFF -> moment.phase
                    picture.isFieldRate -> moment.phase >= 0.5f
                    else -> null
                }
                // The screen the window is on now: a window moved onto a projector takes the projector's setup.
                val shownOn = screen()
                screenSetup = store.screenSetup(shownOn.key)
                val widening = PictureShape.widening(shownOn.aspect, screenSetup.shape)
                val pixelAspect = PictureShape.pixelAspect(raw.width, raw.height, engine.pixelAspect, picture.aspect)
                val look = listOf(picture, canvas, part, timing, pixelAspect, widening, fit)
                if (raw !== lastShown || look != lastLook) {
                    if (raw !== lastShown) {
                        val at = System.nanoTime()
                        statistics.frameArrived(at)
                        statistics.pictureShown(at)
                    }
                    lastShown = raw
                    lastLook = look
                    // Sized by the picture's true shape: non-square pixels (PAL DV, anamorphic DVD) widened, or the
                    // shape set for a file whose flag is wrong; narrowed by what the screen widens (a lens); then
                    // fitted, filled, stretched or widened panoramic into the window.
                    val drawnWidth = (part?.width ?: raw.width) * pixelAspect / widening
                    val drawnHeight = (part?.height ?: raw.height).toFloat()
                    val drawnAspect = drawnWidth / drawnHeight
                    val laidOut = canvas.width > 0 && canvas.height > 0
                    val panorama = if (fit == PictureFit.PANORAMIC && laidOut) {
                        PictureLayout.panorama(drawnAspect, canvas.width.toFloat() / canvas.height)
                    } else {
                        1f
                    }
                    val (w, h) = if (laidOut) {
                        PictureLayout.size(fit, drawnAspect, canvas.width, canvas.height)
                    } else {
                        drawnWidth.toInt().coerceAtLeast(1) to drawnHeight.toInt().coerceAtLeast(1)
                    }
                    // A projector's mirror, flip and keystone (ScreenWarp); with a keystone the stage draws the
                    // whole window, the picture placed in it as its size puts it.
                    val warp = if (screenSetup.keystoned && laidOut) {
                        ScreenWarp.of(screenSetup, ScreenRect.centred(w, h, canvas.width, canvas.height), panorama)
                    } else {
                        ScreenWarp.of(screenSetup, panorama = panorama)
                    }
                    val (outW, outH) = if (warp.wholeScreen) canvas.width to canvas.height else w to h
                    // Where the subtitles go (AssFrame): the part of the video shown, the picture's place in what the
                    // stage draws (centred on the window with a keystone), and the part of that the window shows
                    // (Fill's overhang cut).
                    shownPart = part
                    shownWarp = warp
                    overlayWarp = warp
                    shownPlace = if (warp.wholeScreen) {
                        intArrayOf((outW - w) / 2, (outH - h) / 2, w, h)
                    } else {
                        intArrayOf(0, 0, outW, outH)
                    }
                    shownVisible = if (laidOut) {
                        val left = maxOf(0, (outW - canvas.width) / 2)
                        val top = maxOf(0, (outH - canvas.height) / 2)
                        intArrayOf(left, top, minOf(outW, left + canvas.width), minOf(outH, top + canvas.height))
                    } else {
                        null
                    }
                    shownSize = IntSize(outW, outH)
                    val throughStage = picture.hasFilters || pixelAspect != 1f || widening != 1f ||
                        !warp.isIdentity || raw.hdr != null || engine.drawsSubtitles || raw.planes != null
                    staging = throughStage && stage.ready && outW > 0 && outH > 0
                    // Shown by the stage itself, every picture goes through it: it is the one that draws.
                    staged = if (pictureWindow != null) {
                        if (outW > 0 && outH > 0) {
                            withContext(Dispatchers.Default) {
                                stage.processTexture(moment, picture, outW, outH, part, warp)
                            }
                            MediaReader.Picture(raw.ptsMs, outW, outH, SHOWN_BY_STAGE)
                        } else {
                            staged
                        }
                    } else if (staging) {
                        withContext(Dispatchers.Default) { stage.process(moment, picture, outW, outH, part, warp) }
                    } else {
                        raw.eightBit()
                    }
                    changed = true
                }
            }
            // The stage hands out each picture a call late while the next is read back (Presentation.PBO_LAGGED): one
            // still being read back is collected at the next refresh, so a paused picture or a still shows too.
            if (!changed && stage.behind) {
                withContext(Dispatchers.Default) { stage.latest() }?.let {
                    staged = it
                    changed = true
                }
            }
            staged?.let { base ->
                // Off the window's thread: libass's first font lookup, or a heavy sign, takes its time.
                // The place and the part shown are for what the stage drew; a picture straight from the decoder
                // fills itself. No subtitles over a test pattern.
                val asStaged = base.width == shownSize.width && base.height == shownSize.height
                if (showing == null) {
                    withContext(Dispatchers.Default) {
                        engine.subtitlePicture(
                            base.width, base.height, shownPart,
                            shownPlace.takeIf { asStaged }, shownVisible.takeIf { asStaged },
                        )
                    }?.let {
                        drawn = it
                        changed = true
                    }
                }
                withContext(Dispatchers.Default) {
                    engine.secondSubtitlePicture(
                        base.width, base.height, shownPart,
                        shownPlace.takeIf { asStaged }, shownVisible.takeIf { asStaged },
                    )
                }?.let {
                    drawnSecond = it
                    changed = true
                }
                if (changed || pictureWindow != null) {
                    val warp = if (showing == null) shownWarp else ScreenWarp.None
                    val key = listOf(drawn, drawnSecond, warp, base.width, base.height)
                    if (key != bentFor) {
                        bentFor = key
                        bent = drawn.bent(warp, base.width, base.height)
                        bentSecond = drawnSecond.bent(warp, base.width, base.height)
                    }
                }
                if (pictureWindow != null) {
                    // Placed as this window would draw it, the subtitles over it in the stage's window; what changed
                    // only, sent on.
                    val place = PicturePlacement.shown(
                        canvas.width, canvas.height, shownSize.width, shownSize.height, base.width, base.height,
                        currentDrift.first, currentDrift.second,
                    )
                    val scene = listOf(place.toList(), base.width, base.height, bent, bentSecond)
                    if (scene != sceneShown) {
                        sceneShown = scene
                        pictureWindow.scene(
                            com.hawkynt.mediadroid.engine.desktop.PictureWindow.Scene(
                                place, base.width, base.height, listOf(bent, bentSecond),
                            ),
                        )
                    }
                    // Its pixels read back only for what asks for them: a snapshot, the histogram twice a second.
                    val wanted = demand.wanted(System.currentTimeMillis(), histogramShown)
                    if (wanted.any) {
                        withContext(Dispatchers.Default) { stage.readBackShown() }?.let { read ->
                            bent.blendInto(read.rgba, read.width, read.height)
                            bentSecond.blendInto(read.rgba, read.width, read.height)
                            shownPicture[0] = read
                            if (wanted.snapshot) saveSnapshot(read)
                            if (wanted.histogram) {
                                histogram = withContext(Dispatchers.Default) {
                                    Histogram.of(read.rgba, read.width, read.height)
                                }
                            }
                        }
                    }
                } else if (changed) {
                    val shown = if (bent.isEmpty && bentSecond.isEmpty) {
                        base
                    } else {
                        MediaReader.Picture(base.ptsMs, base.width, base.height, base.rgba.copyOf())
                            .also {
                                bent.blendInto(it.rgba, it.width, it.height)
                                bentSecond.blendInto(it.rgba, it.width, it.height)
                            }
                    }
                    shownPicture[0] = shown
                    frame = bitmap.of(shown)
                    // Counted twice a second off the window's thread, as the stage drew the picture.
                    val nowMs = System.currentTimeMillis()
                    if (histogramShown && nowMs - histogramAtMs >= HISTOGRAM_MS) {
                        histogramAtMs = nowMs
                        histogram = withContext(Dispatchers.Default) {
                            Histogram.of(shown.rgba, shown.width, shown.height)
                        }
                    }
                }
            }
            position = engine.positionMs
            duration = engine.durationMs
            session.keepRepeating()
            val wereEmpty = tracks.none { it.kind == Track.Kind.TEXT }
            tracks = engine.tracks
            // A video's subtitles start in the household's language, as on the phone.
            if (wereEmpty) session.tracksChanged(tracks, store.subtitleLanguage)
            subtitle = engine.subtitle()
            secondLine = engine.secondSubtitleLine()
            val wasPlaying = isPlaying
            isPlaying = engine.isPlaying
            // At its end: the next one, the same again, or the queue is over (PlayerSession.finishCurrent).
            if (wasPlaying && !isPlaying && duration > 0 && position >= duration - 100) session.finishCurrent()
            // Done well within a refresh: the next frame is asked for at once, so the gap to it is one refresh.
            if (System.nanoTime() - began < refresh.periodNs / 2) refresh.askedAgain()
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // The screen stays on while a video plays — from the window's thread, which Windows ties the state to.
    val keeper = remember { ScreenKeeper.forThisSystem() }
    LaunchedEffect(isPlaying) { keeper.keepOn(isPlaying) }
    DisposableEffect(keeper) { onDispose { keeper.close() } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val controlsShown = now < controlsUntil || !isPlaying

    fun togglePlay() = if (engine.isPlaying) engine.pause() else engine.play()

    // The media keys and the system's now-playing panel drive this player while it is open.
    DisposableEffect(Unit) {
        mediaKeys.onCommand = { command ->
            when (command) {
                MediaCommand.PLAY -> engine.play()
                MediaCommand.PAUSE -> engine.pause()
                MediaCommand.PLAY_PAUSE -> togglePlay()
                MediaCommand.STOP -> onExit()
                MediaCommand.NEXT -> next()
                MediaCommand.PREVIOUS -> previous()
            }
        }
        onDispose {
            mediaKeys.onCommand = {}
            mediaKeys.show(null)
        }
    }
    LaunchedEffect(index, isPlaying, duration) {
        mediaKeys.show(NowPlaying(playing.title, isPlaying, index > 0, session.hasNext, duration))
    }

    var speed by remember { mutableStateOf(store.speed) }
    fun changeSpeed(value: Float) {
        val clamped = (kotlin.math.round(value * 20f) / 20f).coerceIn(PlaybackMath.SPEED_MIN, PlaybackMath.SPEED_MAX)
        speed = clamped
        engine.speed = clamped * evenFrames.speedFactor
        store.speed = clamped
    }
    // Every few seconds, and at once for a new speed: the film's rate, the speed and the screen's refresh again.
    LaunchedEffect(speed) {
        while (true) {
            evenFrames.plan(engine.frameRate, speed, refresh.periodNs)
            engine.speed = speed * evenFrames.speedFactor
            delay(EVEN_FRAMES_MS)
        }
    }

    // The picture on screen saved as a PNG; shown by the stage itself, it is read back for that at the next frame.
    fun snapshot() {
        if (pictureWindow != null) demand.askSnapshot() else shownPicture[0]?.let(::saveSnapshot)
    }

    // , and . : one picture back or on, paused there; Page Up and Down: the next and the previous chapter (mpv's keys).
    fun stepFrame(forward: Boolean) = session.stepFrame(forward)

    fun jumpChapter(forward: Boolean) {
        session.jumpChapter(forward)?.let(::show)
    }

    // S: the next subtitle track, after the last one off again.
    fun nextSubtitles() {
        val text = engine.tracks.filter { it.kind == Track.Kind.TEXT }
        val at = text.indexOfFirst { it.selected }
        pick(Track.Kind.TEXT, text.getOrNull(at + 1)?.id)
    }

    Box(
        // Over the stage's own window the screen is see-through — all but invisibly tinted, so a window system that
        // passes clicks through clear pixels (Win32's layered windows) still hands them to the controls.
        Modifier.fillMaxSize().background(if (pictureWindow != null) SEE_THROUGH else Color.Black)
            .focusRequester(focus).focusable()
            .onPointerEvent(PointerEventType.Move) { controlsUntil = System.currentTimeMillis() + CONTROLS_MS }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                controlsUntil = System.currentTimeMillis() + CONTROLS_MS
                when (PlayerKeys.commandOf(event.key) ?: return@onKeyEvent false) {
                    PlayerCommand.PLAY_PAUSE -> togglePlay()
                    PlayerCommand.SEEK_FORWARD ->
                        engine.seekTo(PlaybackMath.seekBy(engine.positionMs, engine.durationMs, true))
                    PlayerCommand.SEEK_BACK ->
                        engine.seekTo(PlaybackMath.seekBy(engine.positionMs, engine.durationMs, false))
                    PlayerCommand.FULL_SCREEN -> onFullScreen()
                    PlayerCommand.NEXT -> next()
                    PlayerCommand.PANEL -> panel = !panel
                    PlayerCommand.NEXT_SUBTITLES -> nextSubtitles()
                    PlayerCommand.SNAPSHOT -> snapshot()
                    PlayerCommand.FRAME_BACK -> stepFrame(false)
                    PlayerCommand.FRAME_FORWARD -> stepFrame(true)
                    PlayerCommand.CHAPTER_NEXT -> jumpChapter(true)
                    PlayerCommand.CHAPTER_BACK -> jumpChapter(false)
                    PlayerCommand.REPEAT -> show(session.markRepeat())
                    PlayerCommand.SLOWER -> changeSpeed(speed - SPEED_STEP)
                    PlayerCommand.FASTER -> changeSpeed(speed + SPEED_STEP)
                    PlayerCommand.LEAVE -> if (fullScreen) onFullScreen() else onExit()
                }
                true
            },
    ) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { canvas = it }) {
            frame?.let { drawShown(it, shownSize, IntOffset(drift.first, drift.second)) }
        }
        val drifted = Modifier.offset { IntOffset(drift.first, drift.second) }
        // The text subtitles bent as the picture is (ScreenWarp.overlayMatrix): read the right way round on a
        // rear-projection screen, on the picture on a keystoned one.
        Box(
            Modifier.fillMaxSize().drawWithContent {
                val bend = overlayWarp
                if (!bend.bendsOverlays) {
                    drawContent()
                    return@drawWithContent
                }
                drawContext.canvas.save()
                drawContext.canvas.concat(composeMatrix(bend.overlayMatrix(size.width, size.height)))
                drawContent()
                drawContext.canvas.restore()
            },
        ) {
            // Sized and raised by the window's height as the phone sizes them by its screen's (SubtitleLook).
            val density = androidx.compose.ui.platform.LocalDensity.current
            val textSize = with(density) { (subtitleLook.textSizeFraction * canvas.height).toSp() }
            val above = with(density) { (subtitleLook.bottomPaddingFraction * canvas.height).toDp() }
            subtitle?.let { line ->
                SubtitleLine(
                    line,
                    subtitleLook,
                    textSize,
                    Modifier.align(Alignment.BottomCenter).then(drifted).padding(start = 48.dp, end = 48.dp,
                        bottom = if (controlsShown) maxOf(120.dp, above) else above),
                )
            }
            secondLine?.let { line ->
                SubtitleLine(
                    line,
                    subtitleLook,
                    textSize,
                    Modifier.align(Alignment.TopCenter).then(drifted)
                        .padding(start = 48.dp, end = 48.dp, top = 40.dp),
                )
            }
        }
        if (statisticsShown || histogramShown) {
            // The statistics, and beneath them the histogram — on the left, beside the gear's picture controls.
            Column(Modifier.align(Alignment.TopStart).then(drifted).padding(top = 72.dp, start = 12.dp)) {
                if (statisticsShown) StatisticsOverlay(statistics, Modifier)
                if (histogramShown) {
                    HistogramOverlay(histogram, Modifier.padding(top = if (statisticsShown) 8.dp else 0.dp))
                }
            }
        }
        feedback?.let { FeedbackNote(it, Modifier.align(Alignment.Center)) }
        if (panel) {
            // Clicking beside the panel closes it, as on the phone.
            Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { panel = false })
            val video = playing.videoPath
            GearPanel(
                speed = speed,
                onSpeed = ::changeSpeed,
                fit = fit,
                onFit = { fit = it },
                fitHint = "",
                subtitlesOn = tracks.any { it.kind == Track.Kind.TEXT && it.selected },
                onSubtitles = { on ->
                    val text = Tracks.of(engine.tracks, Track.Kind.TEXT)
                    val chosen = if (on) {
                        Tracks.preferredText(text, store.subtitleLanguage) ?: text.firstOrNull()
                    } else {
                        null
                    }
                    pick(Track.Kind.TEXT, chosen?.id)
                },
                // This video's picture, as on the phone: live while a slider moves, kept when it is let go. The
                // household's lives in Settings.
                picture = picture,
                onPicture = { picture = it },
                onPictureDone = { store.savePicture(video, picture) },
                onResetPicture = {
                    store.savePicture(video, null)
                    picture = store.pictureFor(video)
                },
                check = { candidate, say ->
                    withContext(Dispatchers.Default) { PictureTrial.check(candidate, trial, say) }
                },
                colorizers = colorizers,
                neuralModels = DesktopNeuralModels,
                folder = FolderDefaults.label(store.folderOf(video), store.allSources),
                pictureFolder = pictureFolder?.let { FolderDefaults.label(it, store.allSources) },
                onUseForFolder = {
                    store.setFolderPicture(store.folderOf(video), picture)
                    // The video follows its folder's picture now, its 3D eye and shape kept as its own.
                    store.savePicture(video, picture)
                    picture = store.pictureFor(video)
                    pictureFolder = store.pictureFolderOf(video)
                },
                onForgetFolder = {
                    pictureFolder?.let { store.setFolderPicture(it, null) }
                    picture = store.pictureFor(video)
                    pictureFolder = store.pictureFolderOf(video)
                },
                onAudioTrack = { trackPicker = TrackPicker.SOUND },
                onSubtitleTrack = { trackPicker = TrackPicker.SUBTITLES },
                onSecondSubtitleTrack = { trackPicker = TrackPicker.SECOND },
                onClose = { panel = false },
                modifier = Modifier.align(Alignment.CenterEnd),
                sections = {
                    DesktopSoundRows(
                        sound,
                        onSound = {
                            sound = it
                            store.sound = it
                        },
                        nightMode = nightMode,
                        onNightMode = { nightMode = it },
                    )
                },
                afterPictureSize = {
                    DesktopHistogramRow(histogramShown) {
                        histogramShown = it
                        if (!it) histogram = null
                    }
                },
                end = {
                    SubtitleLookRows(
                        subtitleLook,
                        onLook = {
                            subtitleLook = it
                            store.state.saveSubtitleLook(video, it)
                        },
                        onReset = {
                            store.state.saveSubtitleLook(video, null)
                            subtitleLook = store.state.subtitleLookFor(video)
                        },
                    )
                    SleepChoice(session) { System.nanoTime() / 1_000_000 }
                    RepeatChoice(session.repeat) { session.repeat = it }
                    DesktopDecodingRows(
                        softwareDecoding,
                        onSoftware = {
                            softwareDecoding = it
                            store.softwareDecoding = it
                            engine.softwareDecoding = it
                        },
                        hardwareDecoder = engine.hardwareDecoder?.label,
                    )
                    DesktopScreenRows(
                        screen().name,
                        screenSetup,
                        onSetup = {
                            screenSetup = it
                            store.setScreenSetup(screen().key, it)
                        },
                        pattern = pattern,
                        onPattern = { pattern = it },
                    )
                },
            )
        }
        if (chapterPicker) {
            ChapterDialog(
                chapters = chapters,
                current = ChapterSteps.current(chapters, engine.positionMs),
                onDismiss = { chapterPicker = false },
                onPick = { start ->
                    engine.seekTo(start)
                    chapterPicker = false
                },
            )
        }
        if (infoOpen) {
            val report = engine.report
            val shownOn = screen()
            PlaybackInfoDialog(
                playing = playing,
                report = report,
                tracks = engine.tracks,
                videoPath = playing.videoPath,
                graph = com.hawkynt.mediadroid.player.PlaybackChain.of(
                    playbackFacts(
                        location = playing.videoPath,
                        report = report,
                        videoDecoder = engine.hardwareDecoder?.label ?: "FFmpeg",
                        audioDecoder = "FFmpeg",
                        picture = picture,
                        fit = fit.label,
                        zoom = 1f,
                        screen = shownOn.name,
                        screenWidth = (shownOn.height * shownOn.aspect).toInt(),
                        screenHeight = shownOn.height,
                        refreshHz = 0f,
                        loudnessDb = sound.loudnessDb,
                        nightMode = nightMode,
                        evenLoudness = sound.evenLoudness,
                        replayGainDb = null,
                        speed = speed,
                        delayMs = sound.audioDelayMs,
                        spatial = false,
                        device = "this computer's output",
                        subtitleDelayMs = subtitleLook.delayMs,
                    ),
                ),
                statisticsShown = statisticsShown,
                onStatistics = { statisticsShown = it },
                onDismiss = { infoOpen = false },
                histogramShown = histogramShown,
                onHistogram = {
                    histogramShown = it
                    if (!it) histogram = null
                },
            )
        }
        when (trackPicker) {
            TrackPicker.SOUND -> TrackDialog(
                tracks = tracks,
                kind = Track.Kind.AUDIO,
                // A sound file's track named as the picker shows a sidecar: `EN`, `DE · commentary`.
                labelFor = { t -> if (t.source > 0) soundNames[t.label] ?: trackName(t) else trackName(t) },
                marked = { it.selected },
                onDismiss = { trackPicker = null },
                onPick = { picked ->
                    picked?.let { pick(Track.Kind.AUDIO, it.id) }
                    trackPicker = null
                },
            )
            TrackPicker.SUBTITLES -> TrackDialog(
                tracks = tracks,
                kind = Track.Kind.TEXT,
                labelFor = ::trackName,
                noneLabel = "Off",
                marked = { it.selected },
                onDismiss = { trackPicker = null },
                onPick = { picked ->
                    pick(Track.Kind.TEXT, picked?.id)
                    trackPicker = null
                },
            )
            TrackPicker.SECOND -> TrackDialog(
                tracks = Tracks.secondText(tracks),
                kind = Track.Kind.TEXT,
                labelFor = ::trackName,
                noneLabel = "None",
                marked = { it.second },
                onDismiss = { trackPicker = null },
                onPick = { picked ->
                    engine.secondSubtitle = picked?.id
                    trackPicker = null
                },
            )
            null -> Unit
        }
        snapshotNote?.takeIf { now < snapshotNoteUntil }?.let { note ->
            Text(
                note, color = Color.White, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
                    .background(Color(0xCC000000), MaterialTheme.shapes.small).padding(12.dp),
            )
        }
        // The queue is over, as on the phone.
        if (session.finished) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = onExit) { Text("All done — back to the library", fontSize = 22.sp) }
            }
        }
        error?.let {
            Text("This video cannot be played: $it", color = Color.White, fontSize = 20.sp,
                modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        if (controlsShown) {
            // The phone's top bar: back, what plays (and the chapter it is in), the sleep timer, frame steps while
            // paused, repeating a part, the chapters, a snapshot, what is playing and the gear.
            PlayerTopBar(
                title = playing.title + (playing.subtitle?.let { "  ·  $it" } ?: "") +
                    (ChapterSteps.current(chapters, position)?.let { "  ·  " + ChapterSteps.name(chapters, it) } ?: ""),
                note = sleepNote(session),
                paused = !isPlaying,
                abLoop = abLoop,
                hasChapters = chapters.isNotEmpty(),
                onBack = onExit,
                onStepFrame = ::stepFrame,
                onRepeat = { show(session.markRepeat()) },
                onChapters = { chapterPicker = true },
                onSnapshot = ::snapshot,
                onInfo = { infoOpen = true },
                onGear = { panel = !panel },
                modifier = Modifier.align(Alignment.TopStart),
            )
            Column(
                Modifier.fillMaxWidth().align(Alignment.BottomStart)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(16.dp),
            ) {
                Slider(
                    value = if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = { engine.seekTo((it * duration).toLong()) },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { togglePlay() }) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause",
                            tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    if (index + 1 < queue.size) {
                        IconButton(onClick = { next() }) { Icon(Icons.Default.SkipNext, "Next", tint = Color.White) }
                    }
                    Text("${clock(position)} · ${clock(duration)}", color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onFullScreen) {
                        Icon(if (fullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            "Full screen", tint = Color.White)
                    }
                }
            }
        }
    }
}

/** Which track picker is open over the player. */
private enum class TrackPicker { SOUND, SUBTITLES, SECOND }

/** The subtitle and sound files beside [video] (Sidecars), by name — on this computer or a network source. */
private fun sidecarsOf(places: DesktopLibrary, video: String): List<Sidecars.Sidecar> {
    val file = places.file(video)
    return Sidecars.of(file.name, file.parentFile?.listFiles()?.map { it.name }.orEmpty())
}

/**
 * What to open for [video]: from [startMs], with the subtitle and sound files beside it, starting in the household's
 * [audioLanguage] where it has a sound in it.
 */
private fun requestFor(places: DesktopLibrary, video: String, startMs: Long, audioLanguage: String): MediaRequest {
    val folder = places.file(video).parentFile
    fun pathOf(name: String) = folder?.child(name)?.path ?: name
    val sidecars = sidecarsOf(places, video)
    val subtitles = sidecars.filter { it.kind == Sidecars.Kind.SUBTITLES }.map {
        MediaRequest.SubtitleFile(pathOf(it.name), it.mimeType, it.language, it.describe(), it.forced)
    }
    val audio = sidecars.filter { it.kind == Sidecars.Kind.AUDIO }.map { pathOf(it.name) }
    return MediaRequest(video, subtitles = subtitles, audio = audio, startMs = startMs, audioLanguage = audioLanguage)
}

/**
 * A subtitle line: white, with a dark outline that keeps it readable over any picture, at [size]; on a dark box where
 * the [look] asks for one.
 */
@Composable
private fun SubtitleLine(
    text: String,
    look: com.hawkynt.mediadroid.player.SubtitleLook,
    size: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier,
) {
    val boxed = if (look.box) Modifier.background(Color(0xB3000000)).padding(horizontal = 8.dp) else Modifier
    Box(modifier.then(boxed)) {
        val style = TextStyle(fontSize = size, textAlign = TextAlign.Center, lineHeight = size * 1.25f)
        Text(text, style = style.copy(color = Color.Black, drawStyle = Stroke(width = 6f, join = StrokeJoin.Round)))
        Text(text, style = style.copy(color = Color.White))
    }
}

/**
 * [image] centred at [shown], the size the picture size gave it (PictureLayout) — larger than the canvas for Fill, its
 * overhang cut; before that is known, as large as the canvas allows, its shape kept. Moved by [drift] (BurnInDrift).
 * Placed as the stage's own window places it (PicturePlacement).
 */
private fun DrawScope.drawShown(image: ImageBitmap, shown: IntSize, drift: IntOffset = IntOffset.Zero) {
    val at = PicturePlacement.shown(
        size.width.toInt(), size.height.toInt(), shown.width, shown.height, image.width, image.height, drift.x, drift.y,
    )
    drawImage(image, dstOffset = IntOffset(at[0], at[1]), dstSize = IntSize(at[2], at[3]))
}

private const val CONTROLS_MS = 3_000L

/**
 * A 3 × 3 projective matrix, row by row (ScreenWarp.overlayMatrix), as Compose's 4 × 4 one, which carries the
 * perspective row as well.
 */
internal fun composeMatrix(m: FloatArray): androidx.compose.ui.graphics.Matrix =
    androidx.compose.ui.graphics.Matrix().apply {
        this[0, 0] = m[0]
        this[1, 0] = m[1]
        this[3, 0] = m[2]
        this[0, 1] = m[3]
        this[1, 1] = m[4]
        this[3, 1] = m[5]
        this[0, 3] = m[6]
        this[1, 3] = m[7]
        this[3, 3] = m[8]
    }

/** How often the histogram is counted. */
private const val HISTOGRAM_MS = 500L

/** How long a snapshot's file stays named on screen. */
private const val SNAPSHOT_NOTE_MS = 5_000L

/** How often Even frames looks at the film's rate, the speed and the screen's refresh again. */
private const val EVEN_FRAMES_MS = 3_000L

/** How long a confirmation stays over the picture. */
private const val FEEDBACK_MS = 800L

/** How much [ and ] change the speed. */
private const val SPEED_STEP = 0.05f

/** The player's background over the stage's own window: black at the least alpha there is. */
private val SEE_THROUGH = Color(0x01000000)

/** The pixels of a picture the stage shows itself (Presentation.NATIVE): none here, its size alone. */
private val SHOWN_BY_STAGE = ByteArray(0)
