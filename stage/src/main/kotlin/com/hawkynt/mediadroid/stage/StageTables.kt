package com.hawkynt.mediadroid.stage

import com.hawkynt.mediadroid.imaging.Xbrz
import com.hawkynt.mediadroid.player.Dither
import com.hawkynt.mediadroid.player.Ewa
import com.hawkynt.mediadroid.player.MotionInterpolation
import com.hawkynt.mediadroid.player.Nis

/**
 * The tables the stage's shaders read as textures, as RGBA bytes rows from the bottom (Passes.table makes them
 * textures), computed from the core's rules; their sizes beside them.
 */
object StageTables {
    /** NIS's table: [NIS_WIDTH] taps by [Nis.PHASES] phases. */
    const val NIS_WIDTH = 6

    /** How NIS's filter taps (-0.62..1.2) map into 0..1 in its table. */
    private const val NIS_OFFSET = 1f
    private const val NIS_SPAN = 2.5f

    /** How many distances the EWA table holds, and how its weights (-0.25..1) map into 0..1. */
    const val EWA_SIZE = 512
    private const val EWA_OFFSET = 0.25f
    private const val EWA_SPAN = 1.25f

    /** Width of xBRZ's pattern table, one texel a cell of the largest block — as glsl/xbrz/scale.frag reads it. */
    const val XBRZ_WIDTH = 36

    /** Rows of xBRZ's pattern table: one per factor from 2 and pattern. */
    val XBRZ_ROWS = Xbrz.FACTORS.count() * Xbrz.Pattern.entries.size

    /** The overlapped blocks' weights' table: [OBMC_SIZE] × [OBMC_SIZE]. */
    const val OBMC_SIZE = 32

    /**
     * NIS's two filter tables (see [Nis]): per phase a row, per tap a texel holding the scaling tap in red and green
     * and the sharpening tap in blue and alpha — one fetch for both — each shifted and scaled into 0..1 and kept to 16
     * bits, the high byte first (glsl/video/nis.frag).
     */
    fun nis(): ByteArray {
        val bytes = ByteArray(NIS_WIDTH * Nis.PHASES * 4)
        var at = 0
        fun put(c: Float) {
            val v = (((c + NIS_OFFSET) / NIS_SPAN) * 65535f + 0.5f).toInt().coerceIn(0, 65535)
            bytes[at++] = (v shr 8).toByte()
            bytes[at++] = (v and 0xFF).toByte()
        }
        for (phase in 0 until Nis.PHASES) {
            for (tap in 0 until NIS_WIDTH) {
                put(Nis.scaleCoefficient(phase, tap))
                put(Nis.sharpenCoefficient(phase, tap))
            }
        }
        return bytes
    }

    /**
     * The EWA weights by distance (see [Ewa]), [EWA_SIZE] × 1: each weight shifted and scaled into 0..1 and kept to
     * 16 bits over the red and green channels (glsl/video/ewa.frag).
     */
    fun ewa(): ByteArray {
        val bytes = ByteArray(EWA_SIZE * 4)
        Ewa.table(EWA_SIZE).forEachIndexed { i, w ->
            val v = (((w + EWA_OFFSET) / EWA_SPAN) * 65535f).toInt().coerceIn(0, 65535)
            bytes[i * 4] = (v shr 8).toByte()
            bytes[i * 4 + 1] = (v and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * xBRZ's patterns (see [Xbrz.weights]), [XBRZ_WIDTH] × [XBRZ_ROWS]: a row per factor from 2 and pattern, a texel
     * per cell of the block, its share of the neighbour's colour as m in red and n in green.
     */
    fun xbrz(): ByteArray {
        val bytes = ByteArray(XBRZ_WIDTH * XBRZ_ROWS * 4)
        var at = 0
        for (factor in Xbrz.FACTORS) for (pattern in Xbrz.Pattern.entries) {
            val weights = Xbrz.weights(factor, pattern)
            for (cell in 0 until XBRZ_WIDTH) {
                val inside = cell < factor * factor
                bytes[at] = (if (inside) weights[cell * 2] else 0).toByte()
                bytes[at + 1] = (if (inside) weights[cell * 2 + 1] else 1).toByte()
                at += 4
            }
        }
        return bytes
    }

    /** The overlapped blocks' weights (MotionInterpolation.obmcWeight), [OBMC_SIZE] × [OBMC_SIZE], in red. */
    fun obmc(): ByteArray = ByteArray(OBMC_SIZE * OBMC_SIZE * 4) {
        if (it % 4 == 0) MotionInterpolation.obmcWeight(it / 4).toByte() else 0
    }

    /** libplacebo's blue-noise matrix for the dither pass, [Dither.SIZE] square, its ranks over red and green. */
    fun noise(): ByteArray = Dither.noiseRgba()
}
