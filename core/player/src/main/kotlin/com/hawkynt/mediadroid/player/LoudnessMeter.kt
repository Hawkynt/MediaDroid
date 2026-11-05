package com.hawkynt.mediadroid.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Integrated loudness after ITU-R BS.1770-4 and EBU R128, in LUFS: each channel K-weighted (a high shelf for the head,
 * then a high-pass), the weighted energy taken over 400 ms blocks overlapping by 75 %, blocks under -70 LUFS dropped
 * (the absolute gate), then blocks more than 10 LU under the mean of the rest (the relative gate), and the mean of
 * what remains is the loudness. Converted from libebur128 (Jan Kokemüller, MIT; see
 * app/THIRD-PARTY-NOTICE.libebur128.txt) for its integrated mode: the same filter design for any sample rate, the same
 * channel weights (front 1, surrounds 1.41, the LFE and channels past the sixth not counted), the same gates.
 *
 * Feed it interleaved sound as it plays ([add]); [integratedLufs] is the loudness of everything fed so far.
 */
class LoudnessMeter(val sampleRate: Int, val channels: Int) {

    // The two K-weighting stages combined into one fourth-order filter (libebur128's ebur128_init_filter).
    private val b = DoubleArray(5)
    private val a = DoubleArray(5)
    private val state = Array(channels) { DoubleArray(5) }
    private val weights = DoubleArray(channels) { weightOf(it) }

    private val framesPer100ms = (sampleRate + 5) / 10
    private val quarters = DoubleArray(4)
    private var quarterCount = 0
    private var quarterEnergy = 0.0
    private var quarterFrames = 0
    private var fedFrames = 0L

    private var blocks = DoubleArray(256)
    private var blockCount = 0

    init {
        val shelfK = tan(PI * SHELF_HZ / sampleRate)
        val vh = 10.0.pow(SHELF_GAIN_DB / 20.0)
        val vb = vh.pow(SHELF_VB_EXPONENT)
        val shelfA0 = 1.0 + shelfK / SHELF_Q + shelfK * shelfK
        val pb = doubleArrayOf(
            (vh + vb * shelfK / SHELF_Q + shelfK * shelfK) / shelfA0,
            2.0 * (shelfK * shelfK - vh) / shelfA0,
            (vh - vb * shelfK / SHELF_Q + shelfK * shelfK) / shelfA0,
        )
        val pa = doubleArrayOf(
            1.0,
            2.0 * (shelfK * shelfK - 1.0) / shelfA0,
            (1.0 - shelfK / SHELF_Q + shelfK * shelfK) / shelfA0,
        )
        val passK = tan(PI * HIGH_PASS_HZ / sampleRate)
        val passA0 = 1.0 + passK / HIGH_PASS_Q + passK * passK
        val rb = doubleArrayOf(1.0, -2.0, 1.0)
        val ra = doubleArrayOf(
            1.0,
            2.0 * (passK * passK - 1.0) / passA0,
            (1.0 - passK / HIGH_PASS_Q + passK * passK) / passA0,
        )
        // The cascade is the product of the two polynomials.
        for (i in 0..2) {
            for (j in 0..2) {
                b[i + j] += pb[i] * rb[j]
                a[i + j] += pa[i] * ra[j]
            }
        }
    }

    // libebur128's default channel map: L R C LFE Ls Rs, or L R Ls Rs for four and L R C Ls Rs for five channels.
    private fun weightOf(channel: Int): Double = when (channels) {
        4 -> if (channel < 2) 1.0 else SURROUND_WEIGHT
        5 -> if (channel < 3) 1.0 else SURROUND_WEIGHT
        else -> when (channel) {
            0, 1, 2 -> 1.0
            4, 5 -> SURROUND_WEIGHT
            else -> 0.0
        }
    }

    /** Seconds of sound fed so far. */
    val measuredSeconds: Double get() = fedFrames.toDouble() / sampleRate

    /** Adds [frames] interleaved frames of [samples] from index [offset], each sample -1..1. */
    fun add(samples: FloatArray, offset: Int, frames: Int) {
        for (f in 0 until frames) {
            val base = offset + f * channels
            var energy = 0.0
            for (c in 0 until channels) energy += weights[c] * square(filter(c, samples[base + c].toDouble()))
            addFrame(energy)
        }
        flushDenormals()
    }

