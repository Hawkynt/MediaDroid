package com.hawkynt.mediadroid.engine

/**
 * The knobs the screens turn while something plays, read by the engine as it plays — no rebuild, no reload: the
 * loudness gain in dB ([loudnessDb]: Settings plus a volume swipe past the device's maximum), night mode
 * ([nightMode], NightModeCompressor), even loudness from file to file ([normalizeLoudness]: ReplayGain tags, else EBU
 * R128 measured as it plays — LoudnessNormalization), the lip-sync delay for the current output ([audioDelayMs]) and
 * the subtitle delay ([subtitleDelayMs]). The engine reads them on its own threads, as often as it needs, so an
 * implementation answers from live state; [Mutable] holds them as plain values.
 */
interface PlaybackTuning {
    val loudnessDb: Float
    val nightMode: Boolean
    val normalizeLoudness: Boolean
    val audioDelayMs: Int
    val subtitleDelayMs: Int

    /** The knobs as values set directly. */
    class Mutable : PlaybackTuning {
        @Volatile override var loudnessDb: Float = 0f
        @Volatile override var nightMode: Boolean = false
        @Volatile override var normalizeLoudness: Boolean = false
        @Volatile override var audioDelayMs: Int = 0
        @Volatile override var subtitleDelayMs: Int = 0
    }
}
