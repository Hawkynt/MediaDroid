package com.hawkynt.mediadroyd.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
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
 *
 * libass lays the track out on the whole video ([AssFrame]) and draws only the part of it the screen shows: Fill and
 * zoom spread the view past the screen, and the stage's crop of the black bars ([crop]: the part and the size of the
 * picture it is part of) leaves the bars out of it. Signs keep their place on the whole video; lines without a place
 * of their own stay inside what shows.
 */
@OptIn(UnstableApi::class)
class AssOverlayView(
    context: Context,
    private val subtitles: AssSubtitles,
    private val positionMs: () -> Long,
    private val videoSize: () -> Pair<Int, Int>,
    private val crop: () -> Pair<PictureCrop, PictureSize>?,
    private val place: () -> ScreenRect?,
) : View(context), Choreographer.FrameCallback {

    private var drawing = false
    private var drawnFor: AssTrack? = null
    private var shown: Bitmap? = null
    private var shownAt = 0 to 0
    private val visible = Rect()
    private val inWindow = IntArray(2)

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
        val frame = shownFrame() ?: return
        drawing = true
        val at = positionMs()
        val (videoWidth, videoHeight) = videoSize()
        // A track not drawn before, or a frame moved, is drawn whole even where libass would call it unchanged.
        val fresh = track !== drawnFor || frame != drawnFrame
        drawnFrame = frame
        drawer.execute {
            val picture = runCatching { track.render(at, frame, videoWidth, videoHeight) }.getOrNull()
            val bitmap = picture?.takeUnless { it.isEmpty }?.let {
                Bitmap.createBitmap(it.pixels, it.width, it.height, Bitmap.Config.ARGB_8888)
            }
            post {
                drawing = false
                if (picture != null || fresh) {
                    show(bitmap, track, frame.x + (picture?.x ?: 0), frame.y + (picture?.y ?: 0))
                }
            }
        }
    }

    private var drawnFrame: AssFrame? = null

    /**
     * Where libass draws, in this view's pixels: the part of the view on the screen — the view scaled with its frame
     * (zoom) and cut by the player's bounds (Fill) — with the whole video around the crop the stage shows. Null where
     * none of it is on the screen.
     */
    private fun shownFrame(): AssFrame? {
        if (!getGlobalVisibleRect(visible)) return null
        getLocationInWindow(inWindow)
        var scaleX = 1f
        var scaleY = 1f
        var view: View? = this
        while (view != null) {
            scaleX *= view.scaleX
            scaleY *= view.scaleY
            view = view.parent as? View
        }
        if (scaleX <= 0f || scaleY <= 0f) return null
        val (part, of) = crop() ?: (null to PictureSize(0, 0))
        // A keystone has the stage draw on the whole screen, the picture on part of it (from the bottom left).
        val at = place() ?: ScreenRect.Whole
        return AssFrame.shown(
            width, height,
            ((visible.left - inWindow[0]) / scaleX).toInt().coerceIn(0, width),
            ((visible.top - inWindow[1]) / scaleY).toInt().coerceIn(0, height),
            ((visible.right - inWindow[0]) / scaleX).toInt().coerceIn(0, width),
            ((visible.bottom - inWindow[1]) / scaleY).toInt().coerceIn(0, height),
            part, of.width, of.height,
            (at.x * width).toInt(), ((1f - at.y - at.height) * height).toInt(),
            (at.width * width).toInt(), (at.height * height).toInt(),
        )
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
            Thread(it, "mediadroyd-ass").apply { isDaemon = true }
        }

        /**
         * An overlay over [view]'s picture, for [subtitles]; does nothing where libass is not there. The player's
         * position and video size come from [positionMs] and [videoSize] (0 × 0 where unknown), the stage's crop of the
         * black bars from [crop] (null where the picture is shown whole), and where on the view the picture is drawn
         * from [place] (null for all of it).
         */
        fun attach(
            view: PlayerView,
            subtitles: AssSubtitles,
            positionMs: () -> Long,
            videoSize: () -> Pair<Int, Int>,
            crop: () -> Pair<PictureCrop, PictureSize>? = { null },
            place: () -> ScreenRect? = { null },
        ) {
            if (!AssSubtitles.available) return
            val frame = view.findViewById<FrameLayout>(androidx.media3.ui.R.id.exo_content_frame) ?: return
            // One overlay for each set of subtitles: the first, and the second at the top.
            if ((0 until frame.childCount).any { (frame.getChildAt(it) as? AssOverlayView)?.subtitles === subtitles }) {
                return
            }
            frame.addView(
                AssOverlayView(view.context, subtitles, positionMs, videoSize, crop, place),
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
