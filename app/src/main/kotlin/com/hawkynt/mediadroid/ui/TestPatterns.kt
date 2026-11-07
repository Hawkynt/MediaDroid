package com.hawkynt.mediadroid.ui

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hawkynt.mediadroid.player.Keystone
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.ScreenSetup
import com.hawkynt.mediadroid.player.ScreenWarp
import com.hawkynt.mediadroid.player.TestPattern
import com.hawkynt.mediadroid.player.VideoFilterStage
import kotlinx.coroutines.delay

/**
 * The test patterns full screen, drawn through the real picture stage (VideoFilterStage) with the household's
 * [picture] (TestPattern.pictureFor) and this screen's [setup] — mirror, flip and keystone — so what is set while
 * looking at them is what the films get. A tap or the remote's OK and arrows step through them; what to look for
 * shows a few seconds after each; Back closes.
 *
 * With [keystone], the screen is for correcting the keystone: the grid alone, the two keystone sliders over it and the
 * correction drawn as it changes, each change handed to [keystone] to be kept; the remote's arrows nudge it
 * (Keystone.nudge — up and down the top and bottom, left and right the sides), OK or Done closes.
 */
@Composable
fun TestPatternScreen(
    picture: PictureAdjustment,
    setup: ScreenSetup,
    keystone: ((ScreenSetup) -> Unit)? = null,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        var index by remember { mutableIntStateOf(if (keystone != null) TestPattern.GRID.ordinal else 0) }
        val pattern = TestPattern.entries[index]
        var live by remember { mutableStateOf(setup) }
        fun change(changed: ScreenSetup) {
            live = changed
            keystone?.invoke(changed)
        }
        var captionShown by remember { mutableStateOf(true) }
        LaunchedEffect(index) {
            captionShown = true
            delay(CAPTION_MS)
            captionShown = false
        }
        fun step(by: Int) {
            index = (index + by).mod(TestPattern.entries.size)
        }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .focusRequester(focus).focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (keystone != null) {
                        when (event.key) {
                            Key.DirectionUp ->
                                change(live.copy(keystoneVertical = Keystone.nudge(live.keystoneVertical, 1)))
                            Key.DirectionDown ->
                                change(live.copy(keystoneVertical = Keystone.nudge(live.keystoneVertical, -1)))
                            Key.DirectionRight ->
                                change(live.copy(keystoneHorizontal = Keystone.nudge(live.keystoneHorizontal, 1)))
                            Key.DirectionLeft ->
                                change(live.copy(keystoneHorizontal = Keystone.nudge(live.keystoneHorizontal, -1)))
                            Key.DirectionCenter, Key.Enter -> onClose()
                            else -> return@onPreviewKeyEvent false
                        }
                        return@onPreviewKeyEvent true
                    }
                    when (event.key) {
                        Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.Spacebar -> step(1)
                        Key.DirectionLeft -> step(-1)
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                }
                .clickable(interactionSource = null, indication = null) { if (keystone == null) step(1) },
        ) {
            PatternSurface(pattern, TestPattern.pictureFor(picture), ScreenWarp.of(live))
            if (keystone != null) {
                KeystoneControls(
                    live,
                    ::change,
                    onDone = onClose,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else {
                AnimatedVisibility(
                    visible = captionShown,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Column(
                        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.75f))
                            .navigationBarsPadding().padding(20.dp),
                    ) {
                        Text(
                            "${pattern.label}  ·  ${index + 1} of ${TestPattern.entries.size}",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(pattern.hint, color = Color.White, fontSize = 16.sp)
                        Text(
                            "Tap for the next pattern, Back to close. The default picture in Settings applies to them.",
                            color = Color.LightGray,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The keystone's two sliders and Done, over the grid or on the phone while the grid is on a projector: [setup]
 * changed through [onChange] as they move.
 */
@Composable
fun KeystoneControls(
    setup: ScreenSetup,
    onChange: (ScreenSetup) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Clear of the system's bars: the pattern fills the screen under them, its controls must not.
    Column(
        modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.75f)).navigationBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Keystone", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Until the grid's lines on the wall are straight and parallel and its cells square. Remote: up and down " +
                "for the top and bottom, left and right for the sides.",
            color = Color.LightGray,
            fontSize = 14.sp,
        )
        KeystoneSlider(
            "Keystone, top and bottom", "top narrower", "bottom narrower", setup.keystoneVertical,
            { onChange(setup.copy(keystoneVertical = it)) },
        ) {}
        KeystoneSlider(
            "Keystone, left and right", "right shorter", "left shorter", setup.keystoneHorizontal,
            { onChange(setup.copy(keystoneHorizontal = it)) },
        ) {}
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (setup.keystoneVertical != 0f || setup.keystoneHorizontal != 0f) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { onChange(setup.copy(keystoneVertical = 0f, keystoneHorizontal = 0f)) },
                ) { Text("No keystone", fontSize = 16.sp) }
            }
            Button(onClick = onDone) { Text("Done", fontSize = 16.sp) }
        }
    }
}

