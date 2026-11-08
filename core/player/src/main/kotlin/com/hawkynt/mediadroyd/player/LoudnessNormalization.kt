package com.hawkynt.mediadroyd.player

/**
 * Even loudness from file to file: a file with ReplayGain plays at its tag's gain ([tagGainDb], see [ReplayGain])
 * from the first sample; a file without is measured as it plays ([LoudnessMeter], EBU R128 integrated loudness) and
 * turned towards ReplayGain 2.0's reference of [TARGET_LUFS]. The measured correction starts once
 * [MEASURE_FIRST_S] seconds are in, follows the measurement as more of the file is heard, moves no faster than
 * [RAMP_DB_PER_S] — so it is never heard as a jump — and stays within [MAX_CUT_DB]..[MAX_BOOST_DB].
 *
 * One per file and sound format; [feed] takes the decoded sound before any gain and returns the gain in dB for it.
 */
class LoudnessNormalization(sampleRate: Int, channels: Int, val tagGainDb: Float?) {

    private val meter = if (tagGainDb == null) LoudnessMeter(sampleRate, channels) else null
    private val rate = sampleRate.toFloat()
    private var targetDb = 0f
    private var currentDb = tagGainDb ?: 0f
    private var nextTargetAt = MEASURE_FIRST_S.toDouble()

    /** Measures [frames] interleaved 16-bit frames of [samples] from [offset]; returns their gain in dB. */
    fun feed(samples: ShortArray, offset: Int, frames: Int): Float {
        val meter = meter ?: return currentDb
        meter.add(samples, offset, frames)
        if (meter.measuredSeconds >= nextTargetAt) {
            nextTargetAt = meter.measuredSeconds + RETARGET_S
            val lufs = meter.integratedLufs
            if (lufs.isFinite()) targetDb = (TARGET_LUFS - lufs).toFloat().coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
        }
        val step = RAMP_DB_PER_S * frames / rate
        currentDb = if (targetDb > currentDb) minOf(targetDb, currentDb + step) else maxOf(targetDb, currentDb - step)
        return currentDb
    }

    companion object {
        /** ReplayGain 2.0's reference loudness, which ReplayGain tags already aim at. */
        const val TARGET_LUFS = -18.0

        /** How much is measured before a correction starts. */
        const val MEASURE_FIRST_S = 3f

        /** How often the correction follows the measurement. */
        const val RETARGET_S = 1.0

        /** How fast the measured correction may move. */
        const val RAMP_DB_PER_S = 2f

        /** A whisper-quiet file is not raised further than this; the soft saturation catches the peaks. */
        const val MAX_BOOST_DB = 12f

        const val MAX_CUT_DB = -24f
    }
}
