package com.hawkynt.mediadroid.player

import android.content.Context
import android.content.res.AssetManager
import com.hawkynt.mediadroid.shaders.Shaders
import java.nio.ByteBuffer

/** The native part's neural frame interpolation (src/main/cpp/interpolator.cpp, on ncnn). */
internal object NativeInterpolation {
    /**
     * Whether the native library loaded — and was handed the compute shaders of glsl/ncnn; false where the device's
     * ABI has none, or off a device.
     */
    val loaded: Boolean by lazy {
        runCatching { System.loadLibrary("mediadroid_neural") }.isSuccess.also { ok ->
            if (ok) {
                val all = mapOf(
                    "warp" to Shaders.NCNN_WARP,
                    "dain-correlation" to Shaders.NCNN_DAIN_CORRELATION,
                    "dain-flow-warp" to Shaders.NCNN_DAIN_FLOW_WARP,
                    "dain-projection-zero" to Shaders.NCNN_DAIN_PROJECTION_ZERO,
                    "dain-projection-project" to Shaders.NCNN_DAIN_PROJECTION_PROJECT,
                    "dain-projection-average" to Shaders.NCNN_DAIN_PROJECTION_AVERAGE,
                    "dain-projection-fill-hole" to Shaders.NCNN_DAIN_PROJECTION_FILL_HOLE,
                    "dain-filter-interpolation" to Shaders.NCNN_DAIN_FILTER_INTERPOLATION,
                    "dain-filter-interpolation-pack4" to Shaders.NCNN_DAIN_FILTER_INTERPOLATION_PACK4,
                )
                shaders(all.keys.toTypedArray(), all.values.toTypedArray())
            }
        }
    }

    /** Whether ncnn finds a Vulkan GPU to run on. */
    external fun hasGpu(): Boolean

    /** Hands over the compute shaders by name. */
    external fun shaders(names: Array<String>, sources: Array<String>)

    /** RIFE from the asset folder [dir], on the GPU or the CPU; 0 where it does not load. */
    external fun openRife(assets: AssetManager, dir: String, gpu: Boolean): Long

    /** DAIN from the folder [dir] of downloaded files, on the GPU or the CPU; 0 where it does not load. */
    external fun openDain(dir: String, gpu: Boolean): Long

    /** XVFI from the folder [dir] of downloaded files, on the GPU or the CPU; 0 where it does not load. */
    external fun openXvfi(dir: String, gpu: Boolean): Long

    /**
     * The in-between at [time] of the RGBA frames [earlier] and [later] into [out] as RGB; 0 when it ran. The ids
     * ([earlierId], [laterId]; 0 where not known) let a frame met again keep what was worked out from it alone.
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
    ): Int

    /** Frees the network. */
    external fun close(handle: Long)

    /** waifu2x or Real-CUGAN ([realCugan]) from the asset folder [dir], on the GPU or the CPU; 0 where not loaded. */
    external fun openEnlarger(assets: AssetManager, dir: String, realCugan: Boolean, gpu: Boolean): Long

    /** The RGBA [picture] ([width] × [height]) at twice its size into [out] as RGB; 0 when it ran. */
    external fun enlarge(handle: Long, picture: ByteBuffer, width: Int, height: Int, out: ByteBuffer): Int

