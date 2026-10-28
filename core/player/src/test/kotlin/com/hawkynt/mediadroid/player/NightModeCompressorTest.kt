package com.hawkynt.mediadroid.player

import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeCompressorTest {

    private val rate = 48_000

    /** Feeds [seconds] of a steady level and returns the gain (dB) the compressor settles on. */
    private fun settle(compressor: NightModeCompressor, peak: Float, seconds: Float): Float {
        var gain = 1f
        repeat((rate * seconds).toInt()) { gain = compressor.gainFor(peak) }
        return 20f * log10(gain)
    }

    private fun db(level: Float) = 20f * log10(level)

    @Test
    fun theGainCurveIsTheTextbookSoftKnee() {
        val c = NightModeCompressor(rate)
        // Well below the knee: unchanged.
        assertEquals(-50f, c.staticCurveDb(-50f), 1e-4f)
        // Well above: threshold plus a quarter of the excess.
        assertEquals(-30f + 20f / 4f, c.staticCurveDb(-10f), 1e-4f)
        // At the threshold, inside the knee: half way between the two lines.
        assertEquals(-30f - 0.9375f, c.staticCurveDb(-30f), 1e-4f)
    }

    @Test
    fun loudPartsComeDownAndQuietPartsComeUp() {
        val loud = NightModeCompressor(rate)
        val loudOut = db(0.9f) + settle(loud, 0.9f, 1f)
        val quiet = NightModeCompressor(rate)
        val quietOut = db(0.01f) + settle(quiet, 0.01f, 1f)
        // A 39 dB gap between dialogue and explosion shrinks to well under 20 dB.
        assertTrue("loud $loudOut quiet $quietOut", loudOut - quietOut < 20f)
        assertTrue("quiet parts are lifted", quietOut > db(0.01f) + 6f)
        assertTrue("peaks stay below full scale", loudOut < 0f)
    }

    @Test
    fun itClampsDownFastAndLetsGoSlowly() {
        val c = NightModeCompressor(rate)
        settle(c, 0.01f, 1f)
        val afterAttack = settle(c, 0.9f, 0.02f) // 20 ms: four attack time constants
        val settledLoud = settle(c, 0.9f, 1f)
        assertEquals(settledLoud, afterAttack, 1f)
        val afterRelease = settle(c, 0.01f, 0.02f) // 20 ms of quiet: still mostly clamped
        assertTrue("release is slow: $afterRelease vs $settledLoud", afterRelease < settledLoud + 6f)
    }

    @Test
    fun silenceStaysSilentWithoutBlowingUp() {
        val c = NightModeCompressor(rate)
        val gain = settle(c, 0f, 0.1f)
        assertTrue(gain.isFinite())
    }
}