/**
 * [pattern] drawn into a picture stage of its own as a decoder would draw a frame: onto the stage's input surface
 * through a canvas, so every pass the films go through draws it. One per surface it shows on.
 */
class PatternDrawer {
    private val stage = VideoFilterStage()
    private var surface: android.view.Surface? = null
    private var drawn: Triple<TestPattern, Int, Int>? = null

    // Once released, the stage's thread is gone and waiting on it would never end: every call after is a no-op.
    private var released = false

    /** [pattern] with [picture] and [warp] onto [holder]'s surface, [width] × [height]; drawn again only if new. */
    fun show(
        holder: SurfaceHolder,
        width: Int,
        height: Int,
        pattern: TestPattern,
        picture: PictureAdjustment,
        warp: ScreenWarp,
    ) {
        if (released || width <= 0 || height <= 0) return
        stage.filters = picture
        stage.warp = warp
        stage.setVideoSize(width, height)
        stage.setInputSize(width, height)
        if (surface !== holder.surface) {
            if (!stage.setOutput(holder.surface)) return
            surface = holder.surface
        }
        val key = Triple(pattern, width, height)
        if (drawn == key) return
        drawn = key
        val rgba = pattern.render(width, height)
        val pixels = IntArray(width * height) { i ->
            val at = i * 4
            (0xFF shl 24) or ((rgba[at].toInt() and 255) shl 16) or ((rgba[at + 1].toInt() and 255) shl 8) or
                (rgba[at + 2].toInt() and 255)
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        runCatching {
            val canvas = stage.inputSurface.lockCanvas(null)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            stage.inputSurface.unlockCanvasAndPost(canvas)
        }
        bitmap.recycle()
    }

    /** The surface is gone: the stage stops drawing there, and the next show draws the pattern anew. */
    fun detach() {
        if (released) return
        stage.setOutput(null)
        surface = null
        drawn = null
    }

    fun release() {
        if (released) return
        detach()
        stage.release()
        released = true
    }
}

/** [pattern] at the surface's own size through a [PatternDrawer] (see there). */
@Composable
private fun PatternSurface(pattern: TestPattern, picture: PictureAdjustment, warp: ScreenWarp) {
    val drawer = remember { PatternDrawer() }
    // The surface's callbacks are made once; they read what is to be shown now.
    val now by rememberUpdatedState(Triple(pattern, picture, warp))
    DisposableEffect(drawer) { onDispose { drawer.release() } }
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) =
                            drawer.show(holder, w, h, now.first, now.second, now.third)

                        override fun surfaceDestroyed(holder: SurfaceHolder) = drawer.detach()
                    },
                )
            }
        },
        // What is shown named here, not only read through `now`: a new pattern makes a new block, and AndroidView runs
        // a new block — a state read inside the callbacks alone did not bring it back, and taps changed only the
        // caption.
        update = { view ->
            if (view.holder.surface.isValid) drawer.show(view.holder, view.width, view.height, pattern, picture, warp)
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * The grid alone, full screen on [display] — a projector attached as a second screen — while the phone holds the
 * keystone's controls: [show] draws it with a screen setup as it changes.
 */
class PatternPresentation(
    context: Context,
    display: Display,
    private val picture: PictureAdjustment,
    private var setup: ScreenSetup,
) : Presentation(context, display) {

    private val drawer = PatternDrawer()
    private var view: SurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val surface = SurfaceView(context)
        surface.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = draw()

                override fun surfaceDestroyed(holder: SurfaceHolder) = drawer.detach()
            },
        )
        setContentView(surface)
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        view = surface
    }

    /** The grid drawn with [changed]: its keystone as the phone's sliders have it now. */
    fun show(changed: ScreenSetup) {
        setup = changed
        draw()
    }

    private fun draw() {
        val v = view ?: return
        if (!v.holder.surface.isValid) return
        val shown = TestPattern.pictureFor(picture)
        drawer.show(v.holder, v.width, v.height, TestPattern.GRID, shown, ScreenWarp.of(setup))
    }

    /**
     * The window goes first — its surface's end detaches the stage while the stage still runs — then the stage; the
     * other way round, the surface's end waited for a stage thread already gone, and the app hung.
     */
    fun release() {
        dismiss()
        drawer.release()
    }
}

/** How long what to look for stays on screen after a pattern is shown. */
private const val CAPTION_MS = 6_000L
