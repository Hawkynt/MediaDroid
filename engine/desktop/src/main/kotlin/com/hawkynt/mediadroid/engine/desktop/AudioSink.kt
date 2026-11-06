package com.hawkynt.mediadroid.engine.desktop

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10

/**
 * Where the decoded sound goes, and the clock the pictures follow: [playedMs] is how much of what was written has
 * been heard since the last [flush] (or [open]).
 */
interface AudioSink : AutoCloseable {
    fun open(sampleRate: Int, channels: Int)

    /** Queues 16-bit little-endian interleaved PCM, waiting while the output is full. */
    fun write(pcm: ByteArray)

    /** How many bytes [write] takes now without waiting. */
    fun available(): Int

    fun playedMs(): Long

    fun pause()

    fun resume()

    /** Drops what is queued and starts the clock at 0 again. */
    fun flush()

    var volume: Float
}

/**
 * The JVM's own sound output (Java Sound: PulseAudio/PipeWire/ALSA on Linux, WASAPI/DirectSound on Windows). Its
 * buffer is deep — [bufferMs] — because a video decoder hands its pictures out several frames after their packets:
 * with a shallow one the sound, written as read, fills it and holds the reader up before the pictures due arrive.
 * The clock counts what was played, not what was written, so the depth does not move it. Chosen over miniaudio: see
 * README "Platform support" for the measured clock and why.
 */
class JavaSoundSink(private val bufferMs: Int = 1_000) : AudioSink {

    private var line: SourceDataLine? = null
    private var rate = 48_000
    private var frameBytes = 4
    private var flushedAtUs = 0L

    override fun open(sampleRate: Int, channels: Int) {
        close()
        rate = sampleRate
        frameBytes = 2 * channels
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        line = AudioSystem.getSourceDataLine(format).apply {
            open(format, sampleRate * frameBytes * bufferMs / 1000)
            start()
        }
        flushedAtUs = 0
        applyVolume()
    }

    override fun write(pcm: ByteArray) {
        line?.write(pcm, 0, pcm.size - pcm.size % frameBytes)
    }

    // Without a line, writing drops the sound: there is always room.
    override fun available(): Int = line?.available() ?: Int.MAX_VALUE

    override fun playedMs(): Long = line?.let { (it.microsecondPosition - flushedAtUs) / 1000 } ?: 0

    override fun pause() {
        line?.stop()
    }

    override fun resume() {
        line?.start()
    }

    override fun flush() {
        line?.let {
            it.flush()
            flushedAtUs = it.microsecondPosition
        }
    }

    override var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyVolume()
        }

    private fun applyVolume() {
        val gain = line?.takeIf { it.isControlSupported(FloatControl.Type.MASTER_GAIN) }
            ?.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl ?: return
        val db = if (volume <= 0.0001f) gain.minimum else (20 * log10(volume)).coerceIn(gain.minimum, gain.maximum)
        gain.value = db
    }

    override fun close() {
        line?.let {
            it.stop()
            it.close()
        }
        line = null
    }
}
