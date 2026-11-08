package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class LoudnessChainTest {

    private val rate = 48_000

    /**
     * The sound stage as Android's LoudnessAudioProcessor ran it before it moved here, kept as the reference the
     * shared chain must match sample for sample: the same per-buffer gain, the same per-frame night-mode gain.
     */
    private class Reference(sampleRate: Int, private val channels: Int) {
        private val rate = sampleRate
        private val compressor = NightModeCompressor(sampleRate)
        private var normalization: LoudnessNormalization? = null
        private var normalizationFor: FileLoudness? = null
        private var samples = ShortArray(0)

        fun queueInput(
            inputBuffer: ByteBuffer,
            gainDb: Float,
            nightMode: Boolean,
            normalize: Boolean,
            file: FileLoudness?,
        ): ByteBuffer {
            val buffer = ByteBuffer.allocate(inputBuffer.remaining()).order(ByteOrder.LITTLE_ENDIAN)
            val factor = LoudnessCurve.factorFor(gainDb + normalizationDb(inputBuffer, normalize, file))
            val night = if (nightMode) compressor else null
            if (night == null) {
                if (factor == 1f) {
                    buffer.put(inputBuffer)
                } else {
                    while (inputBuffer.hasRemaining()) buffer.putShort(LoudnessCurve.apply(inputBuffer.short, factor))
                }
            } else {
                val frame = ShortArray(channels)
                while (inputBuffer.remaining() >= channels * 2) {
                    var peak = 0
                    for (c in 0 until channels) {
                        frame[c] = inputBuffer.short
                        peak = maxOf(peak, abs(frame[c].toInt()))
                    }
                    val gain = night.gainFor(peak / 32768f) * factor
                    for (c in 0 until channels) buffer.putShort(LoudnessCurve.apply(frame[c], gain))
                }
            }
            buffer.flip()
            return buffer
        }

        private fun normalizationDb(inputBuffer: ByteBuffer, normalize: Boolean, file: FileLoudness?): Float {
            if (!normalize) return 0f
            var current = normalization
            if (current == null || file != normalizationFor) {
                current = LoudnessNormalization(rate, channels, file?.tagGainDb)
                normalization = current
                normalizationFor = file
            }
            val count = inputBuffer.remaining() / 2
            if (samples.size < count) samples = ShortArray(count)
            inputBuffer.duplicate().order(inputBuffer.order()).asShortBuffer().get(samples, 0, count)
            return current.feed(samples, 0, count / channels)
        }
    }

    /** One buffer of a tone that swells and fades, [frames] long, starting at frame [start]. */
    private fun buffer(start: Int, frames: Int, channels: Int): ShortArray = ShortArray(frames * channels) { i ->
        val t = start + i / channels
        val envelope = 0.02 + 0.9 * (0.5 + 0.5 * sin(2 * PI * t / (rate * 1.7)))
        (envelope * 32767 * sin(2 * PI * (220 + 110 * (i % channels)) * t / rate)).toInt().toShort()
    }

    private fun bytes(samples: ShortArray): ByteBuffer =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            asShortBuffer().put(samples)
        }

    private data class Knobs(val gainDb: Float, val night: Boolean, val normalize: Boolean, val file: FileLoudness?)

    private fun assertSameAsReference(channels: Int, seconds: Int, knobs: (Int) -> Knobs) {
        val reference = Reference(rate, channels)
        val chain = LoudnessChain(rate, channels)
        val frames = 1024
        for (n in 0 until rate * seconds / frames) {
            val k = knobs(n)
            val input = buffer(n * frames, frames, channels)
            val expected = reference.queueInput(bytes(input), k.gainDb, k.night, k.normalize, k.file)
            val actual = input.copyOf()
            chain.process(actual, 0, frames, k.gainDb, k.night, k.normalize, k.file)
            val want = ShortArray(input.size).also { expected.asShortBuffer().get(it) }
            assertArrayEquals("buffer $n, $k", want, actual)
        }
    }

    @Test
    fun `matches the former Android stage for every combination of the knobs`() {
        val tagged = FileLoudness("a.flac#1:0", -6.5f)
        for (channels in listOf(1, 2, 6)) {
            for (gain in listOf(0f, -7f, 9f)) {
                for (night in listOf(false, true)) {
                    assertSameAsReference(channels, 1) { Knobs(gain, night, false, null) }
                    assertSameAsReference(channels, 1) { Knobs(gain, night, true, tagged) }
                }
            }
        }
    }

    @Test
    fun `matches the former Android stage while it measures the file`() {
        val measured = FileLoudness("b.mkv#1:0", null)
        for (channels in listOf(2, 6)) {
            for (night in listOf(false, true)) {
                // Four seconds: the measured correction starts after three.
                assertSameAsReference(channels, 4) { Knobs(4f, night, true, measured) }
            }
        }
    }

    @Test
    fun `matches the former Android stage while the knobs move as it plays`() {
        val first = FileLoudness("a.mkv#1:0", null)
        val second = FileLoudness("a.mkv#2:0", -3f)
        assertSameAsReference(2, 10) { n ->
            Knobs(
                gainDb = (n % 37 - 18) / 2f,
                night = n / 50 % 2 == 1,
                normalize = n / 120 % 3 != 2,
                file = if (n < 300) first else second,
            )
        }
    }

    @Test
    fun `untouched at unity, without night mode or even loudness`() {
        val chain = LoudnessChain(rate, 2)
        val input = buffer(0, 512, 2)
        val output = input.copyOf()
        chain.process(output, 0, 512, 0f, false, false, null)
        assertArrayEquals(input, output)
    }

    @Test
    fun `only the frames asked for are touched`() {
        val chain = LoudnessChain(rate, 2)
        val samples = ShortArray(20) { 1000 }
        chain.process(samples, 4, 3, -20f, false, false, null)
        assertEquals(1000.toShort(), samples[3])
        assertEquals(100.toShort(), samples[4])
        assertEquals(100.toShort(), samples[9])
        assertEquals(1000.toShort(), samples[10])
    }

    @Test
    fun `a ReplayGain tag turns the file from the first sample`() {
        val chain = LoudnessChain(rate, 2)
        val samples = ShortArray(64) { 10_000 }
        chain.process(samples, 0, 32, 0f, false, true, FileLoudness("x.flac", -20f))
        assertTrue(samples.all { it == 1000.toShort() })
    }
}
