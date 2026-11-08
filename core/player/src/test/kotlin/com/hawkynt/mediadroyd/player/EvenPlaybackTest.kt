package com.hawkynt.mediadroyd.player

import com.hawkynt.mediadroyd.player.PlaybackMath.JudderDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class EvenPlaybackTest {
    private fun plan(
        frameRate: Float,
        speed: Float,
        modes: List<Float>,
        current: Float,
        switch: Boolean = true,
        maxChange: Float = 0.05f,
    ) = PlaybackMath.evenPlayback(frameRate, speed, modes, current, switch, maxChange, JudderDirection.NEAREST)

    @Test
    fun `a mode that is a whole multiple of the film's rate is chosen, the speed untouched`() {
        assertEquals(PlaybackMath.EvenPlan(50f, 1f), plan(25f, 1f, listOf(60f, 50f, 120f), current = 60f))
    }

    @Test
    fun `the chosen speed counts, 24 fps at 1,25x is 30 a second, even on 60 Hz`() {
        assertEquals(PlaybackMath.EvenPlan(60f, 1f), plan(24f, 1.25f, listOf(48f, 60f, 120f), current = 48f))
    }

    @Test
    fun `without a whole multiple, the mode needing the smallest change within the limit`() {
        // 25 fps: 24 on 120 Hz is 4 % slower, 30 or 20 on 60 Hz is 20 % away.
        assertEquals(PlaybackMath.EvenPlan(120f, 0.96f), plan(25f, 1f, listOf(60f, 120f), current = 60f))
    }

    @Test
    fun `where the screen may not switch, only the speed changes`() {
        val plan = plan(23.976f, 1f, listOf(60f, 120f), current = 120f, switch = false)
        assertEquals(120f, plan.refreshHz)
        assertEquals(24f / 23.976f, plan.speedFactor, 1e-4f)
    }

    @Test
    fun `nothing within reach leaves the screen as it is and the speed alone`() {
        assertEquals(PlaybackMath.EvenPlan(60f, 1f, even = false), plan(37.5f, 1f, listOf(60f), current = 60f))
    }

    @Test
    fun `between two equally good modes the current one stays`() {
        assertEquals(PlaybackMath.EvenPlan(120f, 1f), plan(30f, 1f, listOf(60f, 120f), current = 120f))
    }

    @Test
    fun `23,976 fps at 1,75x on a 60 Hz-only screen cannot play evenly within 5 percent`() {
        val plan = plan(23.976f, 1.75f, listOf(60f), current = 60f)
        assertEquals(false, plan.even)
        assertEquals(1f, plan.speedFactor)
    }

    @Test
    fun `a plan that reaches an even rate says so`() {
        assertEquals(true, plan(25f, 1f, listOf(60f, 120f), current = 60f).even)
        assertEquals(true, plan(29.97f, 1f, listOf(60f), current = 60f).even)
    }

    private val still = PictureAdjustment()

    @Test
    fun `out of reach, the picture blends in-betweens at the screen's rate`() {
        val uneven = PlaybackMath.EvenPlan(60f, 1f, even = false)
        assertEquals(
            PictureAdjustment.Interpolation.HIGH,
            PlaybackMath.smoothedWhenUneven(still, uneven, reducingJudder = true).interpolation,
        )
    }

    @Test
    fun `an even plan, no judder reduction, or the video's own smooth motion leave the picture as it is`() {
        val uneven = PlaybackMath.EvenPlan(60f, 1f, even = false)
        assertEquals(still, PlaybackMath.smoothedWhenUneven(still, PlaybackMath.EvenPlan(60f, 1f), true))
        assertEquals(still, PlaybackMath.smoothedWhenUneven(still, uneven, reducingJudder = false))
        val own = still.copy(interpolation = PictureAdjustment.Interpolation.MOTION)
        assertEquals(own, PlaybackMath.smoothedWhenUneven(own, uneven, reducingJudder = true))
    }
}
