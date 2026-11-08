package com.hawkynt.mediadroyd.stage

import com.hawkynt.mediadroyd.player.Deinterlacer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeinterlacingTest {

    private val gl = RecordingGles()
    private val deinterlacing = Deinterlacing(Passes(gl))
    private fun frame(texture: Int) = FakeTarget(texture, 32, 24)

    @Test
    fun `a field is rebuilt from the pictures either side, on the rows the field order gives`() {
        deinterlacing.field(frame(1), frame(2), frame(3), frame(4), second = true, method = 5, topFirst = false)
        assertTrue(gl.calls.contains("uPrev = 1"))
        assertTrue(gl.calls.contains("uNext = 2"))
        assertTrue(gl.calls.contains("uSecond = 1"))
        assertTrue(gl.calls.contains("uMethod = 5"))
        assertTrue(gl.calls.contains("uParity = ${Deinterlacer.rebuiltParity(true, false).toFloat()}"))
    }

    @Test
    fun `MC Smooth rebuilds the fields either side with BWDIF, then averages and sharpens`() {
        deinterlacing.smoothAlongMotion(
            frame(1), frame(2), frame(3), frame(4), frame(5), second = true,
            earlier = frame(6), later = frame(7), smoothed = frame(8), into = frame(9),
        )
        assertEquals(4, gl.calls.count { it.startsWith("draw") })
        val bwdif = Deinterlacer.shaderMethod(Deinterlacer.Method.BWDIF)
        assertEquals(2, gl.calls.count { it == "uMethod = $bwdif" })
        assertTrue(gl.calls.contains("framebuffer 1009"))
    }
}
