package com.hawkynt.mediadroid.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.hawkynt.mediadroid.engine.PlaybackEngine
import com.hawkynt.mediadroid.engine.PlaybackTuning
import com.hawkynt.mediadroid.engine.Track
import com.hawkynt.mediadroid.engine.Tracks
import com.hawkynt.mediadroid.engine.desktop.DesktopPlaybackEngine
import com.hawkynt.mediadroid.library.Sidecars
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PlaybackMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** The player: the picture letterboxed in the window, controls that fade, keys for everything. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(
    store: DesktopStore,
    queue: List<Playing>,
    startIndex: Int,
    fullScreen: Boolean,
    onFullScreen: () -> Unit,
    onExit: () -> Unit,
) {
    var index by remember { mutableIntStateOf(startIndex) }
    val playing = queue[index]
    val engine = remember {
        DesktopPlaybackEngine(PlaybackTuning.Mutable()).apply {
            // MEDIADROID_MUTE=1: checks and documentation runs play silently.
            if (System.getenv("MEDIADROID_MUTE") == "1") volume = 0f
            speed = store.speed
        }
    }
    val bitmap = remember { PictureBitmap() }
    // The picture stage (deinterlacing, upscalers, colour) on the graphics card, made once per player.
    val stage = remember { com.hawkynt.mediadroid.engine.desktop.GlStage() }
    var picture by remember { mutableStateOf(store.picture) }
    // MEDIADROID_SHOW=panel: the picture panel open from the start (documentation and checks).
    var panel by remember { mutableStateOf(System.getenv("MEDIADROID_SHOW") == "panel") }
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf(emptyList<Track>()) }
    // A sound file's track named as the picker shows a sidecar: `EN`, `DE · commentary`.
    val soundNames = remember(index) {
        sidecarsOf(playing.video).filter { it.kind == Sidecars.Kind.AUDIO }.associate { it.name to it.describe() }
    }
    var subtitle by remember { mutableStateOf<String?>(null) }
    var controlsUntil by remember { mutableLongStateOf(System.currentTimeMillis() + CONTROLS_MS) }
    val focus = remember { FocusRequester() }

    fun saveProgress() {
        val d = engine.durationMs
        val p = engine.positionMs
        if (d <= 0 || p <= 0 || engine.openedPath != playing.video) return
        if (PlaybackMath.isWatched(p, d, null)) store.markWatched(playing.video) else store.setResume(playing.video, p)
    }

    fun next() {
        saveProgress()
        if (index + 1 < queue.size) index++ else onExit()
    }

    DisposableEffect(Unit) {
        val listener = object : PlaybackEngine.Listener {
            override fun onEnded() {
                store.markWatched(engine.openedPath ?: return)
            }
        }
        engine.addListener(listener)
        onDispose {
            saveProgress()
            engine.removeListener(listener)
            engine.close()
            stage.close()
        }
    }
    // A subtitle picked becomes the household's language for the next videos; off stays off.
    fun pick(kind: Track.Kind, id: String?) {
        engine.select(kind, id)
        if (kind == Track.Kind.TEXT) {
            store.subtitleLanguage = engine.tracks.firstOrNull { it.id == id }?.language.orEmpty()
        }
    }

    LaunchedEffect(index) {
        error = null
        tracks = emptyList()
        frame = null
        runCatching { engine.open(requestFor(playing.video, store.resumeMs(playing.video))) }
            .onFailure { error = it.message ?: it.toString() }
    }
    // Every window frame: the picture due now — through the stage when the household set filters — the clock, the end.
    LaunchedEffect(Unit) {
        var lastShown: Any? = null
        var lastLook: Pair<PictureAdjustment, IntSize>? = null
        while (true) {
            withFrameNanos { }
            engine.picture()?.let { raw ->
                val look = picture to canvas
                if (raw !== lastShown || look != lastLook) {
                    lastShown = raw
                    lastLook = look
                    // Fitted by the picture's true shape: non-square pixels (PAL DV, anamorphic DVD) widened.
                    val (w, h) = fitted((raw.width * engine.pixelAspect).toInt(), raw.height, canvas)
                    val shown = if ((picture.hasFilters || engine.pixelAspect != 1f) && stage.ready && w > 0 && h > 0) {
                        withContext(Dispatchers.Default) { stage.process(raw, picture, w, h) }
                    } else {
                        raw
                    }
                    frame = bitmap.of(shown)
                }
            }
            position = engine.positionMs
            duration = engine.durationMs
            val wereEmpty = tracks.none { it.kind == Track.Kind.TEXT }
            tracks = engine.tracks
            // A video's subtitles start in the household's language, as on the phone.
            if (wereEmpty) Tracks.preferredText(tracks, store.subtitleLanguage)?.let { pick(Track.Kind.TEXT, it.id) }
            subtitle = engine.subtitle()
            val wasPlaying = isPlaying
            isPlaying = engine.isPlaying
            if (wasPlaying && !isPlaying && duration > 0 && position >= duration - 100) next()
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

    var speed by remember { mutableStateOf(store.speed) }
    fun changeSpeed(value: Float) {
        val clamped = (kotlin.math.round(value * 20f) / 20f).coerceIn(PlaybackMath.SPEED_MIN, PlaybackMath.SPEED_MAX)
        speed = clamped
        engine.speed = clamped
        store.speed = clamped
    }

    // S: the next subtitle track, after the last one off again.
    fun nextSubtitles() {
        val text = engine.tracks.filter { it.kind == Track.Kind.TEXT }
        val at = text.indexOfFirst { it.selected }
        pick(Track.Kind.TEXT, text.getOrNull(at + 1)?.id)
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus).focusable()
            .onPointerEvent(PointerEventType.Move) { controlsUntil = System.currentTimeMillis() + CONTROLS_MS }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                controlsUntil = System.currentTimeMillis() + CONTROLS_MS
                when (event.key) {
                    Key.Spacebar, Key.K -> togglePlay()
                    Key.DirectionRight -> engine.seekTo(PlaybackMath.seekBy(engine.positionMs, engine.durationMs, true))
                    Key.DirectionLeft -> engine.seekTo(PlaybackMath.seekBy(engine.positionMs, engine.durationMs, false))
                    Key.F -> onFullScreen()
                    Key.N -> next()
                    Key.P -> panel = !panel
                    Key.S -> nextSubtitles()
                    Key.LeftBracket -> changeSpeed(speed - SPEED_STEP)
                    Key.RightBracket -> changeSpeed(speed + SPEED_STEP)
                    Key.Escape -> if (fullScreen) onFullScreen() else onExit()
                    else -> return@onKeyEvent false
                }
                true
            },
    ) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { canvas = it }) { frame?.let { drawLetterboxed(it) } }
        subtitle?.let { line ->
            SubtitleLine(
                line,
                Modifier.align(Alignment.BottomCenter).padding(start = 48.dp, end = 48.dp,
                    bottom = if (controlsShown) 120.dp else 40.dp),
            )
        }
        if (panel) {
            PicturePanel(
                picture,
                onChange = {
                    picture = it
                    store.picture = it
                },
                modifier = Modifier.align(Alignment.CenterEnd),
                tracks = tracks.map { t ->
                    val named = t.kind == Track.Kind.AUDIO && t.source > 0
                    if (named) t.copy(label = soundNames[t.label] ?: t.label) else t
                },
                onPick = ::pick,
                speed = speed,
                onSpeed = ::changeSpeed,
            )
        }
        error?.let {
            Text("This video cannot be played: $it", color = Color.White, fontSize = 20.sp,
                modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        if (controlsShown) {
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopStart)
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Text(playing.title, color = Color.White, fontSize = 20.sp)
            }
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
                    IconButton(onClick = { panel = !panel }) {
                        Icon(Icons.Default.Tune, "Picture", tint = Color.White)
                    }
                    IconButton(onClick = onFullScreen) {
                        Icon(if (fullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            "Full screen", tint = Color.White)
                    }
                }
            }
        }
    }
}

