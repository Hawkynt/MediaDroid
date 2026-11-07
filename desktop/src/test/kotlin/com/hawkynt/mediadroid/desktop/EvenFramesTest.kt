package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.state.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class EvenFramesTest {

    private val hz60 = 1_000_000_000L / 60

    @Test
    fun `off, a film plays at its speed whatever the screen's refresh`() {
        val even = EvenFrames(Settings())
        even.plan(24f, 1f, hz60)
        assertEquals(1f, even.speedFactor)
        assertEquals(PictureAdjustment(), even.shown(PictureAdjustment()))
    }

    @Test
    fun `on, an NTSC video on a 60 Hz screen runs at 30 so every frame stays equally long`() {
        val even = EvenFrames(Settings(judderLimitPercent = 5))
        even.plan(29.97f, 1f, hz60)
        assertEquals(30f / 29.97f, even.speedFactor, 1e-4f)
    }

    @Test
    fun `on, with no even rate within reach the picture is blended at every refresh instead`() {
        val even = EvenFrames(Settings(judderLimitPercent = 1))
        even.plan(25f, 1f, hz60)
        assertEquals(1f, even.speedFactor)
        assertEquals(PictureAdjustment.Interpolation.HIGH, even.shown(PictureAdjustment()).interpolation)
    }
}
