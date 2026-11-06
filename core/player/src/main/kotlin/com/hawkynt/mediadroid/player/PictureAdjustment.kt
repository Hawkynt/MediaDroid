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
    /**
     * Debanding, 0 (off) to 1: the steps of an 8-bit gradient smoothed away (see [Deband]), libplacebo's defaults in
     * the middle, stronger and with more iterations towards 1.
     */
    val deband: Float = 0f,
    /**
     * Black bars around the picture — letterbox, pillarbox or both — found as it plays ([AutoCrop]) and cropped before
     * every other filter, so Fit, Fill and the upscalers work on the picture alone. Off here, so [Neutral] stays
     * nothing at all; the household default turns it on.
     */
    val cropBlackBars: Boolean = false,
    /**
     * The frame's shape where the file's own flag is wrong ([PictureShape]); a shape only, so the screen's layout
     * gives it and no filter runs for it.
     */
    val aspect: AspectOverride = AspectOverride.AUTO,
    /** How the colour of a 4:2:0 picture is enlarged to its brightness's size, see [ChromaUpscaling]. */
    val chroma: Chroma = Chroma.BILINEAR,
    /**
     * Which of the [upscaler]'s models runs, by its id (see NeuralEnlarging.models): a waifu2x noise level, a
     * Real-CUGAN strength, scale or pro model; empty for the upscaler's default.
     */
    val upscalerModel: String = "",
    /** The [upscaler]'s network run eight ways and averaged (test-time augmentation, see NeuralEnlarging.Choice). */
    val upscalerTta: Boolean = false,
    /** Colour given to black-and-white pictures by a network (see [Colorizing]). */
    val colorize: Colorize = Colorize.OFF,
    /**
     * A station logo hidden in one of the picture's corners (see [LogoMask]), or null; picked once per show and
     * remembered for its folder.
     */
    val logo: LogoMask? = null,
    /**
     * Deblocking, 0 (off) to 1: the 8 × 8 block edges of a low-bitrate MPEG picture smoothed away (see [Deblock]),
     * FFmpeg's weak filter below the middle, its strong one with its own thresholds in the middle, reaching twice as
     * far at 1.
     */
    val deblock: Float = 0f,
    /**
     * Deringing, 0 (off) to 1: the ripples beside edges and text smoothed along the edge (AV1's CDEF, see [Cdef]),
     * stronger towards 1.
     */
    val dering: Float = 0f,
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
     * Enlarging a 4:2:0 picture's colour, a quarter as many samples as pixels, to its brightness's size (see
     * [ChromaUpscaling]) — where the stage gets the picture's planes: from the bundled FFmpeg on Android (a device's
     * decoder hands over RGB its driver made) and every picture the desktop decodes. [BILINEAR] blends the nearest
     * four; [SPLINE36] weighs six by six, sharper; [KRIG] is KrigBilateral: the colour follows the brightness's edges.
     */
    enum class Chroma(val label: String, val description: String) {
        BILINEAR("Bilinear", "The colour's nearest four samples blended: soft colour edges, the lightest."),
        SPLINE36("Spline36", "Six by six samples weighed (libplacebo's spline36): crisper colour edges."),
        KRIG("KrigBilateral", "Colour edges following the brightness's (Shiandow's KrigBilateral): no bleeding."),
    }

    /**
     * Colour for black-and-white pictures: [DDCOLOR] DDColor's network gives the colour at 256 × 256 every few frames,
     * smoothed over time, laid under the frame's own lightness (see [Colorizing]).
     */
    enum class Colorize(val label: String, val description: String) {
        OFF("Off", "The picture's own colours, or none."),
        DDCOLOR(
            "DDColor",
            "A neural network colours black-and-white pictures: only the colour is its, the detail stays the " +
                "picture's; fetched and tested.",
        ),
    }

    /**
     * Deinterlacing, as the GL stage does it on each frame: [BLEND] averages every line with its
     * neighbours (no combing ever, a little softness); [BOB] keeps one field and rebuilds the other's
     * lines from their neighbours; [AUTO] does that only where a pixel combs — it differs from the lines
     * above and below in the same direction — so progressive pictures pass untouched. [YADIF] and
     * [BWDIF] are temporal (see [Deinterlacer]): they look at the frames before and after too, so still
     * parts keep their full detail, and the picture runs one frame behind, the sound waiting as long. So are
     * DScaler's [GREEDY_HM] and [GREEDY_2FRAME], [TOMSMOCOMP] — motion-compensated, a search along the motion between
     * the fields either side — [SMOOTH_MC] (see [TemporalSmoothing]), BWDIF averaged with the fields either side along
     * the motion, after QTGMC — and [ADAPTIVE] (see [MotionAdaptive]), which weaves the frame whole
     * while the picture stands still and is BWDIF while it moves. [OLD_CONSOLE] (see [OldGame]) is for 240p game
     * consoles: each field a picture of its own, stretched smoothly, never deinterlaced.
     * [FILM] undoes pulldown (see [FilmCadence]) — 3:2 for film on NTSC video, 24 frames a second spread
     * over 30: the fields matched back into whole film frames and the repeated one of every five dropped; 2:2 for
     * film on PAL video ([Pulldown22]): woven whole, and anything that is not film deinterlaced.
     */
    enum class Deinterlace(val label: String, val description: String) {
        OFF("Off", "The picture as it comes: for progressive video, the lightest."),
        AUTO("Auto", "Rebuilds the second field's lines only where the picture combs; progressive video passes."),
        BLEND("Blend", "Both fields mixed: no combing, a little blur where things move."),
        BOB("Bob", "One field at a time, its lines doubled: sharp motion, finer detail flickers."),
        YADIF("YADIF", "FFmpeg's YADIF: the frames before and after weighed, so still parts keep full detail."),
        BWDIF("BWDIF", "FFmpeg's BWDIF: like YADIF with finer filters, the best detail in motion."),
        GREEDY_HM("Greedy HM", "DScaler's Greedy High Motion: each line from the nearer field, diagonals smoothed."),
        GREEDY_2FRAME("Greedy 2F", "DScaler's Greedy 2-Frame: woven where nothing moved, rebuilt where it did."),
        TOMSMOCOMP(
            "TomsMoComp",
            "Tom Barry's motion search: each missing pixel from the fields either side, along the motion.",
        ),
        SMOOTH_MC(
            "MC Smooth",
            "After QTGMC: BWDIF, then each picture averaged with its neighbours along the motion; the heaviest.",
        ),
        ADAPTIVE("Adaptive", "Woven whole while the picture stands still, BWDIF as soon as it moves."),
        OLD_CONSOLE(
            "Old console",
            "For 240p games and old computers: each field its own picture, smoothed, not deinterlaced.",
        ),
        FILM("Film", "For films on video: the film's own frames put back together, NTSC's 3:2 and PAL's 2:2."),
        ;

        /** Whether it needs the neighbouring frames, and so can show every field as a picture of its own. */
        val isTemporal: Boolean get() = method != null || this == OLD_CONSOLE

        /** The temporal deinterlacer it runs — while the picture moves, for [ADAPTIVE] — or null. */
        val method: Deinterlacer.Method?
            get() = when (this) {
                YADIF -> Deinterlacer.Method.YADIF
                BWDIF, ADAPTIVE, SMOOTH_MC -> Deinterlacer.Method.BWDIF
                GREEDY_HM -> Deinterlacer.Method.GREEDY_HM
                GREEDY_2FRAME -> Deinterlacer.Method.GREEDY_2FRAME
                TOMSMOCOMP -> Deinterlacer.Method.TOMSMOCOMP
                else -> null
            }
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

    /** The deblocking [deblock] asks for, or null when it is off. */
    fun deblockParams(): Deblock.Params? = Deblock.params(deblock)

    /** The deringing [dering] asks for, or null when it is off. */
    fun deringParams(): Cdef.Params? = Cdef.params(dering)

    /** The debanding [deband] asks for, or null when it is off. */
    fun debandParams(): Deband.Params? = Deband.params(deband)

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
        get() = sharpness > 0f || clarity > 0f || noise != 0f || deband > 0f || cropBlackBars || logo != null ||
            deblock > 0f || dering > 0f ||
            deinterlace != Deinterlace.OFF ||
            upscaler != Upscaler.STANDARD || interpolation != Interpolation.OFF || stereo != Stereo.OFF ||
            colorize != Colorize.OFF ||
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
