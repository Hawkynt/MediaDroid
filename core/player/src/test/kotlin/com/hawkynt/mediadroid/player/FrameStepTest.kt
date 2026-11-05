package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameStepTest {

    @Test
    fun `a step lands on the start of the next or the previous frame`() {
        // 25 frames a second: 40 ms each.
        assertEquals(1_040L, FrameStep.target(1_000, 25f, forward = true, durationMs = 10_000))
        assertEquals(960L, FrameStep.target(1_000, 25f, forward = false, durationMs = 10_000))
    }

    @Test
    fun `a target never lies past the frame's start, as players show the first frame from there on`() {
        // 24 frames a second: frame 1 starts at 41.67 ms; 41 still shows it, 42 would show frame 2.
        assertEquals(41L, FrameStep.target(0, 24f, forward = true, durationMs = 10_000))
        assertEquals(83L, FrameStep.target(41, 24f, forward = true, durationMs = 10_000))
    }

    @Test
    fun `many steps do not drift off the frames`() {
        val fps = 24_000f / 1_001f
        var at = 0L
        repeat(1_000) { at = FrameStep.target(at, fps, forward = true, durationMs = 3_600_000) }
        assertEquals(Math.floorDiv(1_000L * 1_001_000L, 24_000L), at)
        repeat(1_000) { at = FrameStep.target(at, fps, forward = false, durationMs = 3_600_000) }
        assertEquals(0L, at)
    }

    @Test
    fun `a position between frames steps from the frame showing there`() {
        // 25 fps, 1 013 ms is inside the frame starting at 1 000 ms.
        assertEquals(1_040L, FrameStep.target(1_013, 25f, forward = true, durationMs = 10_000))
        assertEquals(960L, FrameStep.target(1_013, 25f, forward = false, durationMs = 10_000))
    }

    @Test
    fun `steps stay inside the video`() {
        assertEquals(0L, FrameStep.target(0, 25f, forward = false, durationMs = 10_000))
        assertEquals(10_000L, FrameStep.target(10_000, 25f, forward = true, durationMs = 10_000))
        // An unknown length only bounds the start.
        assertEquals(1_040L, FrameStep.target(1_000, 25f, forward = true, durationMs = 0))
    }

    @Test
    fun `an unknown frame rate steps as film does`() {
        assertEquals(
            FrameStep.target(1_000, FrameStep.FALLBACK_FPS, forward = true, durationMs = 0),
            FrameStep.target(1_000, 0f, forward = true, durationMs = 0),
        )
        assertEquals(
            FrameStep.target(1_000, FrameStep.FALLBACK_FPS, forward = true, durationMs = 0),
            FrameStep.target(1_000, Float.NaN, forward = true, durationMs = 0),
        )
    }
}
