package com.hawkynt.mediadroid.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

/**
 * The ASS subtitles libass draws ([AssSubtitles]), laid over the picture: inside the player view's content frame, so it
 * covers the video exactly — its size the video's on screen, in the screen's own pixels, so the text stays sharp —
 * drawn again at every screen refresh where the moment ([positionMs], the subtitle delay taken off) shows something
 * new. libass draws on a thread of its own; the screen's thread only puts the finished picture up.
 */
@OptIn(UnstableApi::class)
class AssOverlayView(
    context: Context,
    private val subtitles: AssSubtitles,
    private val positionMs: () -> Long,
    private val videoSize: () -> Pair<Int, Int>,
) : View(context), Choreographer.FrameCallback {

    private var drawing = false
    private var drawnFor: AssTrack? = null
    private var shown: Bitmap? = null
    private var shownAt = 0 to 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        Choreographer.getInstance().postFrameCallback(this)
        val track = subtitles.track
        if (track == null) {
            if (shown != null) show(null, track)
            return
        }
        // Hidden (the picture on another screen): that screen's overlay draws; libass draws for one size at a time.
        if (drawing || !isShown || width <= 0 || height <= 0) return
        drawing = true
        val at = positionMs()
        val w = width
        val h = height
        val (videoWidth, videoHeight) = videoSize()
        // A track not drawn before is drawn whole even where libass would call it unchanged.
        val fresh = track !== drawnFor
        drawer.execute {
            val picture = runCatching { track.render(at, w, h, videoWidth, videoHeight) }.getOrNull()
            val bitmap = picture?.takeUnless { it.isEmpty }?.let {
                Bitmap.createBitmap(it.pixels, it.width, it.height, Bitmap.Config.ARGB_8888)
            }
            post {
                drawing = false
                if (picture != null || fresh) show(bitmap, track, picture?.x ?: 0, picture?.y ?: 0)
            }
        }
    }

    private fun show(bitmap: Bitmap?, track: AssTrack?, x: Int = 0, y: Int = 0) {
        shown = bitmap
        shownAt = x to y
        drawnFor = track
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        shown?.let { canvas.drawBitmap(it, shownAt.first.toFloat(), shownAt.second.toFloat(), null) }
    }

    companion object {
        /** The one thread libass draws on, for every overlay. */
        private val drawer = Executors.newSingleThreadExecutor {
            Thread(it, "mediadroid-ass").apply { isDaemon = true }
        }

        /**
         * An overlay over [view]'s picture, for [subtitles]; does nothing where libass is not there. The player's
         * position and video size come from [positionMs] and [videoSize] (0 × 0 where unknown).
         */
        fun attach(
            view: PlayerView,
            subtitles: AssSubtitles,
            positionMs: () -> Long,
            videoSize: () -> Pair<Int, Int>,
        ) {
            if (!AssSubtitles.available) return
            val frame = view.findViewById<FrameLayout>(androidx.media3.ui.R.id.exo_content_frame) ?: return
            if ((0 until frame.childCount).any { frame.getChildAt(it) is AssOverlayView }) return
            frame.addView(
                AssOverlayView(view.context, subtitles, positionMs, videoSize),
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }

        /** Shows or hides [view]'s overlay, with the picture it lies over. */
        fun show(view: PlayerView, visible: Boolean) {
            val frame = view.findViewById<FrameLayout>(androidx.media3.ui.R.id.exo_content_frame) ?: return
            (0 until frame.childCount).map(frame::getChildAt).filterIsInstance<AssOverlayView>()
                .forEach { it.visibility = if (visible) View.VISIBLE else View.INVISIBLE }
        }
    }
}
