package com.hawkynt.mediadroyd.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Applies the household loudness setting to the decoded audio: the dB value is
 * re-read per buffer, so the settings slider takes effect mid-playback without
 * rebuilding the chain. The sink feeds the chain PCM16, which is the only
 * encoding accepted here; the sample math itself is [LoudnessChain]'s, shared
 * with the desktop engine and reached there by a plain unit test.
 *
 * With [normalize] on, the file's own correction comes on top: its ReplayGain from [fileLoudness], else its loudness
 * measured as it plays. A new [FileLoudness] — the next file, another audio track — or a new sound format starts that
 * afresh.
 */
class LoudnessAudioProcessor(
    private val gainDb: () -> Float,
    private val nightMode: () -> Boolean = { false },
    private val normalize: () -> Boolean = { false },
    private val fileLoudness: () -> FileLoudness? = { null },
) : BaseAudioProcessor() {

    private var channels = 1
    private var chain: LoudnessChain? = null
    private var samples = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        chain = LoudnessChain(inputAudioFormat.sampleRate, channels)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        // The sink hands over whole frames.
        val frames = inputBuffer.remaining() / 2 / channels
        val count = frames * channels
        if (samples.size < count) samples = ShortArray(count)
        inputBuffer.asShortBuffer().get(samples, 0, count)
        inputBuffer.position(inputBuffer.limit())
        checkNotNull(chain).process(samples, 0, frames, gainDb(), nightMode(), normalize(), fileLoudness())
        val buffer = replaceOutputBuffer(count * 2)
        buffer.asShortBuffer().put(samples, 0, count)
        buffer.position(count * 2)
        buffer.flip()
    }
}
