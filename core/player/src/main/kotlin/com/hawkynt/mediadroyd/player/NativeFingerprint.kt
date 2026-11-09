package com.hawkynt.mediadroyd.player

/**
 * Chromaprint's audio fingerprint (MIT, Lukas Lalinsky; app/THIRD-PARTY-NOTICE.Chromaprint.txt) as the native library
 * carries it (native/jni/fingerprint.cpp): one 32-bit item every [ITEM_SECONDS] of sound, the raw fingerprint
 * Chromaprint's fpcalc prints with `-raw`. The library is the app's own, loaded by the platform before anything here is
 * called. Intros and credits are found by matching these (IntroCredits).
 */
object NativeFingerprint {
    /** The rate Chromaprint works at; samples at another rate are resampled to it first. */
    const val SAMPLE_RATE = 11025

    /**
     * The time from one item to the next: Chromaprint's frame of 4096 samples overlapped by all but 4096 / 3 (1365,
     * the integer division its configuration does), at [SAMPLE_RATE].
     */
    const val ITEM_SECONDS = (4096 / 3) / SAMPLE_RATE.toDouble()

    /** Whether the native library with Chromaprint in it is loaded. */
    val available: Boolean
        get() = runCatching { fingerprint(ShortArray(SAMPLE_RATE), SAMPLE_RATE, 1) != null }.getOrDefault(false)

    /**
     * The raw fingerprint of [samples] (16-bit PCM, [channels] interleaved, at [sampleRate]); null where Chromaprint
     * could not make one.
     */
    external fun fingerprint(samples: ShortArray, sampleRate: Int, channels: Int): IntArray?
}
