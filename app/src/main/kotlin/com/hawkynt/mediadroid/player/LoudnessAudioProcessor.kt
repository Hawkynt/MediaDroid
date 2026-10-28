package com.hawkynt.mediadroid.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import com.hawkynt.mediadroid.player.LoudnessCurve
import java.nio.ByteBuffer

/**
 * Applies the household loudness setting to the decoded audio: the dB value is
 * re-read per buffer, so the settings slider takes effect mid-playback without
 * rebuilding the chain. The sink feeds the chain PCM16, which is the only
 * encoding accepted here; the sample math itself lives in [LoudnessCurve] where
 * a plain unit test can reach it.
 */
class LoudnessAudioProcessor(
    private val gainDb: () -> Float,
    private val nightMode: () -> Boolean = { false },
) : BaseAudioProcessor() {

    private var channels = 1
    private var compressor: NightModeCompressor? = null

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        compressor = NightModeCompressor(inputAudioFormat.sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val factor = LoudnessCurve.factorFor(gainDb())
        val night = if (nightMode()) compressor else null
        val buffer = replaceOutputBuffer(inputBuffer.remaining())
        if (night == null) {
            if (factor == 1f) {
                buffer.put(inputBuffer)
            } else {
                while (inputBuffer.hasRemaining()) {
                    buffer.putShort(LoudnessCurve.apply(inputBuffer.short, factor))
                }
            }
        } else {
            // Night mode: one gain per frame from the loudest channel, so the stereo image stays put.
            val frame = ShortArray(channels)
            while (inputBuffer.remaining() >= channels * 2) {
                var peak = 0
                for (c in 0 until channels) {
                    frame[c] = inputBuffer.short
                    peak = maxOf(peak, kotlin.math.abs(frame[c].toInt()))
                }
                val gain = night.gainFor(peak / 32768f) * factor
                for (c in 0 until channels) buffer.putShort(LoudnessCurve.apply(frame[c], gain))
            }
        }
        buffer.flip()
    }
}
