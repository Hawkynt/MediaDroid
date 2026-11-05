package com.hawkynt.mediadroid.engine.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class TempoTest {

    /** [seconds] of a [hz] tone as 16-bit stereo PCM at the reader's rate. */
    private fun tone(hz: Double, seconds: Double): ByteArray {
        val frames = (MediaReader.SAMPLE_RATE * seconds).toInt()
        val buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { i ->
            val v = (sin(2 * PI * hz * i / MediaReader.SAMPLE_RATE) * 12_000).toInt().toShort()
            buffer.putShort(v).putShort(v)
        }
        return buffer.array()
    }

    /** The tone's pitch, from how often the left channel crosses zero upwards. */
    private fun pitch(pcm: ByteArray): Double {
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val frames = pcm.size / 4
        var crossings = 0
        var last = buffer.getShort(0)
        for (i in 1 until frames) {
            val v = buffer.getShort(i * 4)
            if (last < 0 && v >= 0) crossings++
            last = v
        }
        return crossings * MediaReader.SAMPLE_RATE.toDouble() / frames
    }

    private fun stretch(tempo: Tempo, pcm: ByteArray): ByteArray {
        val chunk = 4 * 1024
        val out = java.io.ByteArrayOutputStream()
        for (at in pcm.indices step chunk) out.write(tempo.process(pcm.copyOfRange(at, minOf(pcm.size, at + chunk))))
        return out.toByteArray()
    }

    @Test
    fun `at normal speed the sound passes as it is`() {
        val pcm = tone(440.0, 0.5)
        Tempo().use { assertArrayEquals(pcm, stretch(it, pcm)) }
    }

    @Test
    fun `twice as fast takes half the time and keeps the pitch`() {
        val pcm = tone(440.0, 2.0)
        val out = Tempo().use {
            it.speed = 2f
            stretch(it, pcm)
        }
        assertEquals(1.0, out.size / 4.0 / MediaReader.SAMPLE_RATE, 0.1)
        assertEquals(440.0, pitch(out), 15.0)
    }

    @Test
    fun `slower takes longer and keeps the pitch`() {
        val pcm = tone(440.0, 1.0)
        val out = Tempo().use {
            it.speed = 0.5f
            stretch(it, pcm)
        }
        assertEquals(2.0, out.size / 4.0 / MediaReader.SAMPLE_RATE, 0.15)
        assertEquals(440.0, pitch(out), 15.0)
    }
}
