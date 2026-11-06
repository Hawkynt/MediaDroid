package com.hawkynt.mediadroid.player

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelectorResult
import androidx.media3.exoplayer.upstream.BandwidthMeter

/**
 * A second subtitle track beside the first (PlaybackEngine.secondSubtitle). Media3 plays one track of a kind, so the
 * player carries a second set of subtitle renderers — libass's for ASS, Media3's for the rest — after the first, which
 * the track selector never gives a track of its own accord; this selector ([selector]) is Media3's own, and then hands
 * the [wanted] track to the second renderer that takes it. Its cues come out through [output] (text) or the second
 * [AssSubtitles] (drawn by libass, at the top).
 */
@OptIn(UnstableApi::class)
class SecondSubtitles(private val media3: DefaultTrackSelector) {

    /** The track shown second: its group and index in it, null for none. Set on the player's own thread. */
    @Volatile
    var wanted: Pair<TrackGroup, Int>? = null
        set(value) {
            field = value
            selector.again()
        }

    /** The second renderers' capabilities, as the selector is handed them, to know them by. */
    private val renderers = mutableSetOf<RendererCapabilities>()

    /** A renderer of the second set, by its capabilities ([androidx.media3.exoplayer.Renderer.getCapabilities]). */
    fun addRenderer(capabilities: RendererCapabilities) {
        renderers += capabilities
    }

    // Those told the cues the second text renderer shows (a view on each screen), on the main thread.
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(List<Cue>) -> Unit>()

    /** [listener] told the cues shown second from now on, on the main thread. */
    fun listen(listener: (List<Cue>) -> Unit) {
        listeners += listener
    }

    /** [listener] no longer told. */
    fun unlisten(listener: (List<Cue>) -> Unit) {
        listeners -= listener
    }

    /**
     * A view over [view]'s picture showing the cues of the track shown second, at the top; with [view]'s player it
     * goes. Null where the player view has no overlay to put it in. The one already there where there is one.
     */
    fun attach(view: androidx.media3.ui.PlayerView): androidx.media3.ui.SubtitleView? {
        val overlay = view.overlayFrameLayout ?: return null
        viewOf(view)?.let { return it }
        val shown = androidx.media3.ui.SubtitleView(view.context).apply { tag = SECOND_VIEW }
        overlay.addView(
            shown,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val listener: (List<Cue>) -> Unit = { cues -> shown.setCues(atTop(cues)) }
        shown.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) = listen(listener)

            override fun onViewDetachedFromWindow(v: android.view.View) = unlisten(listener)
        })
        if (shown.isAttachedToWindow) listen(listener)
        return shown
    }

    /** The view [attach] put over [view]'s picture, or null. */
    fun viewOf(view: androidx.media3.ui.PlayerView): androidx.media3.ui.SubtitleView? =
        view.overlayFrameLayout?.findViewWithTag(SECOND_VIEW)

    private val main = Handler(Looper.getMainLooper())

    /** The second text renderer's output: its cues, handed to every listener. */
    val output = TextOutput { group: CueGroup -> main.post { listeners.forEach { it(group.cues) } } }

    /** The selector for the player: Media3's, with the second track added. */
    val selector = Selector()

    inner class Selector : TrackSelector() {
        fun again() = invalidate(null)

        override fun init(listener: InvalidationListener, bandwidthMeter: BandwidthMeter) {
            super.init(listener, bandwidthMeter)
            media3.init(listener, bandwidthMeter)
        }

        override fun selectTracks(
            rendererCapabilities: Array<out RendererCapabilities>,
            trackGroups: TrackGroupArray,
            periodId: MediaSource.MediaPeriodId,
            timeline: Timeline,
        ): TrackSelectorResult {
            val chosen = media3.selectTracks(rendererCapabilities, trackGroups, periodId, timeline)
            val (group, index) = wanted ?: return chosen
            if (C.TRACK_TYPE_TEXT in media3.parameters.disabledTrackTypes) return chosen
            val target = target(rendererCapabilities, trackGroups, group, index, chosen) ?: return chosen
            val configurations = chosen.rendererConfigurations.copyOf()
            val selections = chosen.selections.copyOf()
            configurations[target] = RendererConfiguration.DEFAULT
            selections[target] = FixedTrackSelection(group, index)
            return TrackSelectorResult(configurations, selections, chosen.tracks, chosen.info)
        }

        override fun onSelectionActivated(info: Any?) = media3.onSelectionActivated(info)

        override fun getParameters(): TrackSelectionParameters = media3.parameters

        override fun setParameters(parameters: TrackSelectionParameters) = media3.setParameters(parameters)

        override fun isSetParametersSupported() = true

        override fun setAudioAttributes(audioAttributes: AudioAttributes) = media3.setAudioAttributes(audioAttributes)

        override fun getRendererCapabilitiesListener() = media3.rendererCapabilitiesListener

        override fun release() {
            media3.release()
            super.release()
        }
    }

    companion object {
        private const val SECOND_VIEW = "mediadroid-second-subtitles"

        /** Where cues shown second start: near the top of the picture, as a share of its height. */
        private const val TOP_LINE = 0.05f

        /** Cues shown second: those without a line of their own at the top of the picture. */
        fun atTop(cues: List<Cue>): List<Cue> = cues.map { cue ->
            if (cue.line != Cue.DIMEN_UNSET) {
                cue
            } else {
                cue.buildUpon().setLine(TOP_LINE, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START).build()
            }
        }

        /**
         * The renderer of the second set that shows track [index] of [group]: one that handles its format, where the
         * group is in what plays and the first does not play the same track already; null where none does.
         */
        fun target(
            capabilities: Array<out RendererCapabilities>,
            trackGroups: TrackGroupArray,
            group: TrackGroup,
            index: Int,
            chosen: TrackSelectorResult,
            second: (RendererCapabilities) -> Boolean,
        ): Int? {
            if (trackGroups.indexOf(group) < 0 || index !in 0 until group.length) return null
            if (chosen.selections.any { it != null && it.trackGroup == group && it.indexOf(index) >= 0 }) return null
            val format = group.getFormat(index)
            return capabilities.indices.firstOrNull { i ->
                second(capabilities[i]) && chosen.selections[i] == null &&
                    RendererCapabilities.getFormatSupport(capabilities[i].supportsFormat(format)) == C.FORMAT_HANDLED
            }
        }
    }

    private fun target(
        capabilities: Array<out RendererCapabilities>,
        trackGroups: TrackGroupArray,
        group: TrackGroup,
        index: Int,
        chosen: TrackSelectorResult,
    ): Int? = target(capabilities, trackGroups, group, index, chosen) { it in renderers }
}
