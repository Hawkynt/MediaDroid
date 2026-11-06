package com.hawkynt.mediadroid.ui

import android.graphics.Bitmap
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 */
@Composable
fun TestPatternScreen(picture: PictureAdjustment, setup: ScreenSetup, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        var index by remember { mutableIntStateOf(0) }
        val pattern = TestPattern.entries[index]
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
                    when (event.key) {
                        Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.Spacebar -> step(1)
                        Key.DirectionLeft -> step(-1)
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                }
                .clickable(interactionSource = null, indication = null) { step(1) },
        ) {
            PatternSurface(pattern, TestPattern.pictureFor(picture), ScreenWarp.of(setup))
            AnimatedVisibility(
                visible = captionShown,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.75f)).padding(20.dp),
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

/**
 * [pattern] at the surface's own size, drawn into a picture stage of its own as a decoder would draw a frame: onto
 * the stage's input surface through a canvas, so every pass the films go through draws it.
 */
@Composable
private fun PatternSurface(pattern: TestPattern, picture: PictureAdjustment, warp: ScreenWarp) {
    val stage = remember { VideoFilterStage() }
    // The surface drawn into, and the pattern last drawn into the stage at what size.
    val drawn = remember { arrayOfNulls<Any>(2) }
    // The surface's callbacks are made once; they read what is to be shown now.
    val now by rememberUpdatedState(Triple(pattern, picture, warp))
    fun show(holder: SurfaceHolder, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val (pattern, picture, warp) = now
        stage.filters = picture
        stage.warp = warp
        stage.setVideoSize(width, height)
        stage.setInputSize(width, height)
        if (drawn[0] !== holder.surface) {
            if (!stage.setOutput(holder.surface)) return
            drawn[0] = holder.surface
        }
        val key = Triple(pattern, width, height)
        if (drawn[1] == key) return
        drawn[1] = key
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
    DisposableEffect(stage) {
        onDispose {
            stage.setOutput(null)
            stage.release()
        }
    }
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) =
                            show(holder, w, h)

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            stage.setOutput(null)
                            drawn[0] = null
                            drawn[1] = null
                        }
                    },
                )
            }
        },
        update = { view ->
            if (view.holder.surface.isValid) show(view.holder, view.width, view.height)
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** How long what to look for stays on screen after a pattern is shown. */
private const val CAPTION_MS = 6_000L
