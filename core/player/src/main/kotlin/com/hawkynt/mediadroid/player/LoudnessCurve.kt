package com.hawkynt.mediadroid.player

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * The loudness fix for unnormalized streams: a dB value becomes a linear gain
 * factor, and samples are scaled with it. Boosting past full scale saturates
 * softly (tanh) instead of hard-clipping or wrapping, so quiet dialogue gets
 * lifted without the loud peaks cracking.
 */
object LoudnessCurve {

    /** Linear amplitude factor for a dB value; 0 dB is unity, +6 dB is double. */
    fun factorFor(db: Float): Float = 10f.pow(db / 20f)

    /** Scales one 16-bit PCM sample by [factor], never leaving the sample range. */
    fun apply(sample: Short, factor: Float): Short = when {
        factor == 1f -> sample
        factor <= 0f -> 0
        factor < 1f -> (sample * factor).roundToInt().coerceIn(-32768, 32767).toShort()
        else -> (32767f * tanh(sample * factor / 32767f)).roundToInt().coerceIn(-32768, 32767).toShort()
    }
}
