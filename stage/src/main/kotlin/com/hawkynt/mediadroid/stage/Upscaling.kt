package com.hawkynt.mediadroid.stage

import com.hawkynt.mediadroid.imaging.Xbrz
import com.hawkynt.mediadroid.player.Ewa
import com.hawkynt.mediadroid.player.Nis
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.shaders.Shaders
import kotlin.math.ceil

/**
 * The upscalers' passes (PictureAdjustment.Upscaler), each from a picture into a target of the size it enlarges to:
 * which shader, which tables and which uniforms — the same on every platform; which frames they draw into and what
 * follows is the platform stage's. The tables are made on first use.
 */
class Upscaling(private val passes: Passes) {
    private var nisTable = 0
    private var ewaTable = 0
    private var xbrzTable = 0

    /** Lanczos (glsl/video/lanczos.frag), straight to [into]'s size, any ratio. */
    fun lanczos(from: DrawTarget, into: DrawTarget): DrawTarget =
        passes.draw(Shaders.VIDEO_LANCZOS, into, from.texture) {
            inSize(from)
            set("uAntiring", ANTIRING)
        }

    /** Snapdragon GSR (glsl/video/sgsr.frag): one pass straight to [into]'s size, any ratio. */
    fun gsr(from: DrawTarget, into: DrawTarget): DrawTarget =
        passes.draw(Shaders.VIDEO_SGSR, into, from.texture) { inSize(from) }

    /**
     * FSR 1's edge-adaptive upscaling (EASU) to [into]'s size: GLSL ES 3.00's pass, which reads the floats' bits for
     * AMD's approximate reciprocals, where [es3]; else GLSL ES 1.00's, which computes the same bits from their fields.
     */
    fun easu(from: DrawTarget, into: DrawTarget, es3: Boolean = true): DrawTarget =
        passes.draw(if (es3) Shaders.VIDEO_FSR_EASU_ES3 else Shaders.VIDEO_FSR_EASU, into, from.texture) {
            inSize(from)
        }

    /** FSR 1's contrast-adaptive sharpening (RCAS) of [from] into [into], of the same size; [es3] as for [easu]. */
    fun rcas(from: DrawTarget, into: DrawTarget, es3: Boolean = true): DrawTarget =
        passes.draw(if (es3) Shaders.VIDEO_FSR_RCAS_ES3 else Shaders.VIDEO_FSR_RCAS, into, from.texture) {
            set("uTexel", 1f / into.width, 1f / into.height)
            set("uSharp", RCAS_SHARPNESS)
        }

    /**
     * NVIDIA Image Scaling (glsl/video/nis.frag) to [into]'s size — at most twice [from]'s, as NIS allows (see
     * [twice]) — at NVIDIA's neutral sharpness.
     */
    fun nis(from: DrawTarget, into: DrawTarget): DrawTarget {
        if (nisTable == 0) nisTable = passes.table(StageTables.NIS_WIDTH, Nis.PHASES, StageTables.nis())
        val c = Nis.Config(NIS_SHARPNESS)
        return passes.draw(Shaders.VIDEO_NIS, into, from.texture, listOf(nisTable to "uCoef")) {
            inSize(from)
            set("uOutSize", into.width.toFloat(), into.height.toFloat())
            // Divided here, as NVIDIA divides: exactly (see glsl/video/nis.frag).
            set("uScale", from.width.toFloat() / into.width, from.height.toFloat() / into.height)
            set("uDetectRatio", c.detectRatio)
            set("uDetectThres", c.detectThres)
            set("uMinContrastRatio", c.minContrastRatio)
            set("uRatioNorm", c.ratioNorm)
            set("uSharpStartY", c.sharpStartY)
            set("uSharpScaleY", c.sharpScaleY)
            set("uSharpStrengthMin", c.sharpStrengthMin)
            set("uSharpStrengthScale", c.sharpStrengthScale)
            set("uSharpLimitMin", c.sharpLimitMin)
            set("uSharpLimitScale", c.sharpLimitScale)
        }
    }

