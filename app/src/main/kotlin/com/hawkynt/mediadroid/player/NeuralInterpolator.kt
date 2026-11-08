package com.hawkynt.mediadroid.player

import android.content.Context
import com.hawkynt.mediadroid.shaders.NcnnShaders
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The native part's networks on Android: `libmediadroid_neural.so`, loaded on first use (see NativeInterpolation). */
internal object AndroidNeural {
    /** Whether the library loaded and was handed the compute shaders; false where the device's ABI has none. */
    val loaded: Boolean by lazy {
        NativeInterpolation.load({ System.loadLibrary("mediadroid_neural") }, NcnnShaders.ALL)
    }
}

/**
 * A neural frame interpolation network (RIFE, DAIN, XVFI; see app/THIRD-PARTY-NOTICE.*.txt) on ncnn, on
 * the device's GPU (Vulkan) or its CPU, at any size: two RGBA frames in, the in-between halfway as RGB bytes out. A
 * network belongs to the thread that opened it.
 */
class NeuralInterpolator private constructor(
    private var handle: Long,
    /** Which network it is. */
    val network: PictureAdjustment.Interpolation,
    /** Where it runs. */
    val unit: NeuralUpscaler.Unit,
) : AutoCloseable {

    private var out: ByteBuffer = ByteBuffer.allocateDirect(0)

    /**
     * The in-between of the RGBA frames [earlier] and [later], [width] × [height] with rows as stored, as RGB bytes
     * in the same row order — valid until the next call; null where the network failed. Frames with ids
     * ([earlierId], [laterId]: the same frame the same id, 0 for none) are worked on once: the later frame of one
     * pair, the earlier of the next, keeps its features.
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

    private var flows: ByteBuffer = ByteBuffer.allocateDirect(0)

    /**
     * The network's flow and blend mask between [earlier] and [later] instead of its picture, for one that hands them
     * back (NeuralInterpolation.handsFlow): NativeInterpolation.flow's floats in native order — valid until the next
     * call; null where the network failed or hands back only pictures.
     */
    fun flow(earlier: ByteBuffer, later: ByteBuffer, width: Int, height: Int): ByteBuffer? {
        val bytes = width * height * 8 * Float.SIZE_BYTES
        if (flows.capacity() < bytes) flows = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        val ran = NativeInterpolation.flow(handle, earlier, later, width, height, 0.5f, flows)
        return if (ran == 0) flows.also { it.rewind() } else null
    }

    override fun close() {
        if (handle != 0L) NativeInterpolation.close(handle)
        handle = 0L
    }

    companion object {
        private const val RIFE = "models/rife-4.25-lite"
        private const val IFRNET = "models/ifrnet-s"

        /**
         * [network] on [unit] (the GPU or the CPU), or null where that unit cannot run it — or, for a network
         * downloaded on demand (see NeuralModels), where it has not been downloaded.
         */
        fun open(
            context: Context,
            network: PictureAdjustment.Interpolation,
            unit: NeuralUpscaler.Unit,
        ): NeuralInterpolator? {
            if (unit == NeuralUpscaler.Unit.NPU || !AndroidNeural.loaded) return null
            val gpu = unit == NeuralUpscaler.Unit.GPU
            if (gpu && !NativeInterpolation.hasGpu()) return null
            val handle = when (network) {
                PictureAdjustment.Interpolation.RIFE -> NativeInterpolation.openRife(context.assets, RIFE, gpu)
                PictureAdjustment.Interpolation.IFRNET -> NativeInterpolation.openIfrnet(context.assets, IFRNET, gpu)
                PictureAdjustment.Interpolation.DAIN,
                PictureAdjustment.Interpolation.XVFI,
                PictureAdjustment.Interpolation.FLAVR,
                PictureAdjustment.Interpolation.CAIN,
                -> {
                    val download = NeuralModels.download(network) ?: return null
                    if (!com.hawkynt.mediadroid.data.ModelStore.ready(context, download)) return null
                    val dir = com.hawkynt.mediadroid.data.ModelStore.folder(context, download).absolutePath
                    when (network) {
                        PictureAdjustment.Interpolation.DAIN -> NativeInterpolation.openDain(dir, gpu)
                        PictureAdjustment.Interpolation.XVFI -> NativeInterpolation.openXvfi(dir, gpu)
                        PictureAdjustment.Interpolation.CAIN -> NativeInterpolation.openCain(dir, gpu)
                        else -> NativeInterpolation.openFlavr(dir, gpu)
                    }
                }
                else -> 0L
            }
            return if (handle == 0L) null else NeuralInterpolator(handle, network, unit)
        }

        /**
         * The self-test of [network] on [unit] (NeuralChecks.interpolator): whether the unit computes it right, and
         * what one in-between takes there at each test size while it keeps up; null where the unit cannot run it, or
         * runs it wrong. Each run is told to [progress] before it starts, so a run beyond
         * [NeuralInterpolation.MAX_WAIT_MS] can be cut short from outside. Not on the main thread.
         */
        fun selfTest(
            context: Context,
            network: PictureAdjustment.Interpolation,
            unit: NeuralUpscaler.Unit,
            progress: NeuralChecks.Progress,
        ): NeuralSelfTest.Timing? = open(context, network, unit)?.use { net ->
            val whole = NeuralChecks.Between { earlier, later, w, h, earlierId, laterId, before, after ->
                net.run(earlier, later, w, h, earlierId, laterId, before, after)
            }
            // What playback runs is timed: for RIFE and IFRNet their flow, the stage warping the frames by it.
            val flow = NeuralChecks.Between { earlier, later, w, h, _, _, _, _ -> net.flow(earlier, later, w, h) }
            NeuralChecks.interpolator(
                whole,
                unit.name,
                progress,
                log = { android.util.Log.i("NeuralInterpolator", "$network $it") },
                sizes = NeuralInterpolation.testSizes(network),
                timed = if (NeuralInterpolation.handsFlow(network)) flow else whole,
            )
        }
    }
}
