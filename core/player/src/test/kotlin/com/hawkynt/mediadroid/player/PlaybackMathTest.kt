package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackMathTest {

    @Test
    fun `double tap seeks fifteen seconds and stays inside the video`() {
        assertEquals(75_000L, PlaybackMath.seekBy(60_000L, 600_000L, forward = true))
        assertEquals(45_000L, PlaybackMath.seekBy(60_000L, 600_000L, forward = false))
        assertEquals(0L, PlaybackMath.seekBy(5_000L, 600_000L, forward = false))
        assertEquals(600_000L, PlaybackMath.seekBy(595_000L, 600_000L, forward = true))
        // Unknown duration: only the start bounds it.
        assertEquals(75_000L, PlaybackMath.seekBy(60_000L, -1L, forward = true))
    }

    @Test
    fun `previous restarts when more than three seconds in, else goes back`() {
        assertEquals(PlaybackMath.Previous.Restart, PlaybackMath.previous(positionMs = 3_001L, index = 4))
        assertEquals(PlaybackMath.Previous.GoTo(3), PlaybackMath.previous(positionMs = 2_000L, index = 4))
        assertEquals(PlaybackMath.Previous.Restart, PlaybackMath.previous(positionMs = 1_000L, index = 0))
    }

    @Test
    fun `a swipe over the full height moves a level from bottom to top`() {
        assertEquals(1f, PlaybackMath.level(0f, dragUpFraction = 1f), 1e-6f)
        assertEquals(0.75f, PlaybackMath.level(0.5f, dragUpFraction = 0.25f), 1e-6f)
        assertEquals(0f, PlaybackMath.level(0.2f, dragUpFraction = -0.5f), 1e-6f)
        assertEquals(1f, PlaybackMath.level(0.9f, dragUpFraction = 0.5f), 1e-6f)
    }

    @Test
    fun `pinch zoom stays between the whole picture and four times`() {
        assertEquals(2f, PlaybackMath.zoom(1f, 2f), 1e-6f)
        assertEquals(1f, PlaybackMath.zoom(1.2f, 0.5f), 1e-6f)
        assertEquals(4f, PlaybackMath.zoom(3f, 2f), 1e-6f)
    }

    @Test
    fun `panning a zoomed picture never shows past its edge`() {
        // At 2x the picture is twice the view: it may move half a view each way.
        assertEquals(500f, PlaybackMath.pan(800f, zoom = 2f, viewSize = 1000f), 1e-3f)
        assertEquals(-500f, PlaybackMath.pan(-800f, zoom = 2f, viewSize = 1000f), 1e-3f)
        assertEquals(300f, PlaybackMath.pan(300f, zoom = 2f, viewSize = 1000f), 1e-3f)
        assertEquals(0f, PlaybackMath.pan(300f, zoom = 1f, viewSize = 1000f), 1e-3f)
    }

    @Test
    fun `the last two minutes left count as watched`() {
        val hour = 3_600_000L
        assertFalse(PlaybackMath.isWatched(hour - 121_000, hour, creditsStartMs = null))
        assertTrue(PlaybackMath.isWatched(hour - 120_000, hour, creditsStartMs = null))
        assertTrue(PlaybackMath.isWatched(hour, hour, creditsStartMs = null))
    }

    @Test
    fun `reaching the credits counts as watched`() {
        val hour = 3_600_000L
        assertFalse(PlaybackMath.isWatched(hour - 600_001, hour, creditsStartMs = hour - 600_000))
        assertTrue(PlaybackMath.isWatched(hour - 600_000, hour, creditsStartMs = hour - 600_000))
    }

    @Test
    fun `a short clip is not watched just by starting it`() {
        // Two minutes would be all of a 90 s clip: the tail shrinks to a tenth of the runtime.
        assertFalse(PlaybackMath.isWatched(30_000, 90_000, creditsStartMs = null))
        assertTrue(PlaybackMath.isWatched(81_000, 90_000, creditsStartMs = null))
    }

    @Test
    fun `nothing is watched while the runtime is unknown`() {
        assertFalse(PlaybackMath.isWatched(500_000, 0, creditsStartMs = 100_000))
        assertFalse(PlaybackMath.isWatched(0, 3_600_000, creditsStartMs = 0))
    }

    @Test
    fun `a swipe runs on past the device maximum for a second full stretch`() {
        assertEquals(1.5f, PlaybackMath.boostedLevel(1f, dragUpFraction = 0.5f), 1e-6f)
        assertEquals(2f, PlaybackMath.boostedLevel(1.8f, dragUpFraction = 0.5f), 1e-6f)
        assertEquals(0f, PlaybackMath.boostedLevel(0.2f, dragUpFraction = -0.5f), 1e-6f)
    }

    @Test
    fun `past the maximum the level climbs logarithmically to 800 percent`() {
        assertEquals(70, PlaybackMath.percent(0.7f))
        assertEquals(100, PlaybackMath.percent(1f))
        assertEquals(200, PlaybackMath.percent(1f + 1f / 3f)) // each third of the stretch doubles it
        assertEquals(400, PlaybackMath.percent(1f + 2f / 3f))
        assertEquals(800, PlaybackMath.percent(2f))
    }

    @Test
    fun `volume past the maximum turns into loudness gain in even decibel steps`() {
        assertEquals(0f, PlaybackMath.boostDb(0.7f), 1e-6f)
        assertEquals(0f, PlaybackMath.boostDb(1f), 1e-6f)
        assertEquals(9.0309f, PlaybackMath.boostDb(1.5f), 1e-3f)
        assertEquals(18.0618f, PlaybackMath.boostDb(2f), 1e-3f)
    }

    @Test
    fun `brightness past the maximum turns into a gentler gamma lift`() {
        assertEquals(1f, PlaybackMath.gammaBoost(0.4f), 1e-6f)
        assertEquals(1f, PlaybackMath.gammaBoost(1f), 1e-6f)
        assertEquals(2f, PlaybackMath.gammaBoost(1f + 2f / 3f), 1e-4f) // 400 % → gamma ×2
        assertEquals(2.8284f, PlaybackMath.gammaBoost(2f), 1e-3f)
    }

    @Test
    fun `a mirrored screen keeps its overlays inside the centred 16 by 9 area`() {
        // A 20:9 phone in landscape (2400 x 1080): a 16:9 TV shows the middle 1920 px.
        assertEquals(240f to 0f, PlaybackMath.mirrorSafeInset(2400f, 1080f))
        // In portrait the crop takes the top and bottom.
        assertEquals(0f to 240f, PlaybackMath.mirrorSafeInset(1080f, 2400f))
        // A screen that is 16:9 already, or wider-than-tall less than that, loses nothing.
        assertEquals(0f to 0f, PlaybackMath.mirrorSafeInset(1920f, 1080f))
        assertEquals(0f to 0f, PlaybackMath.mirrorSafeInset(1600f, 1200f))
    }

    @Test
    fun `the film ends when what is left has played at the current speed`() {
        val now = 1_000_000L
        assertEquals(now + 600_000, PlaybackMath.endsAtMs(now, positionMs = 400_000, durationMs = 1_000_000, speed = 1f))
        assertEquals(now + 300_000, PlaybackMath.endsAtMs(now, positionMs = 400_000, durationMs = 1_000_000, speed = 2f))
        assertEquals(null, PlaybackMath.endsAtMs(now, positionMs = 0, durationMs = 0, speed = 1f))
        assertEquals(now, PlaybackMath.endsAtMs(now, positionMs = 1_200_000, durationMs = 1_000_000, speed = 1f))
    }

    @Test
    fun `holding a skip button steps further the longer it is held`() {
        assertEquals(10_000L, PlaybackMath.holdStepMs(heldMs = 0))
        assertEquals(10_000L, PlaybackMath.holdStepMs(heldMs = 2_999))
        assertEquals(30_000L, PlaybackMath.holdStepMs(heldMs = 3_000))
        assertEquals(60_000L, PlaybackMath.holdStepMs(heldMs = 6_000))
        assertEquals(300_000L, PlaybackMath.holdStepMs(heldMs = 9_000))
        assertEquals(300_000L, PlaybackMath.holdStepMs(heldMs = 60_000))
    }

    @Test
    fun `what plays after the end depends on binge and repeat`() {
        val off = PlaybackMath.Repeat.OFF
        assertEquals(1, PlaybackMath.afterEnd(index = 0, size = 3, autoAdvance = true, repeat = off))
        assertEquals(null, PlaybackMath.afterEnd(index = 2, size = 3, autoAdvance = true, repeat = off))
        assertEquals(null, PlaybackMath.afterEnd(index = 0, size = 3, autoAdvance = false, repeat = off))
        // Repeat this video: the same one again, binge or not.
        assertEquals(1, PlaybackMath.afterEnd(index = 1, size = 3, autoAdvance = true, repeat = PlaybackMath.Repeat.ONE))
        // Repeat all: on through the queue and round again, even with binge off.
        assertEquals(0, PlaybackMath.afterEnd(index = 2, size = 3, autoAdvance = false, repeat = PlaybackMath.Repeat.ALL))
        assertEquals(2, PlaybackMath.afterEnd(index = 1, size = 3, autoAdvance = false, repeat = PlaybackMath.Repeat.ALL))
    }

    @Test
    fun `a running-out sleep timer fades the sound over its last ten seconds`() {
        assertEquals(1f, PlaybackMath.sleepFade(remainingMs = 60_000), 1e-6f)
        assertEquals(1f, PlaybackMath.sleepFade(remainingMs = 10_000), 1e-6f)
        assertEquals(0.5f, PlaybackMath.sleepFade(remainingMs = 5_000), 1e-6f)
        assertEquals(0f, PlaybackMath.sleepFade(remainingMs = 0), 1e-6f)
        assertEquals(0f, PlaybackMath.sleepFade(remainingMs = -3_000), 1e-6f)
    }

    @Test
    fun `the sleep timer shows whole minutes, rounding up`() {
        assertEquals(30, PlaybackMath.sleepMinutesLeft(remainingMs = 30 * 60_000L))
        assertEquals(1, PlaybackMath.sleepMinutesLeft(remainingMs = 1_000))
        assertEquals(0, PlaybackMath.sleepMinutesLeft(remainingMs = 0))
    }

    @Test
    fun `audio delay moves in 50 ms steps within two and a half seconds either way`() {
        assertEquals(50, PlaybackMath.nudgeAudioDelay(0, steps = 1))
        assertEquals(-150, PlaybackMath.nudgeAudioDelay(-100, steps = -1))
        assertEquals(2500, PlaybackMath.nudgeAudioDelay(2480, steps = 1))
        assertEquals(-2500, PlaybackMath.nudgeAudioDelay(-2500, steps = -3))
    }

    @Test
    fun `the small window keeps the picture's shape within what Android allows`() {
        assertEquals(1920 to 1080, PlaybackMath.pipAspect(1920, 1080, pixelRatio = 1f))
        // Anamorphic DVD: 720x576 shown 16:9.
        assertEquals(1024 to 576, PlaybackMath.pipAspect(720, 576, pixelRatio = 1024f / 720f))
        // Wider than 2.39:1 or taller than 1:2.39 is held at the limit.
        assertEquals(239 to 100, PlaybackMath.pipAspect(3000, 1000, pixelRatio = 1f))
        assertEquals(100 to 239, PlaybackMath.pipAspect(500, 2000, pixelRatio = 1f))
        assertEquals(null, PlaybackMath.pipAspect(0, 0, pixelRatio = 1f))
    }
}