    /** Adds [frames] interleaved 16-bit frames of [samples] from index [offset], full scale being 32768. */
    fun add(samples: ShortArray, offset: Int, frames: Int) {
        for (f in 0 until frames) {
            val base = offset + f * channels
            var energy = 0.0
            for (c in 0 until channels) energy += weights[c] * square(filter(c, samples[base + c] / PCM16_SCALE))
            addFrame(energy)
        }
        flushDenormals()
    }

    /** The loudness of everything fed so far in LUFS; negative infinity until a block above the absolute gate. */
    val integratedLufs: Double
        get() {
            if (blockCount == 0) return Double.NEGATIVE_INFINITY
            var sum = 0.0
            for (i in 0 until blockCount) sum += blocks[i]
            val relativeGate = sum / blockCount * RELATIVE_GATE_FACTOR
            var gated = 0.0
            var count = 0
            for (i in 0 until blockCount) {
                if (blocks[i] >= relativeGate) {
                    gated += blocks[i]
                    count++
                }
            }
            return if (count == 0) Double.NEGATIVE_INFINITY else energyToLoudness(gated / count)
        }

    private fun square(x: Double) = x * x

    // Direct form II: the history is the input after the feedback part.
    private fun filter(channel: Int, x: Double): Double {
        if (weights[channel] == 0.0) return 0.0
        val v = state[channel]
        v[0] = x - a[1] * v[1] - a[2] * v[2] - a[3] * v[3] - a[4] * v[4]
        val y = b[0] * v[0] + b[1] * v[1] + b[2] * v[2] + b[3] * v[3] + b[4] * v[4]
        v[4] = v[3]
        v[3] = v[2]
        v[2] = v[1]
        v[1] = v[0]
        return y
    }

    // Once a stretch of silence has decayed the filter into subnormal numbers they cost a hundredfold; zero them.
    private fun flushDenormals() {
        for (v in state) for (i in 1..4) if (abs(v[i]) < java.lang.Double.MIN_NORMAL) v[i] = 0.0
    }

    // The 400 ms blocks are built from 100 ms quarters: one block per new quarter once four are in.
    private fun addFrame(energy: Double) {
        fedFrames++
        quarterEnergy += energy
        if (++quarterFrames < framesPer100ms) return
        quarters[quarterCount % 4] = quarterEnergy
        quarterCount++
        quarterEnergy = 0.0
        quarterFrames = 0
        if (quarterCount < 4) return
        val block = (quarters[0] + quarters[1] + quarters[2] + quarters[3]) / (4.0 * framesPer100ms)
        if (block < ABSOLUTE_GATE_ENERGY) return
        if (blockCount == blocks.size) blocks = blocks.copyOf(blockCount * 2)
        blocks[blockCount++] = block
    }

    private companion object {
        // The K-weighting's shelf and high-pass, as libebur128 fits them to BS.1770's 48 kHz coefficients.
        const val SHELF_HZ = 1681.974450955533
        const val SHELF_GAIN_DB = 3.999843853973347
        const val SHELF_Q = 0.7071752369554196
        const val SHELF_VB_EXPONENT = 0.4996667741545416
        const val HIGH_PASS_HZ = 38.13547087602444
        const val HIGH_PASS_Q = 0.5003270373238773

        const val SURROUND_WEIGHT = 1.41
        const val PCM16_SCALE = 32768.0

        /** BS.1770's offset between the mean square and LUFS. */
        const val LOUDNESS_OFFSET = -0.691
        const val ABSOLUTE_GATE_LUFS = -70.0
        const val RELATIVE_GATE_LU = -10.0

        val ABSOLUTE_GATE_ENERGY = 10.0.pow((ABSOLUTE_GATE_LUFS - LOUDNESS_OFFSET) / 10.0)
        val RELATIVE_GATE_FACTOR = 10.0.pow(RELATIVE_GATE_LU / 10.0)

        fun energyToLoudness(energy: Double) = 10.0 * log10(energy) + LOUDNESS_OFFSET
    }
}
