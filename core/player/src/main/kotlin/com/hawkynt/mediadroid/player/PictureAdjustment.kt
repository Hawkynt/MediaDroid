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
    /**
     * Noise, -1 to 1: below 0 noise and MPEG blocks are removed — an edge-preserving bilateral filter,
     * stronger and wider towards -1 — above 0 fine film grain is added, up to 8 % of full scale, which
     * hides banding and makes cleaned or enlarged pictures look natural.
     */
    val noise: Float = 0f,
    /** How interlaced pictures lose their combing. */
    val deinterlace: Deinterlace = Deinterlace.OFF,
    /** How a picture smaller than the screen is enlarged. */
    val upscaler: Upscaler = Upscaler.STANDARD,
    /** Smoother motion by blending each frame into the next. */
    val interpolation: Interpolation = Interpolation.OFF,
    /** A temporal [deinterlace] shows each field as a picture of its own, see [isFieldRate]. */
    val fieldRate: Boolean = false,
) {
    /**
     * Frame blending: between two frames the screen shows a mix of both, weighted by how far the clock
     * is between them — the screen's refresh rate instead of the film's frame rate, so motion steps
     * less. The strength is how much of the interval blends, centred on the change: [LOW] a quarter,
     * [MEDIUM] half, [HIGH] all of it — a blend, where moving edges soften rather than move. [MOTION] is
     * motion-compensated (see [MotionInterpolation]): the pictures between are built along the motion,
     * so moving edges move, over the whole interval.
     */
    enum class Interpolation(val label: String, private val window: Float) {
        OFF("Off", 0f),
        LOW("Low", 0.25f),
        MEDIUM("Medium", 0.5f),
        HIGH("High", 1f),
        MOTION("Motion", 1f),
        ;

        /** Built along the motion rather than blended. */
        val isMotionCompensated: Boolean get() = this == MOTION

        /** How much of the next frame to show at [phase] (0 at the earlier frame's time, 1 at the next's). */
        fun weight(phase: Float): Float {
            if (window == 0f) return if (phase < 0.5f) 0f else 1f
            return ((phase - (0.5f - window / 2f)) / window).coerceIn(0f, 1f)
        }
    }

    /**
     * Enlarging a picture smaller than the screen: [STANDARD] is the screen's own bilinear scaling;
     * [LANCZOS] a Lanczos-3 filter (sharper, a little ringing held back); [FSR] AMD FidelityFX Super
     * Resolution 1 — edge-adaptive upscaling (EASU) and robust contrast-adaptive sharpening (RCAS); [EWA]
     * a sharpened Jinc over a circle (see [Ewa]), alike at every angle, so diagonals keep no staircase;
     * [NIS] NVIDIA Image Scaling (see [Nis]): directional filters by edge, with luma-adaptive sharpening.
     */
    enum class Upscaler(val label: String) {
        STANDARD("Standard"),
        LANCZOS("Lanczos"),
        FSR("FSR"),
        EWA("EWA"),
        NIS("NIS"),
    }

    /**
     * Deinterlacing, as the GL stage does it on each frame: [BLEND] averages every line with its
     * neighbours (no combing ever, a little softness); [BOB] keeps one field and rebuilds the other's
     * lines from their neighbours; [AUTO] does that only where a pixel combs — it differs from the lines
     * above and below in the same direction — so progressive pictures pass untouched. [YADIF] and
     * [BWDIF] are temporal (see [Deinterlacer]): they look at the frames before and after too, so still
     * parts keep their full detail, and the picture runs one frame behind, the sound waiting as long.
     * [FILM] undoes 3:2 pulldown (see [Telecine]) — for film on NTSC video, 24 frames a second spread
     * over 30: the fields matched back into whole film frames and the repeated one of every five dropped.
     */
    enum class Deinterlace(val label: String) {
        OFF("Off"),
        AUTO("Auto"),
        BLEND("Blend"),
        BOB("Bob"),
        YADIF("YADIF"),
        BWDIF("BWDIF"),
        FILM("Film"),
        ;

        /** Whether it needs the neighbouring frames, and so can show every field as a picture of its own. */
        val isTemporal: Boolean get() = this == YADIF || this == BWDIF
    }

    /** Both fields of every frame shown as pictures of their own: 50 or 60 a second, as the camera saw them. */
    val isFieldRate: Boolean get() = fieldRate && deinterlace.isTemporal

    val isNeutral: Boolean get() = this == Neutral

    /** The bilateral filter's reach in pixels and how alike two colours must be to blend. */
    data class Denoise(val radius: Float, val similarity: Float)

    /** The denoise filter [noise] asks for, or null when it asks for none. */
    fun denoiseKernel(): Denoise? {
        if (noise >= 0f) return null
        val strength = -noise.coerceAtLeast(-1f)
        return Denoise(if (strength <= 1f / 3f) 1f else 2f, 0.04f + 0.12f * strength)
    }

    /** The film grain [noise] asks for, as an amplitude on the 0..1 scale. */
    val grain: Float get() = noise.coerceIn(0f, 1f) * MAX_GRAIN

    /** No colour correction: brightness, gamma, contrast and saturation all neutral. */
    val isColorNeutral: Boolean
        get() = brightness == 0f && gamma == 1f && contrast == 1f && saturation == 1f

    /** A filter is set that only the GL stage can run (see the app's VideoFilterStage). */
    val hasFilters: Boolean
        get() = sharpness > 0f || clarity > 0f || noise != 0f || deinterlace != Deinterlace.OFF ||
            upscaler != Upscaler.STANDARD || interpolation != Interpolation.OFF

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

        private const val MAX_GRAIN = 0.08f

        /** Where an earlier denoise step (0 off … 3 strong) sits on the noise slider. */
        fun noiseOfLevel(level: Int): Float = -level.coerceIn(0, 3) / 3f

        /** What full brightness adds, in 0..255 units: half the range. */
        const val BRIGHTNESS_SPAN = 128f

        // Rec. 709 luma weights, as rounded in the classic saturation matrix.
        const val R = 0.213f
        const val G = 0.715f
        const val B = 0.072f
    }
}
