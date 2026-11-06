package com.hawkynt.mediadroid.player

import android.content.Context
import java.nio.ByteBuffer

/**
 * A model of waifu2x, Real-CUGAN or RealSR (see app/THIRD-PARTY-NOTICE.*.txt) on ncnn, on the device's GPU (Vulkan) or
 * its CPU, once or eight ways: an RGBA picture in, two, three or four times its size as RGB bytes out (see
 * NeuralEnlarging) — whole, or in tiles where the GPU's memory asks for them. A network belongs to the thread that
 * opened it.
 */
class NeuralEnlarger private constructor(
    private var handle: Long,
    /** Which model it is, and whether it runs eight ways. */
    val choice: NeuralEnlarging.Choice,
    /** Where it runs. */
    val unit: NeuralUpscaler.Unit,
    // What ncnn lets the GPU's networks take, in MB; 0 on the CPU.
    private val budgetMb: Int,
) : AutoCloseable {

    private var out: ByteBuffer = ByteBuffer.allocateDirect(0)

    /**
     * The RGBA [picture] ([width] × [height], rows as stored) at the model's scale as RGB bytes in the same row order —
     * valid until the next call; null where the network failed.
     */
    fun run(picture: ByteBuffer, width: Int, height: Int): ByteBuffer? {
        val scale = choice.scale
        val bytes = width * height * scale * scale * 3
        if (out.capacity() < bytes) out = ByteBuffer.allocateDirect(bytes)
        val tile = NeuralEnlarging.tile(choice, budgetMb, width, height)
        val ran = NativeInterpolation.enlarge(handle, picture, width, height, out, tile)
        return if (ran == 0) out.also { it.rewind() } else null
    }

    override fun close() {
        if (handle != 0L) NativeInterpolation.closeEnlarger(handle)
        handle = 0L
    }

    companion object {
        /**
         * [choice] on [unit] (the GPU or the CPU), its model from the app's assets where carried, else from its fetched
         * files; null where that unit cannot run it or its files are not there.
         */
        fun open(context: Context, choice: NeuralEnlarging.Choice, unit: NeuralUpscaler.Unit): NeuralEnlarger? {
            if (!choice.upscaler.isEnlarger || unit == NeuralUpscaler.Unit.NPU || !AndroidNeural.loaded) return null
            val gpu = unit == NeuralUpscaler.Unit.GPU
            if (gpu && !NativeInterpolation.hasGpu()) return null
            val files = choice.model.files
            val handle = if (files in NeuralModels.CARRIED) {
                NativeInterpolation.openEnlarger(context.assets, "models/${files.folder}", choice, gpu)
            } else {
                if (!com.hawkynt.mediadroid.data.ModelStore.ready(context, files)) return null
                val dir = com.hawkynt.mediadroid.data.ModelStore.folder(context, files).absolutePath
                NativeInterpolation.openEnlarger(null, dir, choice, gpu)
            }
            val budget = if (gpu) NativeInterpolation.gpuHeapBudget() else 0
            return if (handle == 0L) null else NeuralEnlarger(handle, choice, unit, budget)
        }

        /**
         * The self-test of [choice] on [unit] (NeuralChecks.enlarger): whether the unit runs it right and what one
         * 576p picture takes there. Null where the unit cannot run it, or runs it wrong. Each run is told to
         * [progress].
         */
        fun selfTest(
            context: Context,
            choice: NeuralEnlarging.Choice,
            unit: NeuralUpscaler.Unit,
            progress: NeuralChecks.Progress,
        ): NeuralSelfTest.Timing? = open(context, choice, unit)?.use { net ->
            NeuralChecks.enlarger(
                { picture, w, h -> net.run(picture, w, h) },
                choice.scale,
                unit.name,
                progress,
            ) { android.util.Log.i("NeuralEnlarger", "${choice.key} $it") }
        }
    }
}
