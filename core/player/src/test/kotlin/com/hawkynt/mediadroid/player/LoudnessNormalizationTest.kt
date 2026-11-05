package com.hawkynt.mediadroid.player

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessNormalizationTest {

    private val rate = 48_000
    private val chunk = 960

    /** 20 ms of a 1 kHz stereo tone at [dbfs], as 16-bit PCM — the tone reads [dbfs] LUFS (EBU Tech 3341). */
    private fun tone(dbfs: Double): ShortArray {
        val amplitude = 32767 * 10.0.pow(dbfs / 20.0)
        return ShortArray(chunk * 2) { i ->
            (amplitude * sin(2.0 * PI * 1000.0 * (i / 2) / rate)).roundToInt().toShort()
        }
    }

    /** Plays [seconds] of [pcm] through [normalization] and returns the gain it gave each second. */
    private fun play(normalization: LoudnessNormalization, pcm: ShortArray, seconds: Int): List<Float> {
        val gains = mutableListOf<Float>()
        repeat(seconds * rate / chunk) { i ->
            val gain = normalization.feed(pcm, 0, chunk)
            if ((i + 1) % (rate / chunk) == 0) gains += gain
        }
        return gains
    }

    @Test
    fun aReplayGainTagIsPlayedFromTheStart() {
        val normalization = LoudnessNormalization(rate, 2, tagGainDb = -4.5f)
        assertEquals(-4.5f, normalization.feed(tone(-10.0), 0, chunk), 0f)
        assertTrue(play(normalization, tone(-40.0), 5).all { it == -4.5f })
    }

    @Test
    fun aQuietFileIsMeasuredAndBroughtUpToTheReference() {
        val gains = play(LoudnessNormalization(rate, 2, null), tone(-26.0), 15)
        // Nothing while the first seconds are measured, then a gentle ramp, no faster than the limit.
        assertEquals(0f, gains[1], 0f)
        gains.zipWithNext().forEach { (a, b) ->
            assertTrue("$a -> $b", b - a <= LoudnessNormalization.RAMP_DB_PER_S + 1e-3f)
        }
        // -26 LUFS to the -18 LUFS reference.
        assertEquals(8f, gains.last(), 0.05f)
    }

    @Test
    fun aLoudFileComesDown() {
        val gains = play(LoudnessNormalization(rate, 2, null), tone(-8.0), 15)
        assertEquals(-10f, gains.last(), 0.05f)
    }

    @Test
    fun theCorrectionIsBounded() {
        val gains = play(LoudnessNormalization(rate, 2, null), tone(-50.0), 20)
        assertEquals(LoudnessNormalization.MAX_BOOST_DB, gains.last(), 0f)
    }

    @Test
    fun silenceIsLeftAlone() {
        val gains = play(LoudnessNormalization(rate, 2, null), ShortArray(chunk * 2), 8)
        assertTrue(gains.all { it == 0f })
    }
}
