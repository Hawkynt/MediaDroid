package com.hawkynt.mediadroid.player

import java.nio.ByteBuffer

/**
 * The native part's neural networks (native/jni/interpolator.cpp, on ncnn), the same surface on every platform: the
 * Android app's `libmediadroid_neural.so`, the desktop's `libmediadroid.so` / `mediadroid.dll`. Which library holds it
 * is the platform's to [load]; the networks read their models from the folders named — on Android an asset folder
 * where an AssetManager is handed over as `assets`, everywhere else files.
 */
object NativeInterpolation {
    @Volatile private var loadedAt: Boolean? = null

    /** Whether [load] loaded the library and handed it the compute shaders. */
    val loaded: Boolean get() = loadedAt == true

    /**
     * Loads the library with [library] (System.loadLibrary or System.load) once, and hands it [shaders] (ncnn's
     * compute shaders by name, see the stage's NcnnShaders); whether it is loaded. Later calls answer as the first.
     */
    @Synchronized
    fun load(library: () -> Unit, shaders: Map<String, String>): Boolean {
        loadedAt?.let { return it }
        val ok = runCatching(library).isSuccess && runCatching {
            shaders(shaders.keys.toTypedArray(), shaders.values.toTypedArray())
        }.isSuccess
        loadedAt = ok
        return ok
    }

    /** Whether ncnn finds a Vulkan GPU to run on. */
    external fun hasGpu(): Boolean

    /** Hands over the compute shaders by name. */
    external fun shaders(names: Array<String>, sources: Array<String>)

    /** RIFE from the folder [dir] (in [assets] where given), on the GPU or the CPU; 0 where it does not load. */
    external fun openRife(assets: Any?, dir: String, gpu: Boolean): Long

    /** DAIN from the folder [dir] of downloaded files, on the GPU or the CPU; 0 where it does not load. */
    external fun openDain(dir: String, gpu: Boolean): Long

    /** XVFI from the folder [dir] of downloaded files, on the GPU or the CPU; 0 where it does not load. */
    external fun openXvfi(dir: String, gpu: Boolean): Long

    /** FLAVR from the folder [dir] of downloaded files, on the GPU or the CPU; 0 where it does not load. */
    external fun openFlavr(dir: String, gpu: Boolean): Long

    /**
     * The in-between at [time] of the RGBA frames [earlier] and [later] into [out] as RGB; 0 when it ran. The ids
     * ([earlierId], [laterId]; 0 where not known) let a frame met again keep what was worked out from it alone. A
     * network that looks at four frames (FLAVR) takes the frames [before] and [after] the pair where given.
     */
    external fun between(
        handle: Long,
        earlier: ByteBuffer,
        later: ByteBuffer,
        width: Int,
        height: Int,
        time: Float,
        out: ByteBuffer,
        earlierId: Long,
        laterId: Long,
        before: ByteBuffer?,
        after: ByteBuffer?,
    ): Int

    /** Frees the network. */
    external fun close(handle: Long)

    /**
     * An upscaler — [kind] 0 waifu2x, 1 Real-CUGAN, 2 RealSR, 3 Real-ESRGAN — its [model] (the structure and weights'
     * name) from the folder [dir], in [assets] where given, else of files; [scale] times the size, once or eight ways
     * ([tta]), on the GPU or the CPU; 0 where not loaded.
     */
    external fun openEnlarger(
        assets: Any?,
        dir: String,
        kind: Int,
        model: String,
        scale: Int,
        tta: Boolean,
        gpu: Boolean,
    ): Long

    /** What ncnn lets the GPU's networks take, in MB (its heap budget); 0 where there is no GPU. */
    external fun gpuHeapBudget(): Int

    /** CAIN from the folder [dir] of downloaded files, on the GPU or the CPU; 0 where it does not load. */
    external fun openCain(dir: String, gpu: Boolean): Long

    /** IFRNet from the folder [dir] (in [assets] where given), on the GPU or the CPU; 0 where it does not load. */
    external fun openIfrnet(assets: Any?, dir: String, gpu: Boolean): Long

    /**
     * The RGBA [picture] ([width] × [height]) at the upscaler's scale into [out] as RGB, whole ([tile] 0) or in tiles
     * of that size (see NeuralEnlarging.tile); 0 when it ran.
     */
    external fun enlarge(handle: Long, picture: ByteBuffer, width: Int, height: Int, out: ByteBuffer, tile: Int): Int

    /** Frees the upscaler. */
    external fun closeEnlarger(handle: Long)

    /** DDColor from the folder [dir] (in [assets] where given), on the GPU or the CPU; 0 where it does not load. */
    external fun openColorizer(assets: Any?, dir: String, gpu: Boolean): Long

    /**
     * The colour of [grey] (Colorizing.input: three planes of 256 × 256 floats, native order) into [ab] (two planes of
     * 256 × 256 floats, the a and b of Lab); 0 when it ran.
     */
    external fun colorize(handle: Long, grey: java.nio.FloatBuffer, ab: java.nio.FloatBuffer): Int

    /** Frees the colouriser. */
    external fun closeColorizer(handle: Long)

    /** [choice] opened: its model from [dir] (in [assets] where given), on the GPU or the CPU; 0 where not loaded. */
    fun openEnlarger(assets: Any?, dir: String, choice: NeuralEnlarging.Choice, gpu: Boolean): Long =
        openEnlarger(assets, dir, enlargerKind(choice.upscaler), choice.model.file, choice.scale, choice.tta, gpu)

    /** The upscaler kinds [openEnlarger] takes, by the choice they make. */
    fun enlargerKind(upscaler: PictureAdjustment.Upscaler): Int = when (upscaler) {
        PictureAdjustment.Upscaler.NEURAL -> 3
        PictureAdjustment.Upscaler.REALSR -> 2
        PictureAdjustment.Upscaler.REALCUGAN -> 1
        else -> 0
    }
}
