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
 *
 * With [normalize] on, the file's own correction comes on top: its ReplayGain from [fileLoudness], else its loudness
 * measured as it plays ([LoudnessNormalization]). A new [FileLoudness] — the next file, another audio track — or a
 * new sound format starts that afresh.
 */
class LoudnessAudioProcessor(
    private val gainDb: () -> Float,
    private val nightMode: () -> Boolean = { false },
    private val normalize: () -> Boolean = { false },
    private val fileLoudness: () -> FileLoudness? = { null },
) : BaseAudioProcessor() {

    private var channels = 1
    private var sampleRate = 48_000
    private var compressor: NightModeCompressor? = null
    private var normalization: LoudnessNormalization? = null
    private var normalizationFor: FileLoudness? = null
    private var samples = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        sampleRate = inputAudioFormat.sampleRate
        compressor = NightModeCompressor(inputAudioFormat.sampleRate)
        normalization = null
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val factor = LoudnessCurve.factorFor(gainDb() + normalizationDb(inputBuffer))
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

    // The file's correction for this buffer, measuring it on the way; the buffer itself is left where it was.
    private fun normalizationDb(inputBuffer: ByteBuffer): Float {
        if (!normalize()) return 0f
        val file = fileLoudness()
        var current = normalization
        if (current == null || file != normalizationFor) {
            current = LoudnessNormalization(sampleRate, channels, file?.tagGainDb)
            normalization = current
            normalizationFor = file
        }
        val count = inputBuffer.remaining() / 2
        if (samples.size < count) samples = ShortArray(count)
        inputBuffer.duplicate().order(inputBuffer.order()).asShortBuffer().get(samples, 0, count)
        return current.feed(samples, 0, count / channels)
    }
}

/**
 * What the loudness correction knows of the file playing: which file and audio track ([key]) and its ReplayGain gain
 * in dB, if its tags carry one ([tagGainDb]).
 */
data class FileLoudness(val key: String, val tagGainDb: Float?)