/** The subtitle and sound files beside [video] (Sidecars), by name. */
private fun sidecarsOf(video: String): List<Sidecars.Sidecar> {
    val file = File(video)
    return Sidecars.of(file.name, file.parentFile?.list()?.toList().orEmpty())
}

/** What to open for [video]: from [startMs], with the subtitle and sound files beside it. */
private fun requestFor(video: String, startMs: Long): MediaRequest {
    val folder = File(video).parentFile
    val sidecars = sidecarsOf(video)
    val subtitles = sidecars.filter { it.kind == Sidecars.Kind.SUBTITLES }.map {
        MediaRequest.SubtitleFile(File(folder, it.name).path, it.mimeType, it.language, it.describe(), it.forced)
    }
    val audio = sidecars.filter { it.kind == Sidecars.Kind.AUDIO }.map { File(folder, it.name).path }
    return MediaRequest(video, subtitles = subtitles, audio = audio, startMs = startMs)
}

/** A subtitle line: white, with a dark outline that keeps it readable over any picture. */
@Composable
private fun SubtitleLine(text: String, modifier: Modifier) {
    Box(modifier) {
        val style = TextStyle(fontSize = 30.sp, textAlign = TextAlign.Center, lineHeight = 38.sp)
        Text(text, style = style.copy(color = Color.Black, drawStyle = Stroke(width = 6f, join = StrokeJoin.Round)))
        Text(text, style = style.copy(color = Color.White))
    }
}

/** A [w] × [h] picture's size fitted into [canvas], its shape kept. */
private fun fitted(w: Int, h: Int, canvas: IntSize): Pair<Int, Int> {
    if (canvas.width <= 0 || canvas.height <= 0) return w to h
    val scale = minOf(canvas.width.toFloat() / w, canvas.height.toFloat() / h)
    return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
}

/** [image] as large as the canvas allows, its shape kept, centred. */
private fun DrawScope.drawLetterboxed(image: ImageBitmap) {
    val scale = minOf(size.width / image.width, size.height / image.height)
    val w = image.width * scale
    val h = image.height * scale
    drawImage(
        image,
        dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
        dstSize = IntSize(w.toInt(), h.toInt()),
    )
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private const val CONTROLS_MS = 3_000L

/** How much [ and ] change the speed. */
private const val SPEED_STEP = 0.05f
