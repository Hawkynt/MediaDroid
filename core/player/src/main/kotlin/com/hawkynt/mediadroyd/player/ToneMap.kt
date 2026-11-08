package com.hawkynt.mediadroyd.player

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * HDR video on a screen without HDR: HDR10 (PQ, SMPTE ST 2084) or HLG (ITU-R BT.2100) in BT.2020 brought down to the
 * BT.709 picture such a screen shows (BT.1886 at a contrast of 1000:1), instead of left to the decoder. The coded values
 * are made linear, taken into IPT — a space where intensity and colour are apart — and there the intensity, in PQ, goes
 * through the BT.2390 curve (ITU-R BT.2390's EETF: a knee from where the screen's peak would be reached, the highlights
 * above it rolled off up to the source's peak, which [PeakDetect] measures from the pictures; black lifted to the
 * screen's black); colour is held back where the intensity drops, so highlights do not turn garish, and whatever lies
 * outside BT.709 afterwards is clipped.
 *
 * Converted from libplacebo 7.360 (LGPL-2.1-or-later, see `THIRD_PARTY_NOTICES.md`): `pl_shader_color_map_ex` and
 * `pl_shader_linearize`/`pl_shader_delinearize` (`src/shaders/colorspace.c`) with the BT.2390 curve
 * (`pl_tone_map_bt2390`, `src/tone_mapping.c`), the hard clip into the target's gamut (`pl_gamut_map_clip`), and the
 * nominal luminances and matrices of `src/colorspace.c` — mpv's `--tone-mapping=bt.2390`. This is the reference the
 * app's GL pass (glsl/video/tonemap.frag) is written from, held to libplacebo's own output by `ToneMapTest`.
 */
object ToneMap {

    /** How an HDR video's values are coded. */
    enum class Transfer {
        /** SMPTE ST 2084, HDR10's perceptual quantizer: absolute luminance up to 10 000 nits. */
        PQ,

        /** ITU-R BT.2100's hybrid log-gamma: relative to the screen, 1000 nits nominal. */
        HLG,
    }

    /** SDR white, the reference all other luminances are measured against: 203 nits (ITU-R BT.2408). */
    const val SDR_WHITE = 203f

    /** The contrast libplacebo assumes for an SDR screen. */
    private const val SDR_CONTRAST = 1000f

    /** The deepest black an HDR source is taken to reach, in nits. */
    private const val HDR_BLACK = 1e-6f

    /** HLG's nominal peak, in nits. */
    private const val HLG_PEAK = 1000f

    // SMPTE ST 2084's constants, in float as libplacebo holds them.
    private val PQ_M1 = (2610.0 / 4096 * 1.0 / 4).toFloat()
    private val PQ_M2 = (2523.0 / 4096 * 128).toFloat()
    private val PQ_C1 = (3424.0 / 4096).toFloat()
    private val PQ_C2 = (2413.0 / 4096 * 32).toFloat()
    private val PQ_C3 = (2392.0 / 4096 * 32).toFloat()

    // ITU-R BT.2100's HLG constants, and the reference white libplacebo scales HLG's gamma from.
    private const val HLG_A = 0.17883277f
    private const val HLG_B = 0.28466892f
    private const val HLG_C = 0.55991073f
    private val HLG_REF = (1000.0 / SDR_WHITE).toFloat()

    /**
     * The constants libplacebo prints into its shaders (`%f`: six decimals), which its GL output is computed with — the
     * per-pixel arithmetic here uses them as they come out of print.
     */
    internal object Printed {
        val M1 = printed(PQ_M1)
        val M2 = printed(PQ_M2)
        val C1 = printed(PQ_C1)
        val C2 = printed(PQ_C2)
        val C3 = printed(PQ_C3)
        val HLG_A = printed(ToneMap.HLG_A)
        val HLG_B = printed(ToneMap.HLG_B)
        val HLG_C = printed(ToneMap.HLG_C)

        /** PQ's 10 000 nits over SDR white, and back. */
        val PQ_SCALE = printed(10000.0 / SDR_WHITE)
        val PQ_SCALE_INVERSE = printed(SDR_WHITE / 10000f)
        val LMS_SCALE = printed(10000 / SDR_WHITE)
    }

    /** [value] as C's `printf("%f")` writes it: rounded to six decimals, a tie to the even digit. */
    internal fun printed(value: Float): Float = printed(value.toDouble())

    /** [value], a double as C passes it to `printf`, as `%f` writes it. */
    internal fun printed(value: Double): Float = BigDecimal(value).setScale(6, RoundingMode.HALF_EVEN).toFloat()

    /** A colour space's primaries and white point as CIE xy pairs. */
    class Primaries(val red: FloatArray, val green: FloatArray, val blue: FloatArray, val white: FloatArray) {
        companion object {
            private val D65 = floatArrayOf(0.3127f, 0.3290f)

            /** ITU-R BT.709 (HD, and sRGB). */
            val BT709 =
                Primaries(floatArrayOf(0.640f, 0.330f), floatArrayOf(0.300f, 0.600f), floatArrayOf(0.150f, 0.060f), D65)

            /** ITU-R BT.2020 (UHD, and every HDR10 and HLG video). */
            val BT2020 =
                Primaries(floatArrayOf(0.708f, 0.292f), floatArrayOf(0.170f, 0.797f), floatArrayOf(0.131f, 0.046f), D65)
        }
    }

    /** A 3 × 3 matrix, row-major as libplacebo's `pl_matrix3x3`. */
    class Matrix(val m: FloatArray) {
        operator fun get(row: Int, column: Int) = m[row * 3 + column]

        /** This times [b], in float as `pl_matrix3x3_mul`. */
        operator fun times(b: Matrix): Matrix = Matrix(
            FloatArray(9) { i ->
                val r = i / 3
                val c = i % 3
                this[r, 0] * b[0, c] + this[r, 1] * b[1, c] + this[r, 2] * b[2, c]
            },
        )

        /** The inverse, worked out in double as `pl_matrix3x3_invert`. */
        fun inverse(): Matrix {
            val d = DoubleArray(9) { m[it].toDouble() }
            val a00 = d[4] * d[8] - d[7] * d[5]
            val a01 = -(d[1] * d[8] - d[7] * d[2])
            val a02 = d[1] * d[5] - d[4] * d[2]
            val a10 = -(d[3] * d[8] - d[6] * d[5])
            val a11 = d[0] * d[8] - d[6] * d[2]
            val a12 = -(d[0] * d[5] - d[3] * d[2])
            val a20 = d[3] * d[7] - d[6] * d[4]
            val a21 = -(d[0] * d[7] - d[6] * d[1])
            val a22 = d[0] * d[4] - d[3] * d[1]
            val det = 1.0 / (d[0] * a00 + d[3] * a01 + d[6] * a02)
            return Matrix(
                doubleArrayOf(a00, a01, a02, a10, a11, a12, a20, a21, a22).map { (det * it).toFloat() }.toFloatArray(),
            )
        }

        /** The matrix for a GLSL `mat3` uniform: column after column. */
        fun columnMajor(): FloatArray = FloatArray(9) { this[it % 3, it / 3] }

        /** [v] multiplied by this matrix, in float. */
        fun apply(v: FloatArray): FloatArray =
            FloatArray(3) { r -> this[r, 0] * v[0] + this[r, 1] * v[1] + this[r, 2] * v[2] }
    }

    /** RGB to CIE XYZ for [p], as libplacebo's `pl_get_rgb2xyz_matrix`. */
    fun rgbToXyz(p: Primaries): Matrix {
        fun x(xy: FloatArray) = xy[0] / xy[1]
        fun z(xy: FloatArray) = (1 - xy[0] - xy[1]) / xy[1]
        val xs = floatArrayOf(x(p.red), x(p.green), x(p.blue))
        val zs = floatArrayOf(z(p.red), z(p.green), z(p.blue))
        val inverse = Matrix(floatArrayOf(xs[0], xs[1], xs[2], 1f, 1f, 1f, zs[0], zs[1], zs[2])).inverse()
        val s = FloatArray(3) { i -> inverse[i, 0] * x(p.white) + inverse[i, 1] * 1 + inverse[i, 2] * z(p.white) }
        return Matrix(
            floatArrayOf(
                s[0] * xs[0], s[1] * xs[1], s[2] * xs[2],
                s[0], s[1], s[2],
                s[0] * zs[0], s[1] * zs[1], s[2] * zs[2],
            ),
        )
    }

    /**
     * RGB in [p] to IPT's LMS cones, as libplacebo's `pl_ipt_rgb2lms`: the Hunt-Pointer-Estévez cones with 4 % crosstalk
     * (Ebner and Fairchild's IPT), the white point D65 — every primaries here are.
     */
    fun rgbToLms(p: Primaries): Matrix {
        val hpe = Matrix(floatArrayOf(0.40024f, 0.70760f, -0.08081f, -0.22630f, 1.16532f, 0.04570f, 0f, 0f, 0.91822f))
        val c = 0.04f
        val crosstalk = Matrix(floatArrayOf(1 - 2 * c, c, c, c, 1 - 2 * c, c, c, c, 1 - 2 * c))
        return crosstalk * hpe * rgbToXyz(p)
    }

    /** IPT's LMS cones to RGB in [p]: the inverse of [rgbToLms], as `pl_ipt_lms2rgb`. */
    fun lmsToRgb(p: Primaries): Matrix = rgbToLms(p).inverse()

    /** PQ-coded LMS to IPT, as standardised by Ebner and Fairchild (1998). */
    val LMS_TO_IPT =
        Matrix(floatArrayOf(0.4000f, 0.4000f, 0.2000f, 4.4550f, -4.8510f, 0.3960f, 0.8056f, 0.3572f, -1.1628f))

    /** IPT back to PQ-coded LMS: libplacebo's numerical inverse of [LMS_TO_IPT]. */
    val IPT_TO_LMS =
        Matrix(floatArrayOf(1.0f, 0.0975689f, 0.205226f, 1.0f, -0.1138760f, 0.133217f, 1.0f, 0.0326151f, -0.676887f))

    /** Nits to PQ, as libplacebo's `pl_hdr_rescale(PL_HDR_NITS, PL_HDR_PQ, …)`. */
    fun pqOfNits(nits: Float): Float {
        if (nits == 0f) return 0f
        var x = max(nits, 0f) / SDR_WHITE
        x *= SDR_WHITE / 10000f
        x = x.pow(PQ_M1)
        x = (PQ_C1 + PQ_C2 * x) / (1f + PQ_C3 * x)
        return x.pow(PQ_M2)
    }

    /**
     * The BT.2390 curve's ends, all in PQ: from the source's black and peak ([inputMin], [inputMax]) to the screen's
     * ([outputMin], [outputMax]).
     */
    data class Curve(val inputMin: Float, val inputMax: Float, val outputMin: Float, val outputMax: Float) {
        // The curve's constants (libplacebo's bt2390), relative to the source's range.
        private val minLum = (outputMin - inputMin) / (inputMax - inputMin)
        private val maxLum = (outputMax - inputMin) / (inputMax - inputMin)

        /** Where the roll-off starts, relative: libplacebo's knee offset of 1 (its default and BT.2390's). */
        val knee = (1 + KNEE_OFFSET) * maxLum - KNEE_OFFSET

        /** The black lift's power. */
        val blackPower = if (minLum > 0) min(1 / minLum, 4f) else 4f

        /** The gain that keeps the peak where it is after the black lift. */
        val gain = if (maxLum < 1) 1 / (1 + minLum / maxLum * (1 - maxLum).pow(blackPower)) else 1f

        /** The relative screen black and peak, for the GL pass. */
        val relative: FloatArray get() = floatArrayOf(minLum, maxLum)

        /** [pq] (an intensity in PQ) through the curve, as libplacebo's `pl_tone_map_sample` with its bt2390. */
        fun map(pq: Float): Float {
            var x = (pq.coerceIn(inputMin, inputMax) - inputMin) / (inputMax - inputMin)
            if (knee < 1) {
                val tb = (x - knee) / (1 - knee)
                val tb2 = tb * tb
                val tb3 = tb2 * tb
                val pb = (2 * tb3 - 3 * tb2 + 1) * knee + (tb3 - 2 * tb2 + tb) * (1 - knee) +
                    (-2 * tb3 + 3 * tb2) * maxLum
                x = if (x < knee) x else pb
            }
            if (x < 1) {
                x += minLum * (1 - x).pow(blackPower)
                x = gain * (x - minLum) + minLum
            }
            x = x * (inputMax - inputMin) + inputMin
            return x.coerceIn(outputMin, outputMax)
        }

        private companion object {
            const val KNEE_OFFSET = 1f
        }
    }

    /**
     * The curve libplacebo sets up for [transfer] to its SDR target: the source's peak [peakPq] as detected (PQ), else
     * [peakNits] from the video's HDR10 metadata, else the nominal one — 10 000 nits for PQ, 1000 for HLG; the source's
     * black PQ's zero, HLG's the screen's (HLG is relative to the screen, so libplacebo gives it the target's contrast);
     * the screen 203 nits at 1000:1. Converted step by step from `pl_shader_color_map_ex`.
     */
    fun curve(transfer: Transfer, peakPq: Float? = null, peakNits: Float = 0f): Curve {
        val hdrMin = pqOfNits(HDR_BLACK)
        val screenBlack = pqOfNits(SDR_WHITE / SDR_CONTRAST)
        // pl_color_space_infer: the static metadata sanitised (in nits), the fallback the transfer's nominal peak.
        val nominalNits = if (transfer == Transfer.HLG) HLG_PEAK else 10000f
        val staticNits = if (peakNits > 0f) peakNits.coerceIn(HDR_BLACK, 10000f) else nominalNits
        var inputMax = peakPq?.coerceIn(hdrMin, 1f) ?: pqOfNits(staticNits)
        val inputMin = if (transfer == Transfer.HLG) screenBlack else hdrMin
        var outputMin = screenBlack
        var outputMax = pqOfNits(SDR_WHITE)
        // pl_tone_map_params_infer: the source's peak no lower than the screen's or 100 nits; BT.2390 maps no higher.
        inputMax = max(inputMax, min(outputMax, pqOfNits(100f)))
        outputMax = min(outputMax, inputMax)
        if (abs(inputMax - outputMax) < 1e-6f) outputMax = inputMax
        if (abs(inputMin - outputMin) < 1e-6f) outputMin = inputMin
        return Curve(inputMin, inputMax, outputMin, min(outputMax, inputMax))
    }

    /**
     * Everything the GL pass (glsl/video/tonemap.frag) needs besides the picture: how [transfer] is made linear, the
     * [curve], the matrices and the BT.1886 coding of the result — each value as libplacebo hands it to its shader.
     * [measuring]: for [PeakDetect], which libplacebo hands HLG with an HDR source's own black rather than the screen's.
     */
    class Pass(val transfer: Transfer, val curve: Curve, measuring: Boolean = false) {
        /** BT.2020's luma weights (the Y row of its RGB to XYZ matrix), printed. */
        val luma = rgbToXyz(Primaries.BT2020).let { m -> FloatArray(3) { printed(m[1, it]) } }

        /** BT.2020 RGB to LMS, BT.709 RGB from LMS. */
        val rgbToLms = rgbToLms(Primaries.BT2020)
        val lmsToRgb = lmsToRgb(Primaries.BT709)

        // HLG's range, relative to SDR white: libplacebo linearises HLG for a 1000-nit screen (gamma 1.2) whose black
        // is the target's, 1000:1 below SDR white — in its measurement, HDR's black.
        private val hlgMax = HLG_PEAK / SDR_WHITE
        private val hlgMin = (if (measuring) HDR_BLACK else SDR_WHITE / SDR_CONTRAST) / SDR_WHITE
        private val hlgGamma = 1.2f * 1.111f.pow(log2(hlgMax / HLG_REF))
        private val hlgBlack = sqrt(3 * (hlgMin / hlgMax).pow(1 / hlgGamma))

        /** HLG's black lift (1 − b, b), its OOTF's peak and gamma less one, all printed. */
        val hlgLift = floatArrayOf(printed(1 - hlgBlack), printed(hlgBlack))
        val hlgOotf = floatArrayOf(printed(hlgMax), printed(hlgGamma - 1))

        // The screen: BT.1886 from its black at 1000:1 up to SDR white, relative to SDR white.
        private val lb = (SDR_WHITE / SDR_CONTRAST / SDR_WHITE).pow(1 / 2.4f)
        private val bt1886A = (1f - lb).pow(2.4f)

        /** BT.1886's 1/a and b, printed. */
        val bt1886 = floatArrayOf(printed(1f / bt1886A), printed(lb / (1f - lb)))

        /**
         * Hands each uniform of glsl/video/tonemap.frag and peak.frag to [set]: its name and value, a `mat3` as nine
         * floats column after column — for the stages to pass on with their own GL bindings.
         */
        fun uniforms(set: (String, FloatArray) -> Unit) {
            set("uHlg", floatArrayOf(if (transfer == Transfer.HLG) 1f else 0f))
            set("uHlgLift", hlgLift)
            set("uHlgOotf", hlgOotf)
            set("uLuma", luma)
            set("uRgbToLms", rgbToLms.columnMajor())
            set("uLmsToIpt", LMS_TO_IPT.columnMajor())
            set("uIptToLms", IPT_TO_LMS.columnMajor())
            set("uLmsToRgb", lmsToRgb.columnMajor())
            set("uCurve", floatArrayOf(curve.inputMin, curve.inputMax) + curve.relative)
            set("uKnee", floatArrayOf(curve.knee, curve.blackPower, curve.gain))
            set("uOutput", floatArrayOf(curve.outputMin, curve.outputMax))
            set("uBt1886", bt1886)
        }
    }

    /** The pass for [transfer] with the source's peak as [PeakDetect] measures it (PQ), or the nominal one. */
    fun pass(transfer: Transfer, peakPq: Float? = null, peakNits: Float = 0f) =
        Pass(transfer, curve(transfer, peakPq, peakNits))

    /** [rgb]'s coded values (0..1) made linear, relative to SDR white, as `pl_shader_linearize` does in its shader. */
    internal fun linearize(rgb: FloatArray, pass: Pass) {
        for (c in 0..2) rgb[c] = max(rgb[c], 0f)
        if (pass.transfer == Transfer.PQ) {
            for (c in 0..2) {
                var x = rgb[c].pow(1f / Printed.M2)
                x = max(x - Printed.C1, 0f) / (Printed.C2 - Printed.C3 * x)
                rgb[c] = x.pow(1f / Printed.M1) * Printed.PQ_SCALE
            }
            return
        }
        for (c in 0..2) {
            val x = pass.hlgLift[0] * rgb[c] + pass.hlgLift[1]
            rgb[c] = if (0.5f < x) exp((x - Printed.HLG_C) * (1f / Printed.HLG_A)) + Printed.HLG_B else 4f * x * x
        }
        for (c in 0..2) rgb[c] *= 1f / 12f
        val y = max(pass.luma[0] * rgb[0] + pass.luma[1] * rgb[1] + pass.luma[2] * rgb[2], 0f)
        val scale = pass.hlgOotf[0] * y.pow(pass.hlgOotf[1])
        for (c in 0..2) rgb[c] *= scale
    }

    /**
     * One pixel's coded HDR values [rgb] (0..1) to the BT.709 picture's coded values (0..1, not yet clipped), as
     * libplacebo's colour mapping computes it.
     */
    fun map(rgb: FloatArray, pass: Pass): FloatArray {
        val color = rgb.copyOf()
        linearize(color, pass)
        // Into IPT, the intensity in PQ.
        val lms = pass.rgbToLms.apply(color)
        val lmsPq = FloatArray(3) {
            var x = max(Printed.PQ_SCALE_INVERSE * lms[it], 0f).pow(Printed.M1)
            x = (Printed.C1 + Printed.C2 * x) / (1f + Printed.C3 * x)
            x.pow(Printed.M2)
        }
        val ipt = LMS_TO_IPT.apply(lmsPq)
        val original = ipt[0]
        ipt[0] = pass.curve.map(ipt[0])
        // Colour held back where the intensity drops, the more the further down (libplacebo's hull).
        fun hull(i: Float) = ((i - 6f) * i + 9f) * i
        val chroma = min(original / ipt[0], hull(ipt[0]) / hull(original))
        ipt[1] *= chroma
        ipt[2] *= chroma
        // Back to linear light, in BT.709.
        val back = IPT_TO_LMS.apply(ipt)
        val linear = FloatArray(3) {
            var x = max(back[it], 0f).pow(1f / Printed.M2)
            x = max(x - Printed.C1, 0f) / (Printed.C2 - Printed.C3 * x)
            x.pow(1f / Printed.M1) * Printed.LMS_SCALE
        }
        val out = pass.lmsToRgb.apply(linear)
        // BT.1886, the screen's black at code 0.
        for (c in 0..2) out[c] = (pass.bt1886[0] * max(out[c], 0f)).pow(1f / 2.4f) - pass.bt1886[1]
        return out
    }

    /**
     * A picture of coded HDR values — [rgb16] 16-bit RGB, [width] × [height] — tone-mapped to 8-bit BT.709 RGB, each
     * value clipped and rounded as a GL pass writes it into an 8-bit frame.
     */
    fun apply(rgb16: ShortArray, width: Int, height: Int, pass: Pass): ByteArray {
        require(rgb16.size == width * height * 3) { "not $width × $height RGB" }
        val out = ByteArray(rgb16.size)
        val pixel = FloatArray(3)
        for (i in 0 until width * height) {
            for (c in 0..2) pixel[c] = (rgb16[i * 3 + c].toInt() and 0xFFFF) / 65535f
            val mapped = map(pixel, pass)
            for (c in 0..2) out[i * 3 + c] = (mapped[c].coerceIn(0f, 1f) * 255f).roundToInt().toByte()
        }
        return out
    }
}
