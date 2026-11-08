package com.hawkynt.mediadroyd.desktop

import com.hawkynt.mediadroyd.player.PictureAdjustment
import com.hawkynt.mediadroyd.player.PlaybackMath
import com.hawkynt.mediadroyd.state.Settings

/**
 * Settings → Match the refresh rate and Even frames on this computer, as the phone plays them
 * (PlaybackMath.evenPlayback): with matching on, the monitor switched to a mode whose refresh is a whole multiple of
 * the film's rate at its speed ([matcher], RefreshMatcher), put back once matching is switched off; otherwise, or where
 * no mode fits, the film run a little faster or slower — within the household's limit, in its direction — so that at
 * the screen's refresh every frame stays on screen equally long; where no such rate is within reach, the picture
 * blended at every refresh instead (PlaybackMath.smoothedWhenUneven).
 */
class EvenFrames(private val settings: Settings, private val matcher: RefreshMatcher? = null) {

    /** The factor on top of the chosen speed. */
    var speedFactor: Float = 1f
        private set

    private var even = true

    /**
     * Plans for a [frameRate] film at [speed] on a screen refreshing every [refreshNs] nanoseconds — the monitor the
     * window is on ([output], asked only where matching is on) switched where that fits.
     */
    fun plan(frameRate: Float, speed: Float, refreshNs: Long, output: () -> DisplayOutput? = { null }) {
        val direction = runCatching { PlaybackMath.JudderDirection.valueOf(settings.judderDirection) }
            .getOrDefault(PlaybackMath.JudderDirection.NEAREST)
        val maxChange = settings.judderLimitPercent / 100f
        if (matcher != null && !settings.matchRefreshRate && matcher.switched) matcher.restore()
        val monitor = if (matcher != null && settings.matchRefreshRate) output() else null
        val plan = if (matcher != null && monitor != null) {
            matcher.match(monitor, frameRate, speed, maxChange, direction)
        } else {
            val hz = if (refreshNs > 0) 1e9f / refreshNs else 0f
            PlaybackMath.evenPlayback(
                frameRate, speed, listOf(hz), hz, canSwitch = false, maxChange = maxChange, direction = direction,
            )
        }
        speedFactor = plan.speedFactor
        even = plan.even
    }

    /** [picture] as shown under the plan: blended at every refresh where Even frames is on and finds no even rate. */
    fun shown(picture: PictureAdjustment): PictureAdjustment = PlaybackMath.smoothedWhenUneven(
        picture, PlaybackMath.EvenPlan(0f, speedFactor, even),
        reducingJudder = settings.judderLimitPercent > 0 || settings.matchRefreshRate && matcher != null,
    )
}
