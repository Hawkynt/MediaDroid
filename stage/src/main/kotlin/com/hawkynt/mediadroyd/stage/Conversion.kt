package com.hawkynt.mediadroyd.stage

import com.hawkynt.mediadroyd.player.PeakDetect
import com.hawkynt.mediadroyd.player.ToneMap
import com.hawkynt.mediadroyd.shaders.Shaders

/**
 * The passes a decoded picture comes in through: HDR's peak measured (glsl/video/peak.frag, PeakDetect) and its tone
 * mapping to SDR (tonemap.frag, ToneMap), and a 4:2:0 picture's chroma enlarged from its planes (ChromaUpscaling:
 * spline36 in one pass, KrigBilateral in three). Where the planes and the coded picture come from, and how the peak's
 * blocks are read back, is the platform stage's.
 */
class Conversion(private val passes: Passes) {

    /** HDR's [coded] picture's blocks measured into [blocks] (PeakDetect.BLOCK pixels a texel) for [transfer]. */
    fun peak(coded: DrawTarget, blocks: DrawTarget, transfer: ToneMap.Transfer): DrawTarget =
        passes.draw(Shaders.VIDEO_PEAK, blocks, coded.texture) {
            tone(PeakDetect.pass(transfer))
            set("uSize", coded.width.toFloat(), coded.height.toFloat())
        }

    /** HDR's [coded] picture tone-mapped into [into] by [pass] (ToneMap.pass, at the peak measured). */
    fun toneMap(coded: DrawTarget, into: DrawTarget, pass: ToneMap.Pass): DrawTarget =
        passes.draw(Shaders.VIDEO_TONEMAP, into, coded.texture) { tone(pass) }

    /**
     * A 4:2:0 picture's planes as the chroma passes read them: the luma, Cb and Cr textures, the luma's [width] ×
     * [height] and the rows' strides in texels ([lumaStride], [chromaStride]), the [color] matrix to RGB with its
     * [foot] (16/255 for limited range, else 0), [chroma] the chroma's share in the spline pass (1: as it is), the
     * [matrix] the luma and chroma are read through, and the chroma's own [chromaWidth] × [chromaHeight] — half the
     * luma's, unless the luma has been enlarged before (an mpv user shader's `LUMA` hooks, MpvHooks.planes).
     */
    class Planes(
        val luma: Int,
        val cb: Int,
        val cr: Int,
        val width: Int,
        val height: Int,
        val lumaStride: Int,
        val chromaStride: Int,
        val color: FloatArray,
        val foot: Float,
        val matrix: FloatArray,
        val chroma: Float = 1f,
        val chromaWidth: Int = (width + 1) / 2,
        val chromaHeight: Int = (height + 1) / 2,
    )

    private fun Passes.Uniforms.colour(p: Planes) {
        matrix3("uColor", p.color)
        set("uFoot", p.foot)
    }

    /** [p] in RGB into [into] at the luma's size, the chroma enlarged by spline36 (glsl/video/yuv-spline.frag). */
    fun spline(p: Planes, into: DrawTarget): DrawTarget = passes.draw(
        Shaders.VIDEO_YUV_SPLINE, into, p.luma, listOf(p.cb to "uU", p.cr to "uV"), matrix = p.matrix,
    ) {
        colour(p)
        set("uChroma", p.chroma)
        set("uChromaSize", p.chromaStride.toFloat(), p.chromaHeight.toFloat())
        set("uChromaWidth", p.chromaWidth.toFloat())
    }

    /**
     * [p] in RGB into [into] at the luma's size, the chroma enlarged by KrigBilateral: the luma brought to the
     * chroma's height into [low], then its size into [lower] (both half floats: the luma's mean and variance), then the
     * chroma predicted from them.
     */
    fun krig(p: Planes, low: DrawTarget, lower: DrawTarget, into: DrawTarget): DrawTarget {
        passes.draw(Shaders.VIDEO_CHROMA_KRIG_1, low, p.luma, matrix = p.matrix) {
            set("uLumaSize", p.lumaStride.toFloat(), p.height.toFloat())
            set("uChromaHeight", p.chromaHeight.toFloat())
        }
        passes.draw(Shaders.VIDEO_CHROMA_KRIG_2, lower, low.texture) {
            set("uLumaWidth", p.width.toFloat())
            set("uChromaWidth", p.chromaWidth.toFloat())
        }
        return passes.draw(
            Shaders.VIDEO_CHROMA_KRIG_3, into, p.luma, listOf(p.cb to "uU", p.cr to "uV", lower.texture to "uLow"),
            matrix = p.matrix,
        ) {
            colour(p)
            set("uLumaStride", p.lumaStride.toFloat())
            set("uLumaSize", p.width.toFloat(), p.height.toFloat())
            set("uChromaSize", p.chromaWidth.toFloat(), p.chromaHeight.toFloat())
        }
    }

    /** Builds the programs: the HDR passes and spline36 everywhere, KrigBilateral's (GLSL ES 3.00) with [krig]. */
    fun build(krig: Boolean) {
        listOf(Shaders.VIDEO_PEAK, Shaders.VIDEO_TONEMAP, Shaders.VIDEO_YUV_SPLINE).forEach { passes.program(it) }
        if (krig) {
            listOf(Shaders.VIDEO_CHROMA_KRIG_1, Shaders.VIDEO_CHROMA_KRIG_2, Shaders.VIDEO_CHROMA_KRIG_3)
                .forEach { passes.program(it) }
        }
    }

    private fun Passes.Uniforms.tone(pass: ToneMap.Pass) = pass.uniforms { name, v ->
        when (v.size) {
            1 -> set(name, v[0])
            2 -> set(name, v[0], v[1])
            3 -> set(name, v[0], v[1], v[2])
            4 -> set(name, v[0], v[1], v[2], v[3])
            else -> matrix3(name, v)
        }
    }
}
