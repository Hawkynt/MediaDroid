package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralPaceTest {
    private val frame = 40_000_000L // 25 frames a second
    private val fullHd = NeuralInterpolation.Size(1920, 1080)
    private val sd = NeuralInterpolation.Size(854, 480)

    @Test
    fun `it starts where the self-test left it, the picture's own size above, halves below down to 120 lines`() {
        val pace = NeuralPace(fullHd, sd)
        assertEquals(
            listOf(fullHd, sd, NeuralInterpolation.Size(428, 240), NeuralInterpolation.Size(214, 120)),
            pace.sizes,
        )
        assertEquals(sd, pace.size)
        assertEquals(listOf(sd, NeuralInterpolation.Size(428, 240), NeuralInterpolation.Size(214, 120)),
            NeuralPace(sd, sd).sizes)
    }

    @Test
    fun `an in-between over half the frame's time halves the size at once`() {
        val pace = NeuralPace(fullHd, sd)
        pace.report(25_000_000L, frame)
        assertEquals(NeuralInterpolation.Size(428, 240), pace.size)
    }

    @Test
    fun `steady room for the next size up doubles it again, but only after a while`() {
        val pace = NeuralPace(fullHd, sd)
        pace.report(25_000_000L, frame)
        repeat(StepPace.CALM_FRAMES - 1) { pace.report(3_000_000L, frame) }
        assertEquals(NeuralInterpolation.Size(428, 240), pace.size)
        pace.report(3_000_000L, frame)
        assertEquals(sd, pace.size)
    }

    @Test
    fun `the step up to the picture's own size waits for room for all its extra pixels`() {
        // 1080p has five times 480p's pixels: 3 ms fits four times over, not five.
        val pace = NeuralPace(fullHd, sd)
        repeat(40) { pace.report(3_500_000L, frame) }
        assertEquals(sd, pace.size)
        repeat(40) { pace.report(2_000_000L, frame) }
        assertEquals(fullHd, pace.size)
    }

    @Test
    fun `a film at 24 frames a second has its own, longer budget`() {
        val pace = NeuralPace(fullHd, sd)
        pace.report(20_500_000L, 41_708_333L)
        assertEquals(sd, pace.size)
        pace.report(20_500_000L, frame)
        assertEquals(NeuralInterpolation.Size(428, 240), pace.size)
    }

    @Test
    fun `on a thread of its own the network has three quarters of the frame's time`() {
        val ownThread = NeuralPace(fullHd, sd, NeuralPace.OWN_THREAD_PERCENT)
        ownThread.report(28_000_000L, frame)
        assertEquals(sd, ownThread.size)
        val drawing = NeuralPace(fullHd, sd)
        drawing.report(28_000_000L, frame)
        assertTrue(drawing.size.height < sd.height)
    }
}
