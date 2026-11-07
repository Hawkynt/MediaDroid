package com.hawkynt.mediadroid.stage

import com.hawkynt.mediadroid.player.MotionInterpolation
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.ScreenWarp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishingTest {

    private val gl = RecordingGles()
    private val finishing = Finishing(Passes(gl))

    @Test
    fun `a copy is a blend wholly of the one picture`() {
        finishing.copy(FakeTarget(1, 8, 8), FakeTarget(2, 8, 8))
        assertTrue(gl.calls.contains("uWeight = 1.0"))
        assertEquals(2, gl.calls.count { it == "texture 1" })
    }

    @Test
    fun `Motion searches at its scale's share of the size, its weight in minterpolate's steps`() {
        finishing.motion(FakeTarget(1, 64, 48), FakeTarget(2, 64, 48), 3, 4, FakeTarget(5, 64, 48), 0.5f, 4, 3, 2)
        assertTrue(gl.calls.contains("uSearch = 32.0, 24.0"))
        assertTrue(gl.calls.contains("uAlpha = ${(0.5f * MotionInterpolation.ALPHA_MAX).toInt().toFloat()}"))
        assertTrue(gl.calls.contains("uObmc = 3"))
    }

    @Test
    fun `the dither counts rows from the top, its noise table made once`() {
        val into = FakeTarget(2, 30, 20)
        finishing.dither(FakeTarget(1, 30, 20), into)
        finishing.dither(FakeTarget(1, 30, 20), into)
        assertTrue(gl.calls.contains("uRows = 20.0, -1.0"))
        assertEquals(1, gl.calls.count { it.startsWith("texImage") })
    }

    @Test
    fun `the colour correction carries the warp`() {
        finishing.adjust(FakeTarget(1, 8, 8), FakeTarget(2, 8, 8), PictureAdjustment.Neutral, ScreenWarp.None)
        assertTrue(gl.calls.contains("uWarp = matrix3"))
        assertTrue(gl.calls.contains("uWarped = 1.0"))
    }
}
