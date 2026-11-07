package com.hawkynt.mediadroid.stage

import com.hawkynt.mediadroid.player.Deband
import com.hawkynt.mediadroid.player.TemporalDenoise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorationTest {

    private val gl = RecordingGles()
    private val restoration = Restoration(gl, Passes(gl))
    private val picture = FakeTarget(1, 40, 30)
    private val into = FakeTarget(2, 40, 30)

    @Test
    fun `PAL's comb reads the picture two before, NTSC's only the pictures either side`() {
        restoration.dotCrawl(picture, FakeTarget(3, 40, 30), FakeTarget(4, 40, 30), null, into, 8f)
        assertFalse(gl.names.contains("uEarlier"))
        restoration.dotCrawl(picture, FakeTarget(3, 40, 30), FakeTarget(4, 40, 30), FakeTarget(5, 40, 30), into, 8f)
        assertTrue(gl.calls.contains("uEarlier = 1"))
        assertTrue(gl.calls.contains("uThreshold = 8.0"))
    }

    @Test
    fun `temporal denoising leaves the vector and block tables read texel by texel`() {
        val prev = Restoration.Side(FakeTarget(3, 40, 30), 50, 1f, 3, 2, 16, grid = true)
        val next = Restoration.Side(FakeTarget(4, 40, 30), 51, 0f)
        restoration.temporalDenoise(picture, prev, next, 52, TemporalDenoise.Params(4f, 8f), into)
        val draw = gl.calls.indexOfFirst { it.startsWith("draw") }
        val after = gl.calls.drop(draw)
        val min = "parameter ${Gles.TEXTURE_MIN_FILTER}"
        assertEquals("texture 52", after.last { it.startsWith("texture") })
        assertEquals("$min ${Gles.NEAREST}", after.last { it.startsWith(min) })
        assertTrue(gl.calls.contains("uPrevGrid = 3.0, 2.0"))
        assertFalse(gl.names.contains("uNextGrid"))
    }

    @Test
    fun `each debanding is the next frame of its random numbers, libplacebo's 8-bit counter`() {
        val d = Deband.Params(1, 3f, 16f, 2f)
        repeat(3) { restoration.deband(picture, into, d) }
        assertTrue(gl.calls.contains("uFrame = 3.0"))
        assertTrue(gl.calls.contains("uRows = 30.0, -1.0"))
    }
}
