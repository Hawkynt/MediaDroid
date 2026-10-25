package com.hawkynt.mediadroid.player

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessCurveTest {

    @Test
    fun `zero dB is unity gain`() {
        assertEquals(1f, LoudnessCurve.factorFor(0f), 1e-6f)
    }

    @Test
    fun `six dB doubles and halves the amplitude`() {
        assertEquals(2f, LoudnessCurve.factorFor(6f), 0.01f)
        assertEquals(0.5f, LoudnessCurve.factorFor(-6f), 0.01f)
    }

    @Test
    fun `twenty dB is ten times the amplitude`() {
        assertEquals(10f, LoudnessCurve.factorFor(20f), 0.01f)
    }

    @Test
    fun `unity leaves the sample alone`() {
        assertEquals(12345.toShort(), LoudnessCurve.apply(12345, 1f))
    }

    @Test
    fun `attenuation scales linearly`() {
        assertEquals(5000.toShort(), LoudnessCurve.apply(10000, 0.5f))
        assertEquals((-5000).toShort(), LoudnessCurve.apply(-10000, 0.5f))
    }

    @Test
    fun `boost saturates instead of wrapping`() {
        val loud = LoudnessCurve.apply(30000, 4f)
        assertTrue("expected saturation below full scale, got $loud", loud in (30000..32767))
        val quiet = LoudnessCurve.apply(-30000, 4f)
        assertTrue("expected negative saturation, got $quiet", quiet in (-32768..-30000))
    }

    @Test
    fun `boost still lifts quiet samples`() {
        assertTrue(LoudnessCurve.apply(2000, 4f) > 2000)
        assertTrue(LoudnessCurve.apply(-2000, 4f) < -2000)
    }

    @Test
    fun `extreme boost never exceeds the sample range`() {
        for (sample in listOf(-32768, -30000, -1, 0, 1, 30000, 32767)) {
            val out = LoudnessCurve.apply(sample.toShort(), 100f)
            assertTrue("sample $sample overflowed to $out", abs(out.toInt()) <= 32768)
        }
    }
}
