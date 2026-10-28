package com.hawkynt.mediadroid.player

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Night mode: a feed-forward dynamic range compressor that brings explosions down and dialogue up, for
 * late evenings and thin walls. The design is the one in Giannoulis, Massberg & Reiss, "Digital Dynamic
 * Range Compressor Design — A Tutorial and Analysis" (JAES 60(6), 2012): a soft-knee static curve, the
 * gain smoothed in the dB domain with separate attack and release, and make-up gain on top.
 *
 * [gainFor] takes the peak of one audio frame across all channels (0..1) — linking the channels keeps
 * the stereo image — and returns the linear gain for that frame.
 */
class NightModeCompressor(sampleRate: Int) {

    private val attack = exp(-1.0 / (ATTACK_S * sampleRate)).toFloat()
    private val release = exp(-1.0 / (RELEASE_S * sampleRate)).toFloat()
    private var smoothedDb = 0f

    /** The static curve: output level in dB for an input level in dB. */
    fun staticCurveDb(inputDb: Float): Float {
        val over = 2f * (inputDb - THRESHOLD_DB)
        return when {
            over < -KNEE_DB -> inputDb
            over <= KNEE_DB -> {
                val x = inputDb - THRESHOLD_DB + KNEE_DB / 2f
                inputDb + (1f / RATIO - 1f) * x * x / (2f * KNEE_DB)
            }
            else -> THRESHOLD_DB + (inputDb - THRESHOLD_DB) / RATIO
        }
    }

    fun gainFor(peak: Float): Float {
        val inputDb = if (peak > SILENCE) 20f * log10(peak) else SILENCE_DB
        val targetDb = staticCurveDb(inputDb) - inputDb
        // Gain going down (more compression) is the attack; coming back up is the release.
        val coefficient = if (targetDb < smoothedDb) attack else release
        smoothedDb = coefficient * smoothedDb + (1f - coefficient) * targetDb
        return 10f.pow((smoothedDb + MAKEUP_DB) / 20f)
    }

    private companion object {
        const val THRESHOLD_DB = -30f
        const val RATIO = 4f
        const val KNEE_DB = 10f
        const val MAKEUP_DB = 12f
        const val ATTACK_S = 0.005
        const val RELEASE_S = 0.2
        const val SILENCE = 1e-6f
        const val SILENCE_DB = -120f
    }
}
