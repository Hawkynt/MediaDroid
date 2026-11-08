package com.hawkynt.mediadroyd.player

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The test signals [LoudnessMeterTest] measures, built the same way every time: interleaved float samples in -1..1.
 * Their expected loudness in `loudness/expected.txt` was measured by the oracles beside it (`measure.sh`) from exactly
 * these samples, written out by [main].
 */
object LoudnessSignals {

    /** A signal: its sample rate, channel count and interleaved samples. */
    class Signal(val name: String, val rate: Int, val channels: Int, val samples: FloatArray) {
        val frames get() = samples.size / channels
    }

    private fun level(dbfs: Double) = 10.0.pow(dbfs / 20.0)

    /** [seconds] of a sine of [hz] at [dbfs] peak on every channel. */
    private fun tone(rate: Int, channels: Int, hz: Double, dbfs: Double, seconds: Double): FloatArray {
        val frames = (rate * seconds).toInt()
        val amplitude = level(dbfs)
        return FloatArray(frames * channels) { i ->
            (amplitude * sin(2.0 * PI * hz * (i / channels) / rate)).toFloat()
        }
    }

    /** White noise from a fixed linear congruential generator, flat between -peak and +peak. */
    private fun noise(rate: Int, channels: Int, dbfs: Double, seconds: Double): FloatArray {
        val amplitude = level(dbfs)
        var state = 12345L
        return FloatArray((rate * seconds).toInt() * channels) {
            state = (state * 6364136223846793005L + 1442695040888963407L)
            val unit = (state ushr 11).toDouble() / (1L shl 53).toDouble()
            (amplitude * (2.0 * unit - 1.0)).toFloat()
        }
    }

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    val all: List<Signal> by lazy {
        listOf(
            // EBU Tech 3341 case 1: 1 kHz stereo at -23 dBFS reads -23 LUFS.
            Signal("tone-1k-23dbfs-48k-stereo", 48_000, 2, tone(48_000, 2, 1000.0, -23.0, 20.0)),
            // A low and a high tone together at 44.1 kHz mono: both stages of the K-weighting matter.
            Signal(
                "tones-100hz-5khz-44k1-mono",
                44_100,
                1,
                tone(44_100, 1, 100.0, -12.0, 4.0).let { low ->
                    val high = tone(44_100, 1, 5000.0, -18.0, 4.0)
                    FloatArray(low.size) { low[it] + high[it] }
                },
            ),
            // EBU Tech 3341 case 3 shortened: quiet - loud - quiet; the relative gate drops the quiet parts.
            Signal(
                "gated-36-23-36-48k-stereo",
                48_000,
                2,
                concat(
                    tone(48_000, 2, 1000.0, -36.0, 2.0),
                    tone(48_000, 2, 1000.0, -23.0, 6.0),
                    tone(48_000, 2, 1000.0, -36.0, 2.0),
                ),
            ),
            // Noise, then digital silence: the absolute gate drops the silence (32 kHz, an odd 100 ms count).
            Signal(
                "noise-then-silence-32k-stereo",
                32_000,
                2,
                concat(noise(32_000, 2, -20.0, 3.0), FloatArray(32_000 * 2 * 3)),
            ),
            // 5.1: a different tone per channel; the LFE is not counted and the surrounds weigh 1.41.
            Signal(
                "tones-5.1-48k",
                48_000,
                6,
                tone(48_000, 6, 440.0, -20.0, 3.0).also { s ->
                    for (i in s.indices) s[i] = s[i] * (1 + i % 6) / 6f
                },
            ),
        )
    }

    /** Writes each signal as raw little-endian float32 (`<name>.<rate>.<channels>.f32`) into the folder [args]`[0]`. */
    @JvmStatic
    fun main(args: Array<String>) {
        val folder = File(args.firstOrNull() ?: "loudness-signals").apply { mkdirs() }
        for (signal in all) {
            val bytes = ByteBuffer.allocate(signal.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            signal.samples.forEach { bytes.putFloat(it) }
            File(folder, "${signal.name}.${signal.rate}.${signal.channels}.f32").writeBytes(bytes.array())
        }
    }
}