    /** EWA (glsl/video/ewa.frag) to [into]'s size, at most twice [from]'s (see [twice]): its 8 × 8 taps affordable. */
    fun ewa(from: DrawTarget, into: DrawTarget): DrawTarget {
        if (ewaTable == 0) ewaTable = passes.table(StageTables.EWA_SIZE, 1, StageTables.ewa())
        return passes.draw(Shaders.VIDEO_EWA, into, from.texture, listOf(ewaTable to "uTable")) {
            inSize(from)
            set("uRadius", Ewa.RADIUS.toFloat())
            set("uAntiring", ANTIRING)
        }
    }

    /**
     * Anime4K (glsl/anime4k): four convolutions at [from]'s size into the half-float targets [a] and [b] taking turns,
     * then twice the size into [into] — the picture's bilinear doubling plus the detail the network adds.
     */
    fun anime4k(from: DrawTarget, a: DrawTarget, b: DrawTarget, into: DrawTarget): DrawTarget {
        var input = from
        ANIME4K.forEachIndexed { i, shader ->
            input = passes.draw(shader, if (i % 2 == 0) a else b, input.texture) { texel(from) }
        }
        return passes.draw(Shaders.ANIME4K_DEPTH_TO_SPACE, into, from.texture, listOf(input.texture to "uLast")) {
            set("uLastSize", from.width.toFloat(), from.height.toFloat())
        }
    }

    /**
     * ACNet (glsl/acnet): eleven convolutions at [from]'s size over eight channels — a layer is two half-float
     * targets, written one pass each, the layers taking turns between the pairs [a], [b] and [c], [d] — then twice the
     * size into [into]: the network's luma put into the picture's bilinear doubling.
     */
    fun acnet(
        from: DrawTarget,
        a: DrawTarget,
        b: DrawTarget,
        c: DrawTarget,
        d: DrawTarget,
        into: DrawTarget,
    ): DrawTarget {
        val pairs = arrayOf(arrayOf(a, b), arrayOf(c, d))
        ACNET.forEachIndexed { i, shader ->
            // The head (0) into the first pair, each body block (1 to 4) from one pair into the other, the last
            // convolution (5) into the second; every layer's two halves read the whole layer before.
            val layer = if (i < 2) 0 else (i - 2) / 2 + 1
            val target = pairs[layer % 2]
            val prior = pairs[1 - layer % 2]
            val input = if (layer == 0) from.texture else prior[0].texture
            val extra = buildList {
                if (layer > 0) add(prior[1].texture to "uTex1")
                if (i == ACNET.lastIndex) add(from.texture to "uPicture")
            }
            passes.draw(shader, target[i % 2], input, extra) { texel(from) }
        }
        return passes.draw(Shaders.ACNET_PIXEL_SHUFFLE, into, from.texture, listOf(c.texture to "uLast")) {
            set("uLastSize", from.width.toFloat(), from.height.toFloat())
        }
    }

    /**
     * xBRZ (glsl/xbrz) by the whole [factor] (see [xbrzFactor]) into [into], [factor] times [from]'s size: the corners
     * where pixels meet into [corners] (one larger than [from] each way), what each pixel draws in its corners into
     * [decisions] ([from]'s size, both 8-bit), then the enlarged picture — every texel read as it is.
     */
    fun xbrz(from: DrawTarget, corners: DrawTarget, decisions: DrawTarget, into: DrawTarget, factor: Int): DrawTarget {
        if (xbrzTable == 0) xbrzTable = passes.table(StageTables.XBRZ_WIDTH, StageTables.XBRZ_ROWS, StageTables.xbrz())
        val size: Passes.Uniforms.() -> Unit = { set("uSize", from.width.toFloat(), from.height.toFloat()) }
        passes.draw(Shaders.XBRZ_CORNERS, corners, from.texture, nearest = true, uniforms = size)
        passes.draw(Shaders.XBRZ_DECISIONS, decisions, from.texture, listOf(corners.texture to "uCorners"),
            nearest = true, uniforms = size)
        return passes.draw(Shaders.XBRZ_SCALE, into, from.texture,
            listOf(decisions.texture to "uDecisions", xbrzTable to "uTable"), nearest = true) {
            size()
            set("uFactor", factor.toFloat())
        }
    }

