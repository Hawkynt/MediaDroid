package com.hawkynt.mediadroid.player

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * NVIDIA Image Scaling's NVScaler: a 6-tap scaler blended with four directional filters (0°, 45°, 90°,
 * 135°) by how strongly the picture has edges in each direction, with luma-adaptive sharpening and a
 * ringing guard — smooth surfaces, crisp edges, for enlarging up to twice. The reference the app's GL
 * pass is written from, checked against NVIDIA's own NIS_Scaler.h run on the processor.
 *
 * Converted from the NVIDIA Image Scaling SDK v1.0.3 (MIT, © 2022 NVIDIA Corporation, see
 * `THIRD_PARTY_NOTICES.md`): NIS_Scaler.h (SDR, single precision) and NIS_Config.h's settings and
 * coefficient tables. Where NVIDIA's tile loader wraps an unsigned index at the picture's top and left
 * edge, this clamps to the edge as the rest of the picture does.
 */
object Nis {

    /** An RGB picture, channels 0..1, row by row from the top. */
    class Picture(val width: Int, val height: Int, val rgb: FloatArray)

    /** NIS_Config.h `NVScalerUpdateConfig` for SDR, at [sharpness] 0..1. */
    class Config(sharpness: Float) {
        private val slider = max(min(1f, sharpness), 0f) - 0.5f
        private val maxScale = if (slider >= 0f) 1.25f else 1.75f
        private val minScale = if (slider >= 0f) 1.25f else 1.0f
        private val limitScale = if (slider >= 0f) 1.25f else 1.0f
        val detectRatio = 2 * 1127f / 1024f
        val detectThres = 64.0f / 1024.0f
        val minContrastRatio = 2.0f
        private val maxContrastRatio = 10.0f
        val sharpStartY = 0.45f
        private val sharpEndY = 0.9f
        val sharpStrengthMin = max(0.0f, 0.4f + slider * minScale * 1.2f)
        private val sharpStrengthMax = 1.6f + slider * maxScale * 1.8f
        val sharpLimitMin = max(0.1f, 0.14f + slider * limitScale * 0.32f)
        private val sharpLimitMax = 0.5f + slider * limitScale * 0.6f
        val ratioNorm = 1.0f / (maxContrastRatio - minContrastRatio)
        val sharpScaleY = 1.0f / (sharpEndY - sharpStartY)
        val sharpStrengthScale = sharpStrengthMax - sharpStrengthMin
        val sharpLimitScale = sharpLimitMax - sharpLimitMin
        val contrastBoost = 1.0f
        val eps = 1.0f / 255.0f
    }

