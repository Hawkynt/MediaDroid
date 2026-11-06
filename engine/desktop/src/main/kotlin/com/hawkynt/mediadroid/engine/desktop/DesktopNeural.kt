package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.player.ModelFolder
import com.hawkynt.mediadroid.player.NativeInterpolation
import com.hawkynt.mediadroid.player.NeuralEnlarging
import com.hawkynt.mediadroid.player.NeuralModels
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.shaders.NcnnShaders
import java.io.File
import java.nio.ByteBuffer

/**
 * The neural networks on the desktop: the native library (NativeNetworks, `libmediadroid.so` / `mediadroid.dll`) runs
 * them on ncnn — on the GPU through Vulkan, or on the CPU where no Vulkan device is there — from their files in
 * [models], a folder per network in the app-data folder: the ones the app carries unpacked there from its resources on
 * first use, every file checked against its digest (ModelFolder.unpack); the others fetched there when picked
 * (ModelFolder.fetch). A network belongs to the thread that opened it.
 */
class DesktopNeural(val models: File) {

    /** Whether the native library loaded and took the compute shaders. */
    val loaded: Boolean
        get() = NativeNetworks.loaded &&
            NativeInterpolation.load({ check(NativeNetworks.loaded) }, NcnnShaders.ALL)

    /** Whether ncnn finds a Vulkan device. */
    val hasGpu: Boolean get() = loaded && NativeInterpolation.hasGpu()

    /** Where [download]'s files are kept. */
    fun folder(download: NeuralModels.Download): File = File(models, download.folder)

    /**
     * Whether [download]'s files are in place: one the app carries is unpacked first (checked in full then, by size
     * after); one fetched must have been fetched.
     */
    fun ready(download: NeuralModels.Download): Boolean {
        if (download in CARRIED && !ModelFolder.ready(folder(download), download)) {
            return ModelFolder.unpack(folder(download), download) { name ->
                DesktopNeural::class.java.getResourceAsStream("/models/${download.folder}/$name")
            }
        }
        return ModelFolder.ready(folder(download), download)
    }

    /** [network] on the GPU or the CPU ([gpu]), or null where it cannot run there or its files are not in place. */
    fun interpolator(network: PictureAdjustment.Interpolation, gpu: Boolean): Interpolator? {
        if (!network.isNeural || !loaded || (gpu && !hasGpu)) return null
        val files = NeuralModels.files(network) ?: return null
        if (!ready(files)) return null
        val dir = folder(files).path
        val handle = when (network) {
            PictureAdjustment.Interpolation.RIFE -> NativeInterpolation.openRife(null, dir, gpu)
            PictureAdjustment.Interpolation.IFRNET -> NativeInterpolation.openIfrnet(null, dir, gpu)
            PictureAdjustment.Interpolation.DAIN -> NativeInterpolation.openDain(dir, gpu)
            PictureAdjustment.Interpolation.XVFI -> NativeInterpolation.openXvfi(dir, gpu)
            PictureAdjustment.Interpolation.CAIN -> NativeInterpolation.openCain(dir, gpu)
            PictureAdjustment.Interpolation.FLAVR -> NativeInterpolation.openFlavr(dir, gpu)
            else -> 0L
        }
        return if (handle == 0L) null else Interpolator(handle, network, gpu)
    }

    /**
     * [choice] — a model of waifu2x, Real-CUGAN, RealSR or Real-ESRGAN, once or eight ways — on the GPU or the CPU
     * ([gpu]), or null where it cannot run there or its files are not in place.
     */
    fun enlarger(choice: NeuralEnlarging.Choice, gpu: Boolean): Enlarger? {
        if (!onNcnn(choice.upscaler) || !loaded || (gpu && !hasGpu)) return null
        val files = choice.model.files
        if (!ready(files)) return null
        val handle = NativeInterpolation.openEnlarger(null, folder(files).path, choice, gpu)
        val budget = if (gpu) NativeInterpolation.gpuHeapBudget() else 0
        return if (handle == 0L) null else Enlarger(handle, choice, gpu, budget)
    }

    companion object {
        /** What the desktop carries: the app's own networks, and Real-ESRGAN's compact video model on ncnn. */
        val CARRIED = NeuralModels.CARRIED + NeuralModels.REALESRGAN

        /** Whether [upscaler] is a network the desktop runs on ncnn: every neural one, Real-ESRGAN included. */
        fun onNcnn(upscaler: PictureAdjustment.Upscaler): Boolean =
            upscaler.isEnlarger || upscaler == PictureAdjustment.Upscaler.NEURAL
    }

    /** A frame interpolation network: two RGBA frames in, the in-between halfway as RGB bytes out. */
    class Interpolator internal constructor(
        private var handle: Long,
        val network: PictureAdjustment.Interpolation,
        val gpu: Boolean,
    ) : AutoCloseable {
        private var out: ByteBuffer = ByteBuffer.allocateDirect(0)

        /**
         * The in-between of the RGBA frames [earlier] and [later] ([width] × [height], rows as stored) as RGB bytes in
         * the same row order — valid until the next call; null where the network failed. A frame met again (the same
         * id, 0 for none) keeps what was worked out from it; [before] and [after] are the frames around the pair.
         */
        fun run(
            earlier: ByteBuffer,
            later: ByteBuffer,
            width: Int,
            height: Int,
            earlierId: Long = 0,
            laterId: Long = 0,
            before: ByteBuffer? = null,
            after: ByteBuffer? = null,
        ): ByteBuffer? {
            if (out.capacity() < width * height * 3) out = ByteBuffer.allocateDirect(width * height * 3)
            val ran = NativeInterpolation.between(
                handle, earlier, later, width, height, 0.5f, out, earlierId, laterId, before, after,
            )
            return if (ran == 0) out.also { it.rewind() } else null
        }

        override fun close() {
            if (handle != 0L) NativeInterpolation.close(handle)
            handle = 0L
        }
    }

    /**
     * An upscaler: an RGBA picture in, [scale] times its size as RGB bytes out — whole, or in tiles where the GPU's
     * memory for it ([budgetMb]) asks for them (NeuralEnlarging.tile).
     */
    class Enlarger internal constructor(
        private var handle: Long,
        val choice: NeuralEnlarging.Choice,
        val gpu: Boolean,
        private val budgetMb: Int,
    ) : AutoCloseable {
        private var out: ByteBuffer = ByteBuffer.allocateDirect(0)

        /** How much larger it makes a picture. */
        val scale: Int = choice.scale

        /**
         * The RGBA [picture] ([width] × [height], rows as stored) at [scale] times its size as RGB bytes in the same
         * row order — valid until the next call; null where the network failed. Whole, or in tiles of [tile] pixels a
         * side: by default where the GPU's memory asks for them.
         */
        fun run(
            picture: ByteBuffer,
            width: Int,
            height: Int,
            tile: Int = NeuralEnlarging.tile(choice, budgetMb, width, height),
        ): ByteBuffer? {
            val bytes = width * height * scale * scale * 3
            if (out.capacity() < bytes) out = ByteBuffer.allocateDirect(bytes)
            val ran = NativeInterpolation.enlarge(handle, picture, width, height, out, tile)
            return if (ran == 0) out.also { it.rewind() } else null
        }

        override fun close() {
            if (handle != 0L) NativeInterpolation.closeEnlarger(handle)
            handle = 0L
        }
    }
}
