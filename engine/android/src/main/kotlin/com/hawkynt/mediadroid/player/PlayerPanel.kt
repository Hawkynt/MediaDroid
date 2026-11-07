package com.hawkynt.mediadroid.player

import android.content.Context
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import com.hawkynt.mediadroid.engine.android.R

/**
 * Where the picture shows on Android, with Media3's own controls over it: a player view (SurfaceView, so pictures go
 * straight to the compositor at the refresh they are meant for) in the shape, fit, zoom and look the screens set, the
 * subtitles — Media3's, the second track's, libass's — over it. The screens drive it through these methods and
 * [root], never through Media3 itself. [inflate] makes one; [controls] false leaves the controls out (the second
 * screen's picture).
 */
@OptIn(UnstableApi::class)
class PlayerPanel private constructor(private val view: PlayerView) {

    /** The view to put on screen. */
    val root: View get() = view

    /** The view whose surface shows the picture, where it is a SurfaceView. */
    val surfaceView: SurfaceView? get() = view.videoSurfaceView as? SurfaceView

    /** The display the panel shows on, null before it is attached. */
    val display: android.view.Display? get() = view.display

    /** Shows [engine]'s pictures and, through [queue], its controls (the engine itself without a queue); none: null. */
    fun show(engine: AndroidPlaybackEngine?, queue: PlaybackQueue? = null) {
        view.player = queue?.player ?: engine?.exo
    }

    /** Whether Media3's controls may show at all (not in picture in picture). */
    var controlsEnabled: Boolean
        get() = view.useController
        set(value) {
            view.useController = value
        }

    fun showControls() = view.showController()

    fun hideControls() = view.hideController()

