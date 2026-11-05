package com.hawkynt.mediadroid.player

import kotlin.math.round
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessMeterTest {

    /** The oracles' values per signal (libebur128 to 1e-6, FFmpeg to 0.1 LU); see loudness/expected.txt. */
    private val expected: Map<String, Pair<Double, Double>> =
        javaClass.getResource("/loudness/expected.txt")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val (name, libebur128, ffmpeg) = line.trim().split(Regex("\\s+"))
                name to (libebur128.toDouble() to ffmpeg.toDouble())
            }

    private fun measure(signal: LoudnessSignals.Signal, chunk: Int = 1024): Double {
        val meter = LoudnessMeter(signal.rate, signal.channels)
        var frame = 0
        while (frame < signal.frames) {
            val frames = minOf(chunk, signal.frames - frame)
            meter.add(signal.samples, frame * signal.channels, frames)
            frame += frames
        }
        return meter.integratedLufs
    }

    @Test
    fun everySignalMeasuresWhatLibebur128AndFfmpegMeasure() {
        assertEquals(LoudnessSignals.all.map { it.name }.toSet(), expected.keys)
        for (signal in LoudnessSignals.all) {
            val (libebur128, ffmpeg) = expected.getValue(signal.name)
            val measured = measure(signal)
            assertEquals(signal.name, libebur128, measured, 1e-4)
            assertEquals(signal.name, ffmpeg, measured, 0.05 + 1e-9)
        }
    }

    @Test
    fun howTheSoundArrivesInChunksDoesNotMatter() {
        val signal = LoudnessSignals.all.first { it.name.startsWith("gated") }
        assertEquals(measure(signal, 1), measure(signal, 48_000), 1e-9)
    }

    @Test
    fun sixteenBitSoundIsScaledToFullScale() {
        val signal = LoudnessSignals.all.first { it.name.startsWith("tone-1k") }
        val meter = LoudnessMeter(signal.rate, signal.channels)
        val pcm = ShortArray(signal.samples.size) { round(signal.samples[it] * 32768f).toInt().toShort() }
        meter.add(pcm, 0, signal.frames)
        assertEquals(expected.getValue(signal.name).first, meter.integratedLufs, 0.01)
    }

    @Test
    fun lessThanOneBlockOrOnlySilenceHasNoLoudness() {
        val short = LoudnessMeter(48_000, 2)
        short.add(FloatArray(2 * 19_000) { 0.5f }, 0, 19_000)
        assertEquals(Double.NEGATIVE_INFINITY, short.integratedLufs, 0.0)
        val silent = LoudnessMeter(48_000, 2)
        silent.add(FloatArray(2 * 96_000), 0, 96_000)
        assertEquals(Double.NEGATIVE_INFINITY, silent.integratedLufs, 0.0)
        assertTrue(silent.measuredSeconds > 1.9)
    }
}