    /** Builds every program [upscaler]'s passes use, now: an exception where one does not build here. */
    fun build(upscaler: PictureAdjustment.Upscaler, es3: Boolean = true) {
        val fragments = when (upscaler) {
            PictureAdjustment.Upscaler.LANCZOS -> listOf(Shaders.VIDEO_LANCZOS)
            PictureAdjustment.Upscaler.GSR -> listOf(Shaders.VIDEO_SGSR)
            PictureAdjustment.Upscaler.FSR -> if (es3) {
                listOf(Shaders.VIDEO_FSR_EASU_ES3, Shaders.VIDEO_FSR_RCAS_ES3)
            } else {
                listOf(Shaders.VIDEO_FSR_EASU, Shaders.VIDEO_FSR_RCAS)
            }
            PictureAdjustment.Upscaler.NIS -> listOf(Shaders.VIDEO_NIS)
            PictureAdjustment.Upscaler.EWA -> listOf(Shaders.VIDEO_EWA)
            PictureAdjustment.Upscaler.ANIME4K -> ANIME4K + Shaders.ANIME4K_DEPTH_TO_SPACE
            PictureAdjustment.Upscaler.ACNET -> ACNET + Shaders.ACNET_PIXEL_SHUFFLE
            PictureAdjustment.Upscaler.XBRZ -> listOf(Shaders.XBRZ_CORNERS, Shaders.XBRZ_DECISIONS, Shaders.XBRZ_SCALE)
            else -> emptyList()
        }
        fragments.forEach { passes.program(it) }
    }

    private fun Passes.Uniforms.inSize(from: DrawTarget) =
        set("uInSize", from.width.toFloat(), from.height.toFloat())

    private fun Passes.Uniforms.texel(from: DrawTarget) = set("uTexel", 1f / from.width, 1f / from.height)

    companion object {
        /**
         * How far Lanczos and EWA pull their result into the range of the four nearest pixels: halfway, so ringing at
         * hard edges is held back without flattening fine detail.
         */
        const val ANTIRING = 0.5f

        /** NIS's sharpness, on its own 0..1 scale: the middle, NVIDIA's neutral setting. */
        const val NIS_SHARPNESS = 0.5f

        /** RCAS strength on AMD's scale of stops below full sharpness (FsrRcasCon): 0.2 stops, exp2(-0.2). */
        const val RCAS_SHARPNESS = 0.8705506f

        private val ANIME4K = listOf(Shaders.ANIME4K_CONV_1, Shaders.ANIME4K_CONV_2, Shaders.ANIME4K_CONV_3,
            Shaders.ANIME4K_CONV_4)
        private val ACNET = listOf(
            Shaders.ACNET_CONV_1, Shaders.ACNET_CONV_2, Shaders.ACNET_CONV_3, Shaders.ACNET_CONV_4,
            Shaders.ACNET_CONV_5, Shaders.ACNET_CONV_6, Shaders.ACNET_CONV_7, Shaders.ACNET_CONV_8,
            Shaders.ACNET_CONV_9, Shaders.ACNET_CONV_10, Shaders.ACNET_CONV_11,
        )

        /** The size NIS and EWA enlarge [size] to on the way to [shown]: at most twice it. */
        fun twice(size: Int, shown: Int): Int = minOf(shown, size * 2)

        /** xBRZ's whole factor, 2 to 6, that reaches [outW] × [outH] from [w] × [h]. */
        fun xbrzFactor(w: Int, h: Int, outW: Int, outH: Int): Int =
            ceil(minOf(outW.toFloat() / w, outH.toFloat() / h)).toInt().coerceIn(Xbrz.FACTORS)
    }
}