    /**
     * The controls start hidden, show on a tap and hide after [timeoutMs] untouched or on a tap; [onVisible] hears
     * them come and go. Speed, subtitles and tracks are the screens' own, so Media3's gear and subtitle button stay
     * out; [onHoldForward] / [onHoldBack] hear a skip button held (true) and let go (false).
     */
    fun setUpControls(
        timeoutMs: Int,
        onVisible: (Boolean) -> Unit,
        onHoldForward: (holding: Boolean) -> Unit,
        onHoldBack: (holding: Boolean) -> Unit,
    ) {
        view.useController = true
        view.controllerAutoShow = false
        view.controllerShowTimeoutMs = timeoutMs
        view.controllerHideOnTouch = true
        view.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
        view.setShowSubtitleButton(false)
        view.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility -> onVisible(visibility == View.VISIBLE) },
        )
        view.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd)?.onHold(onHoldForward)
        view.findViewById<View>(androidx.media3.ui.R.id.exo_rew)?.onHold(onHoldBack)
    }

    /** The picture behind the controls shows through before the first frame (the demo library's posters). */
    fun clearShutter() = view.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)

    /** Takes the shutter away: a first frame drawn by the picture stage reaches the screen without telling the view. */
    fun openShutter() {
        view.findViewById<View>(androidx.media3.ui.R.id.exo_shutter)?.visibility = View.INVISIBLE
    }

    /** The picture shown here or, while it plays on another screen, hidden: the phone black behind the controls. */
    fun showPicture(visible: Boolean) {
        view.videoSurfaceView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        AssOverlayView.show(view, visible)
    }

    /** The chapters' starts marked on the time bar. */
    fun markChapters(startsMs: LongArray) = view.setExtraAdGroupMarkers(startsMs, BooleanArray(startsMs.size))

    /** The controls kept clear of insets and a cutout, in pixels. */
    fun padControls(left: Int, top: Int, right: Int, bottom: Int) {
        view.findViewById<View>(androidx.media3.ui.R.id.exo_controller)?.setPadding(left, top, right, bottom)
    }

    /**
     * Every move of a finger (or a remote's keys) along the seek bar, reported with where the bar's line runs in this
     * view's pixels; null when it lets go.
     */
    fun onScrub(report: (Scrub?) -> Unit) {
        val bar = view.findViewById<View>(androidx.media3.ui.R.id.exo_progress) ?: return
        val timeBar = bar as? TimeBar ?: return
        fun at(position: Long): Scrub {
            val barAt = IntArray(2).also(bar::getLocationInWindow)
            val viewAt = IntArray(2).also(view::getLocationInWindow)
            // Media3 draws the line inside the bar's padding, inset by half the dragged scrubber.
            val inset = bar.paddingLeft + SCRUBBER_HALF_DP * bar.resources.displayMetrics.density
            val left = barAt[0] - viewAt[0] + inset
            val width = bar.width - bar.paddingLeft - bar.paddingRight - 2 * SCRUBBER_HALF_DP *
                bar.resources.displayMetrics.density
            return Scrub(position, left, width.coerceAtLeast(1f), barAt[1] - viewAt[1] + bar.height / 2f)
        }
        timeBar.addListener(
            object : TimeBar.OnScrubListener {
                override fun onScrubStart(timeBar: TimeBar, position: Long) = report(at(position))

                override fun onScrubMove(timeBar: TimeBar, position: Long) = report(at(position))

                override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) = report(null)
            },
        )
    }

    /** The finger on the seek bar: the moment it points at and where the bar's line runs, in the view's pixels. */
    data class Scrub(val positionMs: Long, val lineLeft: Float, val lineWidth: Float, val lineY: Float)

    /**
     * Over the picture: the ASS subtitles libass draws for [engine], first and second, laid out on the whole video
     * ([crop]: the stage's crop and the picture it is part of, null without; [place]: where on the view the picture is
     * drawn, null for all of it), and the second track's text at the top.
     */
    fun showSubtitles(
        engine: AndroidPlaybackEngine,
        crop: () -> Pair<PictureCrop, PictureSize>?,
        place: () -> ScreenRect?,
    ) {
        for (subtitles in listOf(engine.assSubtitles, engine.secondAss)) {
            AssOverlayView.attach(
                view,
                subtitles,
                positionMs = { engine.positionMs - engine.tuning.subtitleDelayMs },
                videoSize = { engine.videoFormat?.let { it.width to it.height } ?: (0 to 0) },
                crop = crop,
                place = place,
            )
        }
        engine.second.attach(view)
    }

    /** Size, position and backing of the subtitles, the first track's and the second's, as [look] has them. */
    fun applySubtitleLook(engine: AndroidPlaybackEngine, look: SubtitleLook) {
        view.subtitleView?.applyLook(look)
        engine.second.viewOf(view)?.applyLook(look)
    }

    /** The shape of the screen this view fills: its width over its height; 0 before it is laid out. */
    val panelAspect: Float
        get() = view.rootView.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: 0f

    /** The size of the screen this view fills, in pixels. */
    val screenWidth: Int get() = view.rootView.width
    val screenHeight: Int get() = view.rootView.height

    /** The view's own size, in pixels. */
    val width: Int get() = view.width
    val height: Int get() = view.height

    /**
     * The picture's frame in the shape [aspect] (width over height): the shape the picture is drawn in, or the whole
     * screen's where the stage draws all of it.
     */
    fun shapePicture(aspect: Float) {
        view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)?.setAspectRatio(aspect)
    }

    /**
     * Fit, zoom and pan on the picture's frame only — the controls and subtitles drawn above it are neither scaled nor
     * moved — and [drift] moving the picture and the subtitles together (BurnInDrift, an OLED screen's burn-in care);
     * the subtitles bent as the stage bends the picture ([bend]: a projector's mirror, flip and keystone).
     */
    fun fitPicture(
        fit: PictureFit,
        zoom: Float,
        panX: Float,
        panY: Float,
        driftX: Float,
        driftY: Float,
        bend: ScreenWarp,
    ) {
        view.resizeMode = when (fit) {
            PictureFit.FIT, PictureFit.PANORAMIC -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            PictureFit.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            PictureFit.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        val frame = view.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) ?: return
        frame.scaleX = zoom
        frame.scaleY = zoom
        frame.translationX = panX + driftX
        frame.translationY = panY + driftY
        view.subtitleView?.translationX = driftX
        view.subtitleView?.translationY = driftY
        // The second subtitle track sits in the overlay frame.
        view.overlayFrameLayout?.translationX = driftX
        view.overlayFrameLayout?.translationY = driftY
        // The subtitles — text and teletext, the second track, libass's signs — bent as the stage bends the picture
        // (ScreenWarp.overlayMatrix).
        val ass = (frame as? android.view.ViewGroup)?.let { group ->
            (0 until group.childCount).map { group.getChildAt(it) }.filterIsInstance<AssOverlayView>()
        }.orEmpty()
        (listOfNotNull(view.subtitleView, view.overlayFrameLayout) + ass).forEach { it.bendWith(bend) }
    }

    companion object {
        /** Half of Media3's dragged scrubber, by which the bar's line is inset. */
        private const val SCRUBBER_HALF_DP = 12

        /** How long a skip button must be held before it starts stepping on its own. */
        private const val HOLD_START_MS = 400L

        /** A new panel; without [controls] Media3's controls are left out. */
        fun inflate(context: Context, controls: Boolean = true): PlayerPanel {
            // Inflated, not constructed: the surface type is only reachable through the XML attribute.
            val view = LayoutInflater.from(context).inflate(R.layout.player_view, null, false) as PlayerView
            view.useController = controls
            return PlayerPanel(view)
        }

        /**
         * Tells [hold] true once the view has been held for [HOLD_START_MS] and false when it is let go; a short tap
         * stays an ordinary click.
         */
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        private fun View.onHold(hold: (Boolean) -> Unit) {
            var holding = false
            val starter = Runnable {
                holding = true
                hold(true)
            }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        holding = false
                        view.postDelayed(starter, HOLD_START_MS)
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        view.removeCallbacks(starter)
                        if (holding) {
                            holding = false
                            hold(false)
                            // The hold replaced the click.
                            view.isPressed = false
                            return@setOnTouchListener true
                        }
                    }
                }
                false
            }
        }

        /**
         * This view's drawing bent by [warp] over its own size: through the view's animation matrix, which takes a
         * projective one, from Android 10; before, only the mirror and the flip, by its scale about its middle.
         */
        private fun View.bendWith(warp: ScreenWarp) {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                animationMatrix = if (warp.bendsOverlays && width > 0 && height > 0) {
                    android.graphics.Matrix().apply { setValues(warp.overlayMatrix(width.toFloat(), height.toFloat())) }
                } else {
                    null
                }
            } else {
                val m = warp.overlayMatrix(1f, 1f)
                scaleX = if (m[0] < 0f) -1f else 1f
                scaleY = if (m[4] < 0f) -1f else 1f
            }
        }

        /** Size, position and backing of the subtitles as [look] has them. */
        private fun androidx.media3.ui.SubtitleView.applyLook(look: SubtitleLook) {
            setFractionalTextSize(look.textSizeFraction)
            setBottomPaddingFraction(look.bottomPaddingFraction)
            setStyle(
                androidx.media3.ui.CaptionStyleCompat(
                    android.graphics.Color.WHITE,
                    if (look.box) 0xB0000000.toInt() else android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                    if (look.box) {
                        androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
                    } else {
                        androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
                    },
                    android.graphics.Color.BLACK,
                    null,
                ),
            )
        }
    }
}