    /** Frees the upscaler. */
    external fun closeEnlarger(handle: Long)
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
    ): ByteBuffer? {
        if (out.capacity() < width * height * 3) out = ByteBuffer.allocateDirect(width * height * 3)
        val ran = NativeInterpolation.between(handle, earlier, later, width, height, 0.5f, out, earlierId, laterId)
        return if (ran == 0) out.also { it.rewind() } else null
    }

    override fun close() {
        if (handle != 0L) NativeInterpolation.close(handle)
        handle = 0L
    }

    /** What the self-test found for one unit: what an in-between took at each tested size, by its height. */
    data class Timing(val unit: NeuralUpscaler.Unit, val msByHeight: Map<Int, Double>)

    companion object {
        private const val RIFE = "models/rife-4.25-lite"

        /** In-betweens timed per size in the self-test, in a stream after one pair to warm up. */
        private const val SAMPLE_RUNS = 2

        /**
         * [network] on [unit] (the GPU or the CPU), or null where that unit cannot run it — or, for a network
         * downloaded on demand (see NeuralModels), where it has not been downloaded.
         */
        fun open(
            context: Context,
            network: PictureAdjustment.Interpolation,
            unit: NeuralUpscaler.Unit,
        ): NeuralInterpolator? {
            if (unit == NeuralUpscaler.Unit.NPU || !NativeInterpolation.loaded) return null
            val gpu = unit == NeuralUpscaler.Unit.GPU
            if (gpu && !NativeInterpolation.hasGpu()) return null
            val handle = when (network) {
                PictureAdjustment.Interpolation.RIFE -> NativeInterpolation.openRife(context.assets, RIFE, gpu)
                PictureAdjustment.Interpolation.DAIN, PictureAdjustment.Interpolation.XVFI -> {
                    val download = NeuralModels.download(network) ?: return null
                    if (!com.hawkynt.mediadroid.data.ModelStore.ready(context, download)) return null
                    val dir = com.hawkynt.mediadroid.data.ModelStore.folder(context, download).absolutePath
                    if (network == PictureAdjustment.Interpolation.DAIN) {
                        NativeInterpolation.openDain(dir, gpu)
                    } else {
                        NativeInterpolation.openXvfi(dir, gpu)
                    }
                }
                else -> 0L
            }
            return if (handle == 0L) null else NeuralInterpolator(handle, network, unit)
        }

        /** What a self-test tells as it goes, for whoever waits on it to cut it short (see NeuralTestService). */
        interface Progress {
            /** An in-between at the test size of [height] is being made. */
            fun running(height: Int)

            /** One at [height] took [ms]. */
            fun timed(height: Int, ms: Double)
        }

        /**
         * The self-test of [network] on [unit]: whether the unit computes it right (see [check]), and what one
         * in-between takes there at each of [NeuralInterpolation.TEST_SIZES], smallest first and only on while the
         * last kept up; null where the unit cannot run it, or runs it wrong. Each run is told to [progress] before it
         * starts, so a run beyond [NeuralInterpolation.MAX_WAIT_MS] can be cut short from outside. Not on the main
         * thread.
         */
        fun selfTest(
            context: Context,
            network: PictureAdjustment.Interpolation,
            unit: NeuralUpscaler.Unit,
            progress: Progress,
        ): Timing? = open(context, network, unit)?.use { net ->
            val smallest = NeuralInterpolation.TEST_SIZES.first()
            progress.running(smallest.height)
            val checkMs = check(net) ?: return@use null
            // The check timed a small picture already: past the budget there, no test size can keep up — the
            // smallest is estimated from it by its pixels, and the device is given nothing larger.
            if (NeuralInterpolation.tooSlowAlready(checkMs)) {
                val estimate = NeuralInterpolation.estimate(checkMs, CHECK_SIDE * CHECK_SIDE)
                progress.timed(smallest.height, estimate)
                return@use Timing(unit, mapOf(smallest.height to estimate))
            }
            val times = LinkedHashMap<Int, Double>()
            for (size in NeuralInterpolation.TEST_SIZES) {
                progress.running(size.height)
                val ms = time(net, size) ?: break
                times[size.height] = ms
                progress.timed(size.height, ms)
                if (!NeuralInterpolation.fastEnough(ms)) break
            }
            if (times.isEmpty()) null else Timing(unit, times)
        }

        /**
         * Whether [net] computes right on its unit: the in-between of two test frames 10 × 6 pixels apart against the
         * frame truly halfway, away from the edges where the motion uncovers what neither frame shows — what it took
         * where it is right, null where it is not.
         */
        private fun check(net: NeuralInterpolator): Double? {
            val w = CHECK_SIDE
            val h = CHECK_SIDE
            val first = testFrame(w, h, 0)
            val middle = testFrame(w, h, 1)
            val last = testFrame(w, h, 2)
            // Once to warm up — the first run sets the unit up (on the GPU its pipelines), which is no measure.
            net.run(first, last, w, h) ?: return null
            val start = System.nanoTime()
            val between = net.run(first, last, w, h) ?: return null
            val ms = (System.nanoTime() - start) / 1e6
            var networkError = 0L
            var blendError = 0L
            var count = 0
            for (y in EDGE until h - EDGE) for (x in EDGE until w - EDGE) for (c in 0 until 3) {
                val i = (y * w + x) * 4 + c
                val truth = middle.get(i).toInt() and 0xFF
                val blend = ((first.get(i).toInt() and 0xFF) + (last.get(i).toInt() and 0xFF)) / 2
                networkError += kotlin.math.abs((between.get((y * w + x) * 3 + c).toInt() and 0xFF) - truth)
                blendError += kotlin.math.abs(blend - truth)
                count++
            }
            val networkMean = networkError.toDouble() / count
            val blendMean = blendError.toDouble() / count
            android.util.Log.i(
                "NeuralInterpolator",
                "${net.network} check on ${net.unit}: in-between off by $networkMean, a blend by $blendMean, $ms ms",
            )
            return if (NeuralInterpolation.rightEnough(networkMean, blendMean)) ms else null
        }

        /** How many times the budget an in-between may take warming up before the self-test stops timing it. */
        private const val FAR_TOO_SLOW = 10

        /**
         * The side of the check's square test frames: small, so a network far too slow for the device is told so
         * at once, without a long job for the graphics chip, which would hold up the whole screen meanwhile.
         */
        private const val CHECK_SIDE = 128

        /** The margin the check leaves out. */
        private const val EDGE = 20

        // One in-between at [size] as playback makes them: a stream of test frames, each pair sharing its earlier
        // frame with the pair before (and so its features, by the frames' ids); the first pair warms up, the pairs
        // after it are timed. Null where it failed.
        private fun time(net: NeuralInterpolator, size: NeuralInterpolation.Size): Double? {
            val frames = Array(SAMPLE_RUNS + 2) { k -> testFrame(size.width, size.height, k * 2) }
            fun pair(k: Int) = net.run(frames[k], frames[k + 1], size.width, size.height, k + 1L, k + 2L)
            val warm = System.nanoTime()
            pair(0) ?: return null
            // Far too slow already warming up: that is answer enough, without waiting for more of it.
            val warmMs = (System.nanoTime() - warm) / 1e6
            if (warmMs > FAR_TOO_SLOW * NeuralInterpolation.BUDGET_MS) return warmMs
            val start = System.nanoTime()
            for (k in 1..SAMPLE_RUNS) pair(k) ?: return null
            return (System.nanoTime() - start) / 1e6 / SAMPLE_RUNS
        }

        /**
         * A test frame: smooth random blotches about [CELL] pixels across in each colour, moved 5 × 3 pixels per
         * [shift]. Nothing in it repeats, so its motion has one answer — stripes would let a network take a move
         * for one the other way.
         */
        private fun testFrame(w: Int, h: Int, shift: Int): ByteBuffer = ByteBuffer.allocateDirect(w * h * 4).also { b ->
            for (y in 0 until h) for (x in 0 until w) {
                val sx = x + shift * 5
                val sy = y + shift * 3
                for (c in 0 until 3) b.put(noise(sx, sy, c).toByte())
                b.put(-1)
            }
            b.rewind()
        }

        private const val CELL = 8

        // Value noise: a random level per cell corner, bilinear between them; the same on every device.
        private fun noise(x: Int, y: Int, channel: Int): Int {
            val cx = Math.floorDiv(x, CELL)
            val cy = Math.floorDiv(y, CELL)
            val fx = (x - cx * CELL).toFloat() / CELL
            val fy = (y - cy * CELL).toFloat() / CELL
            val top = level(cx, cy, channel) * (1 - fx) + level(cx + 1, cy, channel) * fx
            val bottom = level(cx, cy + 1, channel) * (1 - fx) + level(cx + 1, cy + 1, channel) * fx
            return (top * (1 - fy) + bottom * fy).toInt()
        }

        private fun level(cx: Int, cy: Int, channel: Int): Float {
            var v = cx * 73856093 xor cy * 19349663 xor channel * 83492791
            v = (v xor (v ushr 13)) * 0x5bd1e995
            return ((v xor (v ushr 15)) and 0xFF).toFloat()
        }
    }
}
