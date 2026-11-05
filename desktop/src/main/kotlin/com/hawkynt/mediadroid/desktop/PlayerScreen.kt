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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.engine.MediaRequest
import com.hawkynt.mediadroid.engine.PlaybackEngine
import com.hawkynt.mediadroid.engine.PlaybackTuning
import com.hawkynt.mediadroid.engine.desktop.DesktopPlaybackEngine
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PlaybackMath
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

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
    LaunchedEffect(index) {
        error = null
        frame = null
        runCatching { engine.open(MediaRequest(playing.video, startMs = store.resumeMs(playing.video))) }
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
            val wasPlaying = isPlaying
            isPlaying = engine.isPlaying
            if (wasPlaying && !isPlaying && duration > 0 && position >= duration - 100) next()
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val controlsShown = now < controlsUntil || !isPlaying

    fun togglePlay() = if (engine.isPlaying) engine.pause() else engine.play()

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
                    Key.Escape -> if (fullScreen) onFullScreen() else onExit()
                    else -> return@onKeyEvent false
                }
                true
            },
    ) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { canvas = it }) { frame?.let { drawLetterboxed(it) } }
        if (panel) {
            PicturePanel(
                picture,
                onChange = {
                    picture = it
                    store.picture = it
                },
                modifier = Modifier.align(Alignment.CenterEnd),
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
