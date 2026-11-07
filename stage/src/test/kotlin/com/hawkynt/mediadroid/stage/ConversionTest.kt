package com.hawkynt.mediadroid.stage

import com.hawkynt.mediadroid.player.ToneMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversionTest {

    private val gl = RecordingGles()
    private val conversion = Conversion(Passes(gl))
    private val planes = Conversion.Planes(
        luma = 1, cb = 2, cr = 3, width = 99, height = 41, lumaStride = 128, chromaStride = 64,
        color = FloatArray(9), foot = 16f / 255f, matrix = Passes.IDENTITY,
    )

    @Test
    fun `spline36 reads the chroma by its stride, KrigBilateral the luma by its own`() {
        conversion.spline(planes, FakeTarget(9, 99, 41))
        assertTrue(gl.calls.contains("uChromaSize = 64.0, 21.0"))
        assertTrue(gl.calls.contains("uChromaWidth = 50.0"))
        gl.calls.clear()
        conversion.krig(planes, FakeTarget(5, 99, 21), FakeTarget(6, 50, 21), FakeTarget(9, 99, 41))
        assertEquals(3, gl.calls.count { it.startsWith("draw") })
        assertTrue(gl.calls.contains("uLumaSize = 128.0, 41.0"))
        assertTrue(gl.calls.contains("uLumaStride = 128.0"))
        assertTrue(gl.calls.contains("uLow = 3"))
    }

    @Test
    fun `the peak is measured over the coded picture's size`() {
        conversion.peak(FakeTarget(1, 64, 48), FakeTarget(2, 2, 2), ToneMap.Transfer.PQ)
        assertTrue(gl.calls.contains("uSize = 64.0, 48.0"))
    }
}
