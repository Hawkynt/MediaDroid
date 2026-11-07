package com.hawkynt.mediadroid.stage

import com.hawkynt.mediadroid.player.Dither
import com.hawkynt.mediadroid.player.MotionInterpolation
import com.hawkynt.mediadroid.player.NeuralInterpolation
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.ScreenWarp
import com.hawkynt.mediadroid.shaders.Shaders

/**
 * The passes that finish a picture: two pictures mixed (blend.frag: smooth motion's blending, an upscaler's strength, a
 * plain copy) or built along the motion between them (motion.frag: Motion, MotionInterpolation; rife-warp-es3.frag:
 * RIFE's flow), the colour correction
 * with the projector's warp and the panorama (adjust.frag), and the dither to 8 bits (dither-es3.frag, dither.frag,
 * libplacebo's blue noise, its table made on first use).
 */
class Finishing(private val passes: Passes) {
    private var noise = 0

    /** [from] into [into] as it is, read through [matrix] (a blend wholly of the one picture). */
    fun copy(from: DrawTarget, into: DrawTarget, matrix: FloatArray = Passes.IDENTITY): DrawTarget =
        passes.draw(Shaders.VIDEO_BLEND, into, from.texture, listOf(from.texture to "uPrevious"), matrix = matrix) {
            set("uWeight", 1f)
        }

    /** [previous] and [current] mixed into [into]: [weight] 0 is [previous], 1 [current]. */
    fun blend(previous: DrawTarget, current: DrawTarget, into: DrawTarget, weight: Float): DrawTarget =
        passes.draw(Shaders.VIDEO_BLEND, into, current.texture, listOf(previous.texture to "uPrevious")) {
            set("uWeight", weight)
        }

    /**
     * The picture [weight] of the way from [previous] to [current], built into [into] along the motion search's
     * vectors ([vectors]: a texture of [gridWidth] × [gridHeight] blocks, searched at 1/[scale] of the size) with the
     * overlapped blocks' weights [obmc] (MotionInterpolation, FFmpeg's minterpolate).
     */
    fun motion(
        previous: DrawTarget,
        current: DrawTarget,
        vectors: Int,
        obmc: Int,
        into: DrawTarget,
        weight: Float,
        gridWidth: Int,
        gridHeight: Int,
        scale: Int,
    ): DrawTarget = passes.draw(
        Shaders.VIDEO_MOTION, into, current.texture,
        listOf(previous.texture to "uPrevious", vectors to "uVectors", obmc to "uObmc"),
    ) {
        set("uAlpha", (weight * MotionInterpolation.ALPHA_MAX).toInt().toFloat())
        set("uSize", into.width.toFloat(), into.height.toFloat())
        set("uGrid", gridWidth.toFloat(), gridHeight.toFloat())
        set("uSearch", (into.width / scale).toFloat(), (into.height / scale).toFloat())
        set("uScale", scale.toFloat())
    }

    /**
     * RIFE's in-between of [earlier] and [later] at their own size into [into] (rife-warp-es3.frag): its [flow] and
     * [mask] (float textures of [flowWidth] × [flowHeight], NeuralInterpolation.handsFlow) grown to the frames' size,
     * each frame warped along its flow and the two blended by the mask. GLSL ES 3.00.
     */
    fun warp(
        earlier: DrawTarget,
        later: DrawTarget,
        flow: Int,
        mask: Int,
        into: DrawTarget,
        flowWidth: Int,
        flowHeight: Int,
    ): DrawTarget = passes.draw(
        Shaders.VIDEO_RIFE_WARP_ES3, into, later.texture,
        listOf(earlier.texture to "uPrevious", flow to "uFlow", mask to "uMask"),
    ) {
        val (sx, sy) = NeuralInterpolation.flowScale(
            NeuralInterpolation.Size(into.width, into.height), NeuralInterpolation.Size(flowWidth, flowHeight),
        )
        set("uSize", into.width.toFloat(), into.height.toFloat())
        set("uScale", sx, sy)
    }

    /** [from] colour-corrected as [picture] says into [into], its geometry bent by [warp] (ScreenWarp). */
    fun adjust(from: DrawTarget, into: DrawTarget, picture: PictureAdjustment, warp: ScreenWarp): DrawTarget {
        val u = picture.shaderUniforms()
        return passes.draw(Shaders.VIDEO_ADJUST, into, from.texture) {
            set("uBrightness", u.brightness)
            set("uContrast", u.contrast)
            set("uSaturation", u.saturation)
            set("uInverseGamma", u.inverseGamma)
            set("uPanorama", warp.panorama)
            matrix3("uWarp", warp.matrix)
            set("uWarped", 1f)
        }
    }

    /**
     * [from] (in half floats) dithered to 8 bits into [into] with libplacebo's blue noise: GLSL ES 3.00's pass, which
     * reads its texels exactly, where [es3]; else GLSL ES 1.00's. [into]'s top row is where v is 1 (libplacebo counts
     * rows from the top).
     */
    fun dither(from: DrawTarget, into: DrawTarget, es3: Boolean = true): DrawTarget {
        if (noise == 0) noise = passes.table(Dither.SIZE, Dither.SIZE, StageTables.noise())
        return passes.draw(
            if (es3) Shaders.VIDEO_DITHER_ES3 else Shaders.VIDEO_DITHER, into, from.texture, listOf(noise to "uNoise"),
        ) {
            set("uSize", into.width.toFloat(), into.height.toFloat())
            set("uRows", into.height.toFloat(), -1f)
        }
    }

    /** Builds the programs: blending, Motion and the colour correction everywhere. */
    fun build() {
        listOf(Shaders.VIDEO_BLEND, Shaders.VIDEO_MOTION, Shaders.VIDEO_ADJUST).forEach { passes.program(it) }
    }
}
