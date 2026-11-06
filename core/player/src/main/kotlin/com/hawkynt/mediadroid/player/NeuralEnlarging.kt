package com.hawkynt.mediadroid.player

/**
 * The upscalers made by networks on ncnn (waifu2x's CUnet, Real-CUGAN's SE and pro models, RealSR's DF2K_JPEG model,
 * as nihui's ncnn ports run them; on the desktop Real-ESRGAN too): two, three or four times the picture's size, the
 * screen's scaling doing the rest. Each upscaler offers the [models] its port ships — waifu2x's noise levels,
 * Real-CUGAN's denoise strengths, scales and pro models, Real-ESRGAN's video and general models — and each model may
 * run eight ways ([Choice.tta]). Like the other neural choices, one is taken only where a self-test finds the device
 * runs that very choice right and fast enough.
 */
object NeuralEnlarging {
    /** How much larger [upscaler]'s default model makes a picture: RealSR and Real-ESRGAN four times, else twice. */
    fun scale(upscaler: PictureAdjustment.Upscaler): Int =
        if (upscaler == PictureAdjustment.Upscaler.REALSR || upscaler == PictureAdjustment.Upscaler.NEURAL) 4 else 2

    /** What one 576p picture may take: a frame at 25 frames a second. */
    const val BUDGET_MS = 40.0

    /**
     * How far the output, shrunk back to its input's size, may be from that input, as a mean difference per value
     * (0..255): an upscaler adds detail, it does not change the picture.
     */
    const val MAX_ERROR = 10.0

    /** Whether an output [error] from its input (see [MAX_ERROR]) means the unit runs the network right. */
    fun rightEnough(error: Double): Boolean = error <= MAX_ERROR

    /** Whether enlarging a 576p picture in [ms] keeps up with playback. */
    fun fastEnough(ms: Double): Boolean = ms <= BUDGET_MS

    /**
     * One model of an [upscaler]: its [id] as a picture names it (empty for the upscaler's default), the [label] its
     * chip shows, how much larger it makes a picture ([scale]), the name of its structure and weights in their folder
     * ([file], without `.param` / `.bin`) and where they come from ([files]).
     */
    data class Model(
        val upscaler: PictureAdjustment.Upscaler,
        val id: String,
        val label: String,
        val scale: Int,
        val file: String,
        val files: NeuralModels.Download,
    ) {
        /** Whether it is the upscaler's default: the one carried, or the only one. */
        val isDefault: Boolean get() = id.isEmpty()
    }

    /**
     * What runs: [model], once or eight ways ([tta], test-time augmentation as the ncnn ports do it — the picture
     * mirrored and turned every way, each result turned back and all eight averaged: steadier detail for eight times
     * the work).
     */
    data class Choice(val model: Model, val tta: Boolean = false) {
        val upscaler: PictureAdjustment.Upscaler get() = model.upscaler

        /** How much larger it makes a picture. */
        val scale: Int get() = model.scale

        /**
         * How it is kept with its self-test's result: the upscaler's name alone for its default model run once, as
         * before there were models; else `NAME:id`, with `+tta` where it runs eight ways.
         */
        val key: String
            get() = upscaler.name + (if (model.isDefault) "" else ":${model.id}") + (if (tta) TTA_SUFFIX else "")

        /** As a person reads it in a message. */
        val label: String
            get() = upscaler.label + (if (model.isDefault) "" else " ${model.label}") +
                (if (tta) ", $TTA_WORDS" else "")

        /** [picture] with this choice as its upscaler. */
        fun applied(picture: PictureAdjustment): PictureAdjustment =
            picture.copy(upscaler = upscaler, upscalerModel = model.id, upscalerTta = tta)
    }

    private const val TTA_SUFFIX = "+tta"
    private const val TTA_WORDS = "eight ways"

    /** What the chip for a [Choice.tta] run reads. */
    const val TTA_LABEL = "Eight ways"

    /** What a [Choice.tta] run does, as the household reads it. */
    const val TTA_DESCRIPTION =
        "Eight ways: the picture also mirrored and turned, each result turned back and all averaged — steadier " +
            "detail, eight times the work."

    private val WAIFU2X_MODELS = listOf(-1, 0, 1, 2, 3).map { noise ->
        Model(
            PictureAdjustment.Upscaler.WAIFU2X,
            when (noise) {
                1 -> ""
                -1 -> "nonoise"
                else -> "noise$noise"
            },
            if (noise < 0) "Noise off" else "Noise $noise",
            2,
            NeuralModels.waifu2xFile(noise),
            NeuralModels.waifu2x(noise),
        )
    }

