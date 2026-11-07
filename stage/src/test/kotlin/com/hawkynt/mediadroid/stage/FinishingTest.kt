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
    fun `RIFE's flow from a quarter of the size warps the frames at theirs, grown four times each way`() {
        finishing.warp(FakeTarget(1, 64, 48), FakeTarget(2, 64, 48), 3, 4, FakeTarget(5, 64, 48), 16, 12)
        assertTrue(gl.calls.contains("uScale = 4.0, 4.0"))
        assertTrue(gl.calls.contains("uSize = 64.0, 48.0"))
        assertTrue(gl.calls.contains("uPrevious = 1"))
        assertTrue(gl.calls.contains("uFlow = 2"))
        assertTrue(gl.calls.contains("uMask = 3"))
    }

    @Test
    fun `float tables are made once per size and refilled after`() {
        val passes = Passes(gl)
        val data = java.nio.ByteBuffer.allocateDirect(16 * 12 * 4 * 4)
        val first = passes.floats(0, 16, 12, 4, data)
        assertEquals(first, passes.floats(first, 16, 12, 4, data))
        assertEquals(1, gl.calls.count { it.startsWith("texImage") })
        assertEquals(1, gl.calls.count { it.startsWith("texSubImage") })
        assertTrue(passes.floats(first, 32, 24, 4, java.nio.ByteBuffer.allocateDirect(32 * 24 * 16)) != first)
    }

    @Test
    fun `the colour correction carries the warp`() {
        finishing.adjust(FakeTarget(1, 8, 8), FakeTarget(2, 8, 8), PictureAdjustment.Neutral, ScreenWarp.None)
        assertTrue(gl.calls.contains("uWarp = matrix3"))
        assertTrue(gl.calls.contains("uWarped = 1.0"))
    }
}
