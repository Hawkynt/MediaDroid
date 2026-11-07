package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PlaybackMath
import com.hawkynt.mediadroid.state.Settings

/**
 * Settings → Even frames on this computer, as the phone plays it (PlaybackMath.evenPlayback): the film run a little
 * faster or slower — within the household's limit, in its direction — so that at the screen's refresh every frame
 * stays on screen equally long; where no such rate is within reach, the picture blended at every refresh instead
 * (PlaybackMath.smoothedWhenUneven). The screen keeps its mode: the desktop does not switch a monitor's refresh rate.
 */
class EvenFrames(private val settings: Settings) {

    /** The factor on top of the chosen speed. */
    var speedFactor: Float = 1f
        private set

    private var even = true

    /** Plans for a [frameRate] film at [speed] on a screen refreshing every [refreshNs] nanoseconds. */
    fun plan(frameRate: Float, speed: Float, refreshNs: Long) {
        val hz = if (refreshNs > 0) 1e9f / refreshNs else 0f
        val direction = runCatching { PlaybackMath.JudderDirection.valueOf(settings.judderDirection) }
            .getOrDefault(PlaybackMath.JudderDirection.NEAREST)
        val plan = PlaybackMath.evenPlayback(
            frameRate, speed, listOf(hz), hz, canSwitch = false,
            maxChange = settings.judderLimitPercent / 100f, direction = direction,
        )
        speedFactor = plan.speedFactor
        even = plan.even
    }

    /** [picture] as shown under the plan: blended at every refresh where Even frames is on and finds no even rate. */
    fun shown(picture: PictureAdjustment): PictureAdjustment = PlaybackMath.smoothedWhenUneven(
        picture, PlaybackMath.EvenPlan(0f, speedFactor, even), reducingJudder = settings.judderLimitPercent > 0,
    )
}