    /** NVScaler's luma for SDR. */
    fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /** [src] enlarged to [outWidth] × [outHeight] (1 to 2 times its size); returns the RGB channels. */
    fun scale(src: Picture, outWidth: Int, outHeight: Int, sharpness: Float): FloatArray {
        val c = Config(sharpness)
        val scaleX = src.width / outWidth.toFloat()
        val scaleY = src.height / outHeight.toFloat()
        val normX = 1f / src.width
        val normY = 1f / src.height
        val out = FloatArray(outWidth * outHeight * 3)
        val sample = FloatArray(3)
        // Source pixel (column, row)'s luma, read as NVIDIA's tile loader reads it for the output block whose
        // tile starts at (startX, startY): a bilinear tap at its centre, the tile filled in 2 × 2 batches whose
        // second column and row add one texel to the first's coordinate.
        fun coordinate(pixel: Int, start: Int, norm: Float): Float {
            val a = pixel + 2
            return if ((a - start) % 2 == 0) (a + SHIFT) * norm else (a - 1 + SHIFT) * norm + norm
        }
        fun lumaAt(column: Int, row: Int, startX: Int, startY: Int): Float {
            bilinear(src, coordinate(column, startX, normX), coordinate(row, startY, normY), sample)
            return luma(sample[0], sample[1], sample[2])
        }
        val p = Array(6) { FloatArray(6) }
        val q = Array(3) { FloatArray(3) }
        for (dstY in 0 until outHeight) {
            val srcY = (0.5f + dstY) * scaleY - 0.5f
            val fy = srcY - floor(srcY)
            val fyInt = (fy * PHASES).toInt()
            val rowBase = floor(srcY).toInt()
            val startY = floor((BLOCK_HEIGHT * (dstY / BLOCK_HEIGHT) + 0.5f) * scaleY - 0.5f).toInt()
            for (dstX in 0 until outWidth) {
                val srcX = (0.5f + dstX) * scaleX - 0.5f
                val fx = srcX - floor(srcX)
                val fxInt = (fx * PHASES).toInt()
                val columnBase = floor(srcX).toInt()
                val startX = floor((BLOCK_WIDTH * (dstX / BLOCK_WIDTH) + 0.5f) * scaleX - 0.5f).toInt()
                for (i in 0 until 6) {
                    for (j in 0 until 6) p[i][j] = lumaAt(columnBase - 2 + j, rowBase - 2 + i, startX, startY)
                }
                val edge = Array(2) { i ->
                    Array(2) { j ->
                        for (a in 0 until 3) for (b in 0 until 3) q[a][b] = p[1 + i + a][1 + j + b]
                        edgeMap(q, c)
                    }
                }
                val w = interpEdgeMap(edge, fx, fy)
                val baseWeight = 1f - w[0] - w[1] - w[2] - w[3]
                var opY = 0f
                opY += filterNormal(p, fxInt, fyInt) * baseWeight
                opY += addDirFilters(p, fx, fy, fxInt, fyInt, w, c)
                bilinear(src, (srcX + 0.5f) * normX, (srcY + 0.5f) * normY, sample)
                val corr = opY * 1.0f - luma(sample[0], sample[1], sample[2])
                val o = (dstY * outWidth + dstX) * 3
                out[o] = sample[0] + corr
                out[o + 1] = sample[1] + corr
                out[o + 2] = sample[2] + corr
            }
        }
        return out
    }

