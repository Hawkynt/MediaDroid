package com.hawkynt.mediadroid.player

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
    /** 3D video shown as 2D: one eye's half of the frame. */
    val stereo: Stereo = Stereo.OFF,
    /** How much of the [upscaler]'s picture shows, 0 to 1; the rest is plain scaling, see [plainShare]. */
    val upscalerStrength: Float = 1f,
) {
    /** The share of plain scaling mixed over the [upscaler]'s picture: 0 at full strength, 1 with it off. */
    val plainShare: Float get() = 1f - upscalerStrength.coerceIn(0f, 1f)

    /** Whether an [upscaler] other than the screen's own runs: not at zero strength, where only plain scaling shows. */
    val upscales: Boolean get() = upscaler != Upscaler.STANDARD && plainShare < 1f

    /**
     * One eye of a 3D frame as the whole picture: [LEFT] or [RIGHT] half of a side-by-side frame, [TOP] or
     * [BOTTOM] half of a top-and-bottom one, stretched twice in the direction it was halved. A half-width or
     * half-height eye was squeezed to fit the frame, so the stretch gives it back its proper shape and the
     * picture keeps the frame's. [x], [y], [width] and [height] are the half in the frame's texture
     * coordinates: 0 to 1, from the bottom left, as SurfaceTexture's.
     */
    enum class Stereo(val label: String, val x: Float, val y: Float, val width: Float, val height: Float) {
        OFF("Off", 0f, 0f, 1f, 1f),
        LEFT("Left", 0f, 0f, 0.5f, 1f),
        RIGHT("Right", 0.5f, 0f, 0.5f, 1f),
        TOP("Top", 0f, 0.5f, 1f, 0.5f),
        BOTTOM("Bottom", 0f, 0f, 1f, 0.5f),
        ;

        /**
         * The crop as a texture matrix (column-major 4×4, as OpenGL's): the whole picture's coordinates onto
         * the half, to be applied before the frame's own transform.
         */
        fun cropMatrix(): FloatArray = floatArrayOf(
            width, 0f, 0f, 0f,
            0f, height, 0f, 0f,
            0f, 0f, 1f, 0f,
            x, y, 0f, 1f,
        )
    }

    /**
     * Frame blending: between two frames the screen shows a mix of both, weighted by how far the clock
     * is between them — the screen's refresh rate instead of the film's frame rate, so motion steps
     * less. The strength is how much of the interval blends, centred on the change: [LOW] a quarter,
     * [MEDIUM] half, [HIGH] all of it — a blend, where moving edges soften rather than move. [MOTION] is
     * motion-compensated (see [MotionInterpolation]): the pictures between are built along the motion,
     * so moving edges move, over the whole interval.
     */
    enum class Interpolation(val label: String, private val window: Float, val description: String) {
        OFF("Off", 0f, "Every frame shown as it is, for as long as it lasts."),
        LOW("Low", 0.25f, "A short blend where one frame meets the next: steadier pans, nearly as sharp."),
        MEDIUM("Medium", 0.5f, "Half of each frame blended into the next: smoother pans, a little softer."),
        HIGH("High", 1f, "Each refresh shows the two frames mixed by the time between: the smoothest blend."),
        MOTION(
            "Motion",
            1f,
            "The pictures between built along the motion (FFmpeg's minterpolate): edges move, not soften.",
        ),

        /** RIFE's network makes the in-between (see [NeuralInterpolation]); where its self-test passed. */
        RIFE("RIFE", 1f, "A neural network builds the picture between two frames along the motion; tested first."),

        /**
         * DAIN's networks make the in-between, weighing nearer objects by depth (see [NeuralInterpolation]); where
         * its self-test passed, after downloading it (see [NeuralModels]).
         */
        DAIN(
            "DAIN",
            1f,
            "Heavier networks weighing depth, so what passes in front stays in front; fetched and tested.",
        ),

        /**
         * XVFI's network makes the in-between from flows estimated coarse to fine (see [NeuralInterpolation]); where
         * its self-test passed, after downloading it (see [NeuralModels]).
         */
        XVFI(
            "XVFI",
            1f,
            "A network finding motion coarse to fine, for large movements; fetched and tested; not for sale.",
        ),

        /**
         * FLAVR's network makes the in-between from four frames at once with 3D convolutions (see
         * [NeuralInterpolation]); where its self-test passed, after downloading it (see [NeuralModels]).
         */
        FLAVR(
            "FLAVR",
            1f,
            "A network looking at four frames at once, motion learned without flow; the heaviest; fetched and tested.",
        ),

        /** CAIN's network makes the in-between by attention over channels, no flow; fetched (see [NeuralModels]). */
        CAIN("CAIN", 1f, "A network without motion estimation, attention over channels; fetched and tested."),

        /** IFRNet's network makes the in-between in one pass, flows and features together; carried in the app. */
        IFRNET("IFRNet", 1f, "A light network estimating flow and features in one pass; quick; tested first."),
        ;

        /** Built along the motion rather than blended. */
        val isMotionCompensated: Boolean get() = this == MOTION

        /** Made by a neural network (see [NeuralInterpolation]). */
        val isNeural: Boolean get() = this in setOf(RIFE, DAIN, XVFI, FLAVR, CAIN, IFRNET)

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
     * [NIS] NVIDIA Image Scaling (see [Nis]): directional filters by edge, with luma-adaptive sharpening;
     * [GSR] Qualcomm's Snapdragon Game Super Resolution 1: a single pass, bilinear plus an edge-directed
     * correction of the green channel's 12 neighbours, made for mobile GPUs;
     * [ANIME4K] Anime4K's small convolutional network doubling the picture, made for line art and animation.
     * [ACNET] Anime4KCPP's ACNet (F8B4): a VGG-style network of eight channels doubling the picture's luma, the
     * colour following from the picture's bilinear doubling.
     * [XBRZ] Zenju's xBRZ, scaling by rules 2× to 6×: smooth diagonals and curves for pixel art and cartoons.
     * [NEURAL] Real-ESRGAN's compact network (see [NeuralUpscaling]), four times the picture's size — offered only
     * where the household's self-test found the device fast enough.
     */
    enum class Upscaler(val label: String, val description: String) {
        STANDARD("Standard", "The screen's own smooth enlarging: soft, and the lightest on the device."),
        LANCZOS("Lanczos", "A sharper classic filter (Lanczos-3): crisper edges, a little ringing held back."),
        FSR(
            "FSR",
            "AMD FidelityFX Super Resolution: edges enlarged along their direction, then sharpened gently.",
        ),
        EWA("EWA", "A round sharpened filter (libplacebo's EWA Lanczos): diagonals without a staircase."),
        NIS("NIS", "NVIDIA Image Scaling: directional filters by edge, sharpening that holds back ringing."),
        GSR("GSR", "Snapdragon Game Super Resolution: one quick pass, edges sharpened by their direction."),
        ANIME4K("Anime4K", "A small neural network for cartoons and anime: line art kept crisp when doubled."),
        ACNET("ACNet", "Anime4KCPP's small network on the picture's brightness: anime doubled, lines clean."),
        XBRZ("xBRZ", "Rules for pixel art and cartoons: smooth diagonals and curves, flat areas left flat."),
        NEURAL("Real-ESRGAN", "A neural network enlarging four times with fine detail: the heaviest; tested first."),
        WAIFU2X(
            "waifu2x",
            "A neural network for drawings and anime, twice the size with noise lightly removed; tested first.",
        ),
        REALCUGAN("Real-CUGAN", "bilibili's network for anime, twice the size, edges crisp and clean; tested first."),
        REALSR(
            "RealSR",
            "A network for photos and film, four times the size, trained on real blur and noise; fetched.",
        ),
        ;

        /** Enlarged by a network on ncnn (see [NeuralEnlarging]). */
        val isEnlarger: Boolean get() = this == WAIFU2X || this == REALCUGAN || this == REALSR
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
    enum class Deinterlace(val label: String, val description: String) {
        OFF("Off", "The picture as it comes: for progressive video, the lightest."),
        AUTO("Auto", "Rebuilds the second field's lines only where the picture combs; progressive video passes."),
        BLEND("Blend", "Both fields mixed: no combing, a little blur where things move."),
        BOB("Bob", "One field at a time, its lines doubled: sharp motion, finer detail flickers."),
        YADIF("YADIF", "FFmpeg's YADIF: the frames before and after weighed, so still parts keep full detail."),
        BWDIF("BWDIF", "FFmpeg's BWDIF: like YADIF with finer filters, the best detail in motion."),
        FILM("Film", "For films on NTSC video: the film's own 24 frames put back together, shown evenly."),
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

    /**
     * Something is set that the GL stage runs (see the app's VideoFilterStage) — the colour correction too: the
     * picture is drawn into a SurfaceView, which no view effect can colour.
     */
    val hasFilters: Boolean
        get() = sharpness > 0f || clarity > 0f || noise != 0f || deinterlace != Deinterlace.OFF ||
            upscaler != Upscaler.STANDARD || interpolation != Interpolation.OFF || stereo != Stereo.OFF ||
            !isColorNeutral

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

    companion object {
        val Neutral = PictureAdjustment()

        private const val MAX_GRAIN = 0.08f

        /** Where an earlier denoise step (0 off … 3 strong) sits on the noise slider. */
        fun noiseOfLevel(level: Int): Float = -level.coerceIn(0, 3) / 3f

        /** What full brightness adds, in 0..255 units: half the range. */
        const val BRIGHTNESS_SPAN = 128f
    }
}
