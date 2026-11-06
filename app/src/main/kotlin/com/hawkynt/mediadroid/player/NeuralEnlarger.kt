package com.hawkynt.mediadroid.player

import android.content.Context
import java.nio.ByteBuffer

/**
 * waifu2x, Real-CUGAN or RealSR (see app/THIRD-PARTY-NOTICE.*.txt) on ncnn, on the device's GPU (Vulkan) or its CPU:
 * an RGBA picture in, twice its size (RealSR four times) as RGB bytes out (see NeuralEnlarging). A network belongs
 * to the thread that opened it.
 */
class NeuralEnlarger private constructor(
    private var handle: Long,
    /** Which upscaler it is. */
    val upscaler: PictureAdjustment.Upscaler,
    /** Where it runs. */
    val unit: NeuralUpscaler.Unit,
) : AutoCloseable {

    private var out: ByteBuffer = ByteBuffer.allocateDirect(0)

    /**
     * The RGBA [picture] ([width] × [height], rows as stored) at twice its size as RGB bytes in the same row order —
     * valid until the next call; null where the network failed.
     */
    fun run(picture: ByteBuffer, width: Int, height: Int): ByteBuffer? {
        val scale = NeuralEnlarging.scale(upscaler)
        val bytes = width * height * scale * scale * 3
        if (out.capacity() < bytes) out = ByteBuffer.allocateDirect(bytes)
        val ran = NativeInterpolation.enlarge(handle, picture, width, height, out)
        return if (ran == 0) out.also { it.rewind() } else null
    }

    override fun close() {
        if (handle != 0L) NativeInterpolation.closeEnlarger(handle)
        handle = 0L
    }

    companion object {
        private const val REALSR_KIND = 2

        /** [upscaler] (waifu2x or Real-CUGAN) on [unit] (the GPU or the CPU), or null where that unit cannot run it. */
        fun open(context: Context, upscaler: PictureAdjustment.Upscaler, unit: NeuralUpscaler.Unit): NeuralEnlarger? {
            if (!upscaler.isEnlarger || unit == NeuralUpscaler.Unit.NPU || !AndroidNeural.loaded) return null
            val gpu = unit == NeuralUpscaler.Unit.GPU
            if (gpu && !NativeInterpolation.hasGpu()) return null
            val download = NeuralModels.download(upscaler)
            val handle = if (download != null) {
                if (!com.hawkynt.mediadroid.data.ModelStore.ready(context, download)) return null
                val dir = com.hawkynt.mediadroid.data.ModelStore.folder(context, download).absolutePath
                NativeInterpolation.openEnlarger(null, dir, REALSR_KIND, gpu)
            } else {
                val cugan = upscaler == PictureAdjustment.Upscaler.REALCUGAN
                val dir = if (cugan) "models/realcugan-se" else "models/waifu2x-cunet"
                NativeInterpolation.openEnlarger(context.assets, dir, if (cugan) 1 else 0, gpu)
            }
            return if (handle == 0L) null else NeuralEnlarger(handle, upscaler, unit)
        }

        /**
         * The self-test of [upscaler] on [unit] (NeuralChecks.enlarger): whether the unit runs it right and what one
         * 576p picture takes there. Null where the unit cannot run it, or runs it wrong. Each run is told to
         * [progress].
         */
        fun selfTest(
            context: Context,
            upscaler: PictureAdjustment.Upscaler,
            unit: NeuralUpscaler.Unit,
            progress: NeuralChecks.Progress,
        ): NeuralSelfTest.Timing? = open(context, upscaler, unit)?.use { net ->
            NeuralChecks.enlarger(
                { picture, w, h -> net.run(picture, w, h) },
                NeuralEnlarging.scale(upscaler),
                unit.name,
                progress,
            ) { android.util.Log.i("NeuralEnlarger", "$upscaler $it") }
        }
    }
}
