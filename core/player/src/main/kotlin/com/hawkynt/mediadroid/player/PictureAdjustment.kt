package com.hawkynt.mediadroid.player

import kotlin.math.pow

/**
 * Picture correction for dark or washed-out videos. Neutral at brightness 0 and gamma, contrast
 * and saturation 1. Brightness runs -1..1 (half the signal range either way); gamma, contrast and
 * saturation are multipliers, see [PictureControl] for the ranges the sliders offer. Gamma follows
 * the video-player convention: above 1 lifts the mid-tones, below 1 sinks them.
 */
data class PictureAdjustment(
    val brightness: Float = 0f,
    val gamma: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    /** Edge sharpening, 0 (off) to 1: an unsharp mask of small radius on the luma. */
    val sharpness: Float = 0f,
    /** Local contrast, 0 (off) to 1: the same with a large radius, which lifts texture without halos. */
    val clarity: Float = 0f,
    /** Noise and MPEG block reduction, 0 (off) to 3: an edge-preserving bilateral filter of growing reach. */
    val denoise: Int = 0,
    /** How interlaced pictures lose their combing. */
    val deinterlace: Deinterlace = Deinterlace.OFF,
) {
    /**
     * Deinterlacing, as the GL stage does it on each frame: [BLEND] averages every line with its
     * neighbours (no combing ever, a little softness); [BOB] keeps one field and rebuilds the other's
     * lines from their neighbours; [AUTO] does that only where a pixel combs — it differs from the lines
     * above and below in the same direction — so progressive pictures pass untouched.
     */
    enum class Deinterlace(val label: String) {
        OFF("Off"),
        AUTO("Auto"),
        BLEND("Blend"),
        BOB("Bob"),
    }

    val isNeutral: Boolean get() = this == Neutral

    /** No colour correction: brightness, gamma, contrast and saturation all neutral. */
    val isColorNeutral: Boolean
        get() = brightness == 0f && gamma == 1f && contrast == 1f && saturation == 1f

    /** A filter is set that only the GL stage can run (see the app's VideoFilterStage). */
    val hasFilters: Boolean
        get() = sharpness > 0f || clarity > 0f || denoise > 0 || deinterlace != Deinterlace.OFF

    /** The correction as a GPU shader applies it: saturation, contrast, brightness, then the true gamma curve. */
    data class ShaderUniforms(
        val brightness: Float,
        val contrast: Float,
        val saturation: Float,
        val inverseGamma: Float,
    )

    fun shaderUniforms(): ShaderUniforms = ShaderUniforms(
        brightness = brightness * BRIGHTNESS_SPAN / 255f,
        contrast = contrast,
        saturation = saturation,
        inverseGamma = 1f / gamma,
    )

    /**
     * The same correction as one 4×5 colour matrix (row-major, offsets in 0..255 units), for
     * devices without runtime shaders: saturation around grey with the Rec. 709 luma weights, then
     * contrast around mid grey. A matrix cannot bend a curve, so gamma becomes the offset that puts
     * mid grey where the true curve would; brightness adds to the same offset. The saturation rows
     * sum to 1, so the offset passes through untouched.
     */
    fun colorMatrix(): FloatArray {
        val c = contrast
        val s = saturation
        val gammaLift = 255f * (0.5f.pow(1f / gamma) - 0.5f)
        val o = (c - 1f) * -127.5f + gammaLift + brightness * BRIGHTNESS_SPAN
        return floatArrayOf(
            (R + (1f - R) * s) * c, (G - G * s) * c, (B - B * s) * c, 0f, o,
            (R - R * s) * c, (G + (1f - G) * s) * c, (B - B * s) * c, 0f, o,
            (R - R * s) * c, (G - G * s) * c, (B + (1f - B) * s) * c, 0f, o,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    companion object {
        val Neutral = PictureAdjustment()

        /** What full brightness adds, in 0..255 units: half the range. */
        const val BRIGHTNESS_SPAN = 128f

        // Rec. 709 luma weights, as rounded in the classic saturation matrix.
        const val R = 0.213f
        const val G = 0.715f
        const val B = 0.072f
    }
}
