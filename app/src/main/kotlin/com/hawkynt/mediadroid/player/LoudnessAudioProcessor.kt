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
class LoudnessAudioProcessor(private val gainDb: () -> Float) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val factor = LoudnessCurve.factorFor(gainDb())
        val buffer = replaceOutputBuffer(inputBuffer.remaining())
        if (factor == 1f) {
            buffer.put(inputBuffer)
        } else {
            while (inputBuffer.hasRemaining()) {
                buffer.putShort(LoudnessCurve.apply(inputBuffer.short, factor))
            }
        }
        buffer.flip()
    }
}