    // Real-CUGAN's models as realcugan-ncnn-vulkan ships them: the SE set at 2x (every denoise strength), 3x and 4x,
    // the pro set at 2x and 3x.
    private val REALCUGAN_MODELS = buildList {
        fun add(set: String, scale: Int, strength: String) {
            val name = "up${scale}x-$strength"
            val words = when (strength) {
                "conservative" -> "conservative"
                "no-denoise" -> "no denoise"
                else -> "denoise ${strength.removePrefix("denoise").removeSuffix("x")}"
            }
            val default = set == "se" && name == "up2x-conservative"
            add(
                Model(
                    PictureAdjustment.Upscaler.REALCUGAN,
                    if (default) "" else "$set-$name",
                    (if (set == "pro") "Pro " else "") + "$scale× $words",
                    scale,
                    name,
                    NeuralModels.realCugan(set, name),
                ),
            )
        }
        val few = listOf("conservative", "no-denoise", "denoise3x")
        for (strength in listOf("conservative", "no-denoise", "denoise1x", "denoise2x", "denoise3x")) {
            add("se", 2, strength)
        }
        for (scale in 3..4) for (strength in few) add("se", scale, strength)
        for (scale in 2..3) for (strength in few) add("pro", scale, strength)
    }

    private val SINGLE_MODELS = listOf(
        Model(PictureAdjustment.Upscaler.REALSR, "", "4×", 4, "x4", NeuralModels.REALSR),
        // Real-ESRGAN on ncnn (the desktop's): its compact video model, carried, and its general x4plus model, fetched.
        Model(
            PictureAdjustment.Upscaler.NEURAL, "", "Anime video", 4, "realesr-animevideov3-x4", NeuralModels.REALESRGAN,
        ),
        Model(
            PictureAdjustment.Upscaler.NEURAL, "x4plus", "General", 4, "realesrgan-x4plus",
            NeuralModels.REALESRGAN_X4PLUS,
        ),
    )

    /** The models [upscaler] runs, its default among them; none for an upscaler that is no network on ncnn. */
    fun models(upscaler: PictureAdjustment.Upscaler): List<Model> = when (upscaler) {
        PictureAdjustment.Upscaler.WAIFU2X -> WAIFU2X_MODELS
        PictureAdjustment.Upscaler.REALCUGAN -> REALCUGAN_MODELS
        else -> SINGLE_MODELS.filter { it.upscaler == upscaler }
    }

    /** [picture]'s upscaler with the model it names (its default where it names none it has); null for no network. */
    fun choice(picture: PictureAdjustment): Choice? {
        val models = models(picture.upscaler)
        val model = models.firstOrNull { it.id == picture.upscalerModel } ?: models.firstOrNull { it.isDefault }
        return model?.let { Choice(it, picture.upscalerTta) }
    }

    /** The choice kept as [key] (see [Choice.key]); null where it names none there is. */
    fun choice(key: String): Choice? {
        val tta = key.endsWith(TTA_SUFFIX)
        val name = key.removeSuffix(TTA_SUFFIX)
        val upscaler = runCatching { PictureAdjustment.Upscaler.valueOf(name.substringBefore(':')) }.getOrNull()
            ?: return null
        val id = name.substringAfter(':', "")
        return models(upscaler).firstOrNull { it.id == id }?.let { Choice(it, tta) }
    }

    /**
     * The tile size [choice] runs a [width] × [height] picture in on a GPU with [budgetMb] of memory for it (0: the
     * CPU, or a budget not known): 0 for the whole picture at once — on the CPU, and on a GPU whose budget holds it as
     * it holds the largest tile — else the size the model's ncnn port takes at that budget. Tiles cost a little at
     * their seams (the SE models average each tile on its own), so a picture is cut only where the memory asks it.
     */
    fun tile(choice: Choice, budgetMb: Int, width: Int, height: Int): Int {
        if (budgetMb <= 0) return 0
        val tiers = tiers(choice.model)
        val (largestBudget, largest) = tiers.first()
        if (budgetMb.toDouble() * largest * largest >= largestBudget.toDouble() * width * height) return 0
        return tiers.firstOrNull { budgetMb > it.first }?.second ?: SMALLEST_TILE
    }

    private const val SMALLEST_TILE = 32

    // (budget in MB above which, tile size), largest first, as each port's main.cpp sizes its tiles by the GPU's heap
    // budget: waifu2x-ncnn-vulkan for CUnet, realcugan-ncnn-vulkan per scale, realsr- and Real-ESRGAN-ncnn-vulkan.
    private fun tiers(model: Model): List<Pair<Int, Int>> = when (model.upscaler) {
        PictureAdjustment.Upscaler.WAIFU2X -> listOf(2600 to 400, 740 to 200, 250 to 100)
        PictureAdjustment.Upscaler.REALCUGAN -> when (model.scale) {
            2 -> listOf(1300 to 400, 800 to 300, 400 to 200, 200 to 100)
            3 -> listOf(3300 to 400, 1900 to 300, 950 to 200, 320 to 100)
            else -> listOf(1690 to 400, 980 to 300, 530 to 200, 240 to 100)
        }
        else -> listOf(1900 to 200, 550 to 100, 190 to 64)
    }
}
