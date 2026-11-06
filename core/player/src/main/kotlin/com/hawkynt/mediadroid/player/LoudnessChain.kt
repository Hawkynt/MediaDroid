package com.hawkynt.mediadroid.player

import kotlin.math.abs

/**
 * The household's sound settings on the decoded sound, one chain per playback and sound format: the loudness gain in
 * dB ([LoudnessCurve]), with even loudness the file's own correction on top ([LoudnessNormalization]: its ReplayGain
 * from [FileLoudness], else its loudness measured as it plays), and night mode ([NightModeCompressor], one gain per
 * frame from its loudest channel, so the stereo image stays put). The knobs are passed with every buffer, so a slider
 * takes effect mid-playback; a new [FileLoudness] — the next file, another audio track — starts the correction afresh.
 *
 * Android's audio processor and the desktop engine both run their PCM through it.
 */
class LoudnessChain(private val sampleRate: Int, channels: Int) {

    private val channels = channels.coerceAtLeast(1)
    private val compressor = NightModeCompressor(sampleRate)
    private var normalization: LoudnessNormalization? = null
    private var normalizationFor: FileLoudness? = null

    /**
     * Applies the settings in place to [frames] frames of interleaved 16-bit samples from [offset]: the loudness
     * [gainDb], [nightMode], and with [normalize] the correction for [file].
     */
    fun process(
        samples: ShortArray,
        offset: Int,
        frames: Int,
        gainDb: Float,
        nightMode: Boolean,
        normalize: Boolean,
        file: FileLoudness?,
    ) {
        if (frames <= 0) return
        val factor = LoudnessCurve.factorFor(gainDb + normalizationDb(samples, offset, frames, normalize, file))
        val end = offset + frames * channels
        if (!nightMode) {
            if (factor != 1f) for (i in offset until end) samples[i] = LoudnessCurve.apply(samples[i], factor)
            return
        }
        var at = offset
        while (at < end) {
            var peak = 0
            for (c in at until at + channels) peak = maxOf(peak, abs(samples[c].toInt()))
            val gain = compressor.gainFor(peak / 32768f) * factor
            for (c in at until at + channels) samples[c] = LoudnessCurve.apply(samples[c], gain)
            at += channels
        }
    }

    // The file's correction for these frames, measured before any gain.
    private fun normalizationDb(
        samples: ShortArray,
        offset: Int,
        frames: Int,
        normalize: Boolean,
        file: FileLoudness?,
    ): Float {
        if (!normalize) return 0f
        var current = normalization
        if (current == null || file != normalizationFor) {
            current = LoudnessNormalization(sampleRate, channels, file?.tagGainDb)
            normalization = current
            normalizationFor = file
        }
        return current.feed(samples, offset, frames)
    }
}

/**
 * What the loudness correction knows of the file playing: which file and audio track ([key]) and its ReplayGain gain
 * in dB, if its tags carry one ([tagGainDb]).
 */
data class FileLoudness(val key: String, val tagGainDb: Float?)