    /** A clamped bilinear tap at normalised [u], [v], as a GPU's linear sampler takes it. */
    fun bilinear(src: Picture, u: Float, v: Float, into: FloatArray) {
        val fx = u * src.width - 0.5f
        val fy = v * src.height - 0.5f
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val ax = fx - x0
        val ay = fy - y0
        for (k in 0 until 3) {
            fun at(x: Int, y: Int) =
                src.rgb[(min(max(y, 0), src.height - 1) * src.width + min(max(x, 0), src.width - 1)) * 3 + k]
            into[k] = (at(x0, y0) * (1 - ax) + at(x0 + 1, y0) * ax) * (1 - ay) +
                (at(x0, y0 + 1) * (1 - ax) + at(x0 + 1, y0 + 1) * ax) * ay
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun saturate(x: Float) = min(1f, max(0f, x))

    /** NIS GetEdgeMap: the weights of the 0°, 90°, 45° and 135° filters from the 3 × 3 around a pixel. */
    private fun edgeMap(p: Array<FloatArray>, c: Config): FloatArray {
        val g0 = abs(p[0][0] + p[0][1] + p[0][2] - p[2][0] - p[2][1] - p[2][2])
        val g45 = abs(p[1][0] + p[0][0] + p[0][1] - p[2][1] - p[2][2] - p[1][2])
        val g90 = abs(p[0][0] + p[1][0] + p[2][0] - p[0][2] - p[1][2] - p[2][2])
        val g135 = abs(p[1][0] + p[2][0] + p[2][1] - p[0][1] - p[0][2] - p[1][2])
        val g090Max = max(g0, g90)
        val g090Min = min(g0, g90)
        val g45135Max = max(g45, g135)
        val g45135Min = min(g45, g135)
        if (g090Max + g45135Max == 0f) return FloatArray(4)
        val e090 = min(g090Max / (g090Max + g45135Max), 1.0f)
        val e45135 = 1.0f - e090
        val c090 = g090Max > g090Min * c.detectRatio && g090Max > c.detectThres && g090Max > g45135Min
        val c45135 = g45135Max > g45135Min * c.detectRatio && g45135Max > c.detectThres && g45135Max > g090Min
        val cG090 = g090Max == g0
        val cG45135 = g45135Max == g45
        val fE090 = if (c090 && c45135) e090 else 1.0f
        val fE45135 = if (c090 && c45135) e45135 else 1.0f
        return floatArrayOf(
            if (c090 && cG090) fE090 else 0f,
            if (c090 && !cG090) fE090 else 0f,
            if (c45135 && cG45135) fE45135 else 0f,
            if (c45135 && !cG45135) fE45135 else 0f,
        )
    }

    private fun interpEdgeMap(edge: Array<Array<FloatArray>>, fx: Float, fy: Float) = FloatArray(4) { k ->
        val h0 = lerp(edge[0][0][k], edge[0][1][k], fx)
        val h1 = lerp(edge[1][0][k], edge[1][1][k], fx)
        lerp(h0, h1, fy)
    }

    private fun calcLti(p: FloatArray, phase: Int, c: Config): Float {
        val selector = phase <= PHASES / 2
        var sel = if (selector) p[0] else p[3]
        val aMin = min(min(p[1], p[2]), sel)
        val aMax = max(max(p[1], p[2]), sel)
        sel = if (selector) p[2] else p[5]
        val bMin = min(min(p[3], p[4]), sel)
        val bMax = max(max(p[3], p[4]), sel)
        val aCont = aMax - aMin
        val bCont = bMax - bMin
        val contRatio = max(aCont, bCont) / (min(aCont, bCont) + c.eps)
        return (1.0f - saturate((contRatio - c.minContrastRatio) * c.ratioNorm)) * c.contrastBoost
    }

    private fun evalPoly6(pxl: FloatArray, phase: Int, c: Config): Float {
        var y = 0f
        for (i in 0 until 6) y += COEF_SCALE[phase][i] * pxl[i]
        var yUsm = 0f
        for (i in 0 until 6) yUsm += COEF_USM[phase][i] * pxl[i]
        val yScale = 1.0f - saturate((y * 1.0f - c.sharpStartY) * c.sharpScaleY)
        val ySharpness = yScale * c.sharpStrengthScale + c.sharpStrengthMin
        yUsm *= ySharpness
        val yLimit = (yScale * c.sharpLimitScale + c.sharpLimitMin) * y
        yUsm = min(yLimit, max(-yLimit, yUsm))
        yUsm *= calcLti(pxl, phase, c)
        return y + yUsm
    }

    private fun filterNormal(p: Array<FloatArray>, phaseX: Int, phaseY: Int): Float {
        var hAcc = 0f
        for (j in 0 until 6) {
            var vAcc = 0f
            for (i in 0 until 6) vAcc += p[i][j] * COEF_SCALE[phaseY][i]
            hAcc += vAcc * COEF_SCALE[phaseX][j]
        }
        return hAcc
    }

    private fun addDirFilters(
        p: Array<FloatArray>,
        fx: Float,
        fy: Float,
        phaseX: Int,
        phaseY: Int,
        w: FloatArray,
        c: Config,
    ): Float {
        var f = 0f
        if (w[0] > 0f) {
            val interp = FloatArray(6) { i -> lerp(p[i][2], p[i][3], fx) }
            f += evalPoly6(interp, phaseY, c) * w[0]
        }
        if (w[1] > 0f) {
            val interp = FloatArray(6) { i -> lerp(p[2][i], p[3][i], fy) }
            f += evalPoly6(interp, phaseX, c) * w[1]
        }
        if (w[2] > 0f) {
            var b45 = 0.5f + 0.5f * (fx - fy)
            val t = FloatArray(7)
            t[1] = lerp(p[2][1], p[1][2], b45)
            t[3] = lerp(p[3][2], p[2][3], b45)
            t[5] = lerp(p[4][3], p[3][4], b45)
            b45 -= 0.5f
            val up = b45 >= 0f
            t[0] = lerp(p[1][1], if (up) p[0][2] else p[2][0], abs(b45))
            t[2] = lerp(p[2][2], if (up) p[1][3] else p[3][1], abs(b45))
            t[4] = lerp(p[3][3], if (up) p[2][4] else p[4][2], abs(b45))
            t[6] = lerp(p[4][4], if (up) p[3][5] else p[5][3], abs(b45))
            var p45 = fx + fy
            val shift = if (p45 >= 1) 1 else 0
            if (p45 >= 1) p45 -= 1
            f += evalPoly6(FloatArray(6) { t[it + shift] }, (p45 * 64).toInt(), c) * w[2]
        }
        if (w[3] > 0f) {
            var b135 = 0.5f * (fx + fy)
            val t = FloatArray(7)
            t[1] = lerp(p[3][1], p[4][2], b135)
            t[3] = lerp(p[2][2], p[3][3], b135)
            t[5] = lerp(p[1][3], p[2][4], b135)
            b135 -= 0.5f
            val up = b135 >= 0f
            t[0] = lerp(p[4][1], if (up) p[5][2] else p[3][0], abs(b135))
            t[2] = lerp(p[3][2], if (up) p[4][3] else p[2][1], abs(b135))
            t[4] = lerp(p[2][3], if (up) p[3][4] else p[1][2], abs(b135))
            t[6] = lerp(p[1][4], if (up) p[2][5] else p[0][3], abs(b135))
            var p135 = 1 + (fx - fy)
            val shift = if (p135 >= 1) 1 else 0
            if (p135 >= 1) p135 -= 1
            f += evalPoly6(FloatArray(6) { t[it + shift] }, (p135 * 64).toInt(), c) * w[3]
        }
        return f
    }

    /** The output block a tile is loaded for: NIS's default NIS_BLOCK_WIDTH and NIS_BLOCK_HEIGHT. */
    private const val BLOCK_WIDTH = 32
    private const val BLOCK_HEIGHT = 24

    /** The filter phases: NIS's kPhaseCount. */
    const val PHASES = 64

    /** The tile loader's shift to the centre of a texel, less half the support: NIS's kShift. */
    private const val SHIFT = 0.5f - (6 - 1) / 2

    /** The 6-tap scaling filter for [phase], tap [tap]: the table the graphics pass reads. */
    fun scaleCoefficient(phase: Int, tap: Int): Float = COEF_SCALE[phase][tap]

    /** The 6-tap sharpening (unsharp mask) filter for [phase], tap [tap]. */
    fun sharpenCoefficient(phase: Int, tap: Int): Float = COEF_USM[phase][tap]

    /** NIS_Config.h `coef_scale`: 64 phases × 6 taps (the last two of its 8 are always 0). */
    private val COEF_SCALE: Array<FloatArray> = arrayOf(
        floatArrayOf(0.0f, 0.0f, 1.0000f, 0.0f, 0.0f, 0.0f),
        floatArrayOf(0.0029f, -0.0127f, 1.0000f, 0.0132f, -0.0034f, 0.0f),
        floatArrayOf(0.0063f, -0.0249f, 0.9985f, 0.0269f, -0.0068f, 0.0f),
        floatArrayOf(0.0088f, -0.0361f, 0.9956f, 0.0415f, -0.0103f, 0.0005f),
        floatArrayOf(0.0117f, -0.0474f, 0.9932f, 0.0562f, -0.0142f, 0.0005f),
        floatArrayOf(0.0142f, -0.0576f, 0.9897f, 0.0713f, -0.0181f, 0.0005f),
        floatArrayOf(0.0166f, -0.0674f, 0.9844f, 0.0874f, -0.0220f, 0.0010f),
        floatArrayOf(0.0186f, -0.0762f, 0.9785f, 0.1040f, -0.0264f, 0.0015f),
        floatArrayOf(0.0205f, -0.0850f, 0.9727f, 0.1206f, -0.0308f, 0.0020f),
        floatArrayOf(0.0225f, -0.0928f, 0.9648f, 0.1382f, -0.0352f, 0.0024f),
        floatArrayOf(0.0239f, -0.1006f, 0.9575f, 0.1558f, -0.0396f, 0.0029f),
        floatArrayOf(0.0254f, -0.1074f, 0.9487f, 0.1738f, -0.0439f, 0.0034f),
        floatArrayOf(0.0264f, -0.1138f, 0.9390f, 0.1929f, -0.0488f, 0.0044f),
        floatArrayOf(0.0278f, -0.1191f, 0.9282f, 0.2119f, -0.0537f, 0.0049f),
        floatArrayOf(0.0288f, -0.1245f, 0.9170f, 0.2310f, -0.0581f, 0.0059f),
        floatArrayOf(0.0293f, -0.1294f, 0.9058f, 0.2510f, -0.0630f, 0.0063f),
        floatArrayOf(0.0303f, -0.1333f, 0.8926f, 0.2710f, -0.0679f, 0.0073f),
        floatArrayOf(0.0308f, -0.1367f, 0.8789f, 0.2915f, -0.0728f, 0.0083f),
        floatArrayOf(0.0308f, -0.1401f, 0.8657f, 0.3120f, -0.0776f, 0.0093f),
        floatArrayOf(0.0313f, -0.1426f, 0.8506f, 0.3330f, -0.0825f, 0.0103f),
        floatArrayOf(0.0313f, -0.1445f, 0.8354f, 0.3540f, -0.0874f, 0.0112f),
        floatArrayOf(0.0313f, -0.1460f, 0.8193f, 0.3755f, -0.0923f, 0.0122f),
        floatArrayOf(0.0313f, -0.1470f, 0.8022f, 0.3965f, -0.0967f, 0.0137f),
        floatArrayOf(0.0308f, -0.1479f, 0.7856f, 0.4185f, -0.1016f, 0.0146f),
        floatArrayOf(0.0303f, -0.1479f, 0.7681f, 0.4399f, -0.1060f, 0.0156f),
        floatArrayOf(0.0298f, -0.1479f, 0.7505f, 0.4614f, -0.1104f, 0.0166f),
        floatArrayOf(0.0293f, -0.1470f, 0.7314f, 0.4829f, -0.1147f, 0.0181f),
        floatArrayOf(0.0288f, -0.1460f, 0.7119f, 0.5049f, -0.1187f, 0.0190f),
        floatArrayOf(0.0278f, -0.1445f, 0.6929f, 0.5264f, -0.1226f, 0.0200f),
        floatArrayOf(0.0273f, -0.1431f, 0.6724f, 0.5479f, -0.1260f, 0.0215f),
        floatArrayOf(0.0264f, -0.1411f, 0.6528f, 0.5693f, -0.1299f, 0.0225f),
        floatArrayOf(0.0254f, -0.1387f, 0.6323f, 0.5903f, -0.1328f, 0.0234f),
        floatArrayOf(0.0244f, -0.1357f, 0.6113f, 0.6113f, -0.1357f, 0.0244f),
        floatArrayOf(0.0234f, -0.1328f, 0.5903f, 0.6323f, -0.1387f, 0.0254f),
        floatArrayOf(0.0225f, -0.1299f, 0.5693f, 0.6528f, -0.1411f, 0.0264f),
        floatArrayOf(0.0215f, -0.1260f, 0.5479f, 0.6724f, -0.1431f, 0.0273f),
        floatArrayOf(0.0200f, -0.1226f, 0.5264f, 0.6929f, -0.1445f, 0.0278f),
        floatArrayOf(0.0190f, -0.1187f, 0.5049f, 0.7119f, -0.1460f, 0.0288f),
        floatArrayOf(0.0181f, -0.1147f, 0.4829f, 0.7314f, -0.1470f, 0.0293f),
        floatArrayOf(0.0166f, -0.1104f, 0.4614f, 0.7505f, -0.1479f, 0.0298f),
        floatArrayOf(0.0156f, -0.1060f, 0.4399f, 0.7681f, -0.1479f, 0.0303f),
        floatArrayOf(0.0146f, -0.1016f, 0.4185f, 0.7856f, -0.1479f, 0.0308f),
        floatArrayOf(0.0137f, -0.0967f, 0.3965f, 0.8022f, -0.1470f, 0.0313f),
        floatArrayOf(0.0122f, -0.0923f, 0.3755f, 0.8193f, -0.1460f, 0.0313f),
        floatArrayOf(0.0112f, -0.0874f, 0.3540f, 0.8354f, -0.1445f, 0.0313f),
        floatArrayOf(0.0103f, -0.0825f, 0.3330f, 0.8506f, -0.1426f, 0.0313f),
        floatArrayOf(0.0093f, -0.0776f, 0.3120f, 0.8657f, -0.1401f, 0.0308f),
        floatArrayOf(0.0083f, -0.0728f, 0.2915f, 0.8789f, -0.1367f, 0.0308f),
        floatArrayOf(0.0073f, -0.0679f, 0.2710f, 0.8926f, -0.1333f, 0.0303f),
        floatArrayOf(0.0063f, -0.0630f, 0.2510f, 0.9058f, -0.1294f, 0.0293f),
        floatArrayOf(0.0059f, -0.0581f, 0.2310f, 0.9170f, -0.1245f, 0.0288f),
        floatArrayOf(0.0049f, -0.0537f, 0.2119f, 0.9282f, -0.1191f, 0.0278f),
        floatArrayOf(0.0044f, -0.0488f, 0.1929f, 0.9390f, -0.1138f, 0.0264f),
        floatArrayOf(0.0034f, -0.0439f, 0.1738f, 0.9487f, -0.1074f, 0.0254f),
        floatArrayOf(0.0029f, -0.0396f, 0.1558f, 0.9575f, -0.1006f, 0.0239f),
        floatArrayOf(0.0024f, -0.0352f, 0.1382f, 0.9648f, -0.0928f, 0.0225f),
        floatArrayOf(0.0020f, -0.0308f, 0.1206f, 0.9727f, -0.0850f, 0.0205f),
        floatArrayOf(0.0015f, -0.0264f, 0.1040f, 0.9785f, -0.0762f, 0.0186f),
        floatArrayOf(0.0010f, -0.0220f, 0.0874f, 0.9844f, -0.0674f, 0.0166f),
        floatArrayOf(0.0005f, -0.0181f, 0.0713f, 0.9897f, -0.0576f, 0.0142f),
        floatArrayOf(0.0005f, -0.0142f, 0.0562f, 0.9932f, -0.0474f, 0.0117f),
        floatArrayOf(0.0005f, -0.0103f, 0.0415f, 0.9956f, -0.0361f, 0.0088f),
        floatArrayOf(0.0f, -0.0068f, 0.0269f, 0.9985f, -0.0249f, 0.0063f),
        floatArrayOf(0.0f, -0.0034f, 0.0132f, 1.0000f, -0.0127f, 0.0029f),
    )

    /** NIS_Config.h `coef_usm`: 64 phases × 6 taps (the last two of its 8 are always 0). */
    private val COEF_USM: Array<FloatArray> = arrayOf(
        floatArrayOf(0.0f, -0.6001f, 1.2002f, -0.6001f, 0.0f, 0.0f),
        floatArrayOf(0.0029f, -0.6084f, 1.1987f, -0.5903f, -0.0029f, 0.0f),
        floatArrayOf(0.0049f, -0.6147f, 1.1958f, -0.5791f, -0.0068f, 0.0005f),
        floatArrayOf(0.0073f, -0.6196f, 1.1890f, -0.5659f, -0.0103f, 0.0f),
        floatArrayOf(0.0093f, -0.6235f, 1.1802f, -0.5513f, -0.0151f, 0.0f),
        floatArrayOf(0.0112f, -0.6265f, 1.1699f, -0.5352f, -0.0195f, 0.0005f),
        floatArrayOf(0.0122f, -0.6270f, 1.1582f, -0.5181f, -0.0259f, 0.0005f),
        floatArrayOf(0.0142f, -0.6284f, 1.1455f, -0.5005f, -0.0317f, 0.0005f),
        floatArrayOf(0.0156f, -0.6265f, 1.1274f, -0.4790f, -0.0386f, 0.0005f),
        floatArrayOf(0.0166f, -0.6235f, 1.1089f, -0.4570f, -0.0454f, 0.0010f),
        floatArrayOf(0.0176f, -0.6187f, 1.0879f, -0.4346f, -0.0532f, 0.0010f),
        floatArrayOf(0.0181f, -0.6138f, 1.0659f, -0.4102f, -0.0615f, 0.0015f),
        floatArrayOf(0.0190f, -0.6069f, 1.0405f, -0.3843f, -0.0698f, 0.0015f),
        floatArrayOf(0.0195f, -0.6006f, 1.0161f, -0.3574f, -0.0796f, 0.0020f),
        floatArrayOf(0.0200f, -0.5928f, 0.9893f, -0.3286f, -0.0898f, 0.0024f),
        floatArrayOf(0.0200f, -0.5820f, 0.9580f, -0.2988f, -0.1001f, 0.0029f),
        floatArrayOf(0.0200f, -0.5728f, 0.9292f, -0.2690f, -0.1104f, 0.0034f),
        floatArrayOf(0.0200f, -0.5620f, 0.8975f, -0.2368f, -0.1226f, 0.0039f),
        floatArrayOf(0.0205f, -0.5498f, 0.8643f, -0.2046f, -0.1343f, 0.0044f),
        floatArrayOf(0.0200f, -0.5371f, 0.8301f, -0.1709f, -0.1465f, 0.0049f),
        floatArrayOf(0.0195f, -0.5239f, 0.7944f, -0.1367f, -0.1587f, 0.0054f),
        floatArrayOf(0.0195f, -0.5107f, 0.7598f, -0.1021f, -0.1724f, 0.0059f),
        floatArrayOf(0.0190f, -0.4966f, 0.7231f, -0.0649f, -0.1865f, 0.0063f),
        floatArrayOf(0.0186f, -0.4819f, 0.6846f, -0.0288f, -0.1997f, 0.0068f),
        floatArrayOf(0.0186f, -0.4668f, 0.6460f, 0.0093f, -0.2144f, 0.0073f),
        floatArrayOf(0.0176f, -0.4507f, 0.6055f, 0.0479f, -0.2290f, 0.0083f),
        floatArrayOf(0.0171f, -0.4370f, 0.5693f, 0.0859f, -0.2446f, 0.0088f),
        floatArrayOf(0.0161f, -0.4199f, 0.5283f, 0.1255f, -0.2598f, 0.0098f),
        floatArrayOf(0.0161f, -0.4048f, 0.4883f, 0.1655f, -0.2754f, 0.0103f),
        floatArrayOf(0.0151f, -0.3887f, 0.4497f, 0.2041f, -0.2910f, 0.0107f),
        floatArrayOf(0.0142f, -0.3711f, 0.4072f, 0.2446f, -0.3066f, 0.0117f),
        floatArrayOf(0.0137f, -0.3555f, 0.3672f, 0.2852f, -0.3228f, 0.0122f),
        floatArrayOf(0.0132f, -0.3394f, 0.3262f, 0.3262f, -0.3394f, 0.0132f),
        floatArrayOf(0.0122f, -0.3228f, 0.2852f, 0.3672f, -0.3555f, 0.0137f),
        floatArrayOf(0.0117f, -0.3066f, 0.2446f, 0.4072f, -0.3711f, 0.0142f),
        floatArrayOf(0.0107f, -0.2910f, 0.2041f, 0.4497f, -0.3887f, 0.0151f),
        floatArrayOf(0.0103f, -0.2754f, 0.1655f, 0.4883f, -0.4048f, 0.0161f),
        floatArrayOf(0.0098f, -0.2598f, 0.1255f, 0.5283f, -0.4199f, 0.0161f),
        floatArrayOf(0.0088f, -0.2446f, 0.0859f, 0.5693f, -0.4370f, 0.0171f),
        floatArrayOf(0.0083f, -0.2290f, 0.0479f, 0.6055f, -0.4507f, 0.0176f),
        floatArrayOf(0.0073f, -0.2144f, 0.0093f, 0.6460f, -0.4668f, 0.0186f),
        floatArrayOf(0.0068f, -0.1997f, -0.0288f, 0.6846f, -0.4819f, 0.0186f),
        floatArrayOf(0.0063f, -0.1865f, -0.0649f, 0.7231f, -0.4966f, 0.0190f),
        floatArrayOf(0.0059f, -0.1724f, -0.1021f, 0.7598f, -0.5107f, 0.0195f),
        floatArrayOf(0.0054f, -0.1587f, -0.1367f, 0.7944f, -0.5239f, 0.0195f),
        floatArrayOf(0.0049f, -0.1465f, -0.1709f, 0.8301f, -0.5371f, 0.0200f),
        floatArrayOf(0.0044f, -0.1343f, -0.2046f, 0.8643f, -0.5498f, 0.0205f),
        floatArrayOf(0.0039f, -0.1226f, -0.2368f, 0.8975f, -0.5620f, 0.0200f),
        floatArrayOf(0.0034f, -0.1104f, -0.2690f, 0.9292f, -0.5728f, 0.0200f),
        floatArrayOf(0.0029f, -0.1001f, -0.2988f, 0.9580f, -0.5820f, 0.0200f),
        floatArrayOf(0.0024f, -0.0898f, -0.3286f, 0.9893f, -0.5928f, 0.0200f),
        floatArrayOf(0.0020f, -0.0796f, -0.3574f, 1.0161f, -0.6006f, 0.0195f),
        floatArrayOf(0.0015f, -0.0698f, -0.3843f, 1.0405f, -0.6069f, 0.0190f),
        floatArrayOf(0.0015f, -0.0615f, -0.4102f, 1.0659f, -0.6138f, 0.0181f),
        floatArrayOf(0.0010f, -0.0532f, -0.4346f, 1.0879f, -0.6187f, 0.0176f),
        floatArrayOf(0.0010f, -0.0454f, -0.4570f, 1.1089f, -0.6235f, 0.0166f),
        floatArrayOf(0.0005f, -0.0386f, -0.4790f, 1.1274f, -0.6265f, 0.0156f),
        floatArrayOf(0.0005f, -0.0317f, -0.5005f, 1.1455f, -0.6284f, 0.0142f),
        floatArrayOf(0.0005f, -0.0259f, -0.5181f, 1.1582f, -0.6270f, 0.0122f),
        floatArrayOf(0.0005f, -0.0195f, -0.5352f, 1.1699f, -0.6265f, 0.0112f),
        floatArrayOf(0.0f, -0.0151f, -0.5513f, 1.1802f, -0.6235f, 0.0093f),
        floatArrayOf(0.0f, -0.0103f, -0.5659f, 1.1890f, -0.6196f, 0.0073f),
        floatArrayOf(0.0005f, -0.0068f, -0.5791f, 1.1958f, -0.6147f, 0.0049f),
        floatArrayOf(0.0f, -0.0029f, -0.5903f, 1.1987f, -0.6084f, 0.0029f),
    )
}
