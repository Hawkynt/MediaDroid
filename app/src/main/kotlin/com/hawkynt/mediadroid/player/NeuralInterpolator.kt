package com.hawkynt.mediadroid.player

import android.content.Context
import android.content.res.AssetManager
import com.hawkynt.mediadroid.shaders.Shaders
import java.nio.ByteBuffer

/** The native part's neural frame interpolation (src/main/cpp/interpolator.cpp, on ncnn). */
internal object NativeInterpolation {
    /** Whether the native library loaded; false where the device's ABI has none, or off a device. */
    val loaded: Boolean by lazy { runCatching { System.loadLibrary("mediadroid_neural") }.isSuccess }

    /** Whether ncnn finds a Vulkan GPU to run on. */
    external fun hasGpu(): Boolean

    /** The network of the asset files [param] and [model], on the GPU or the CPU; 0 where it does not load. */
    external fun open(
        assets: AssetManager,
        param: String,
        model: String,
        gpu: Boolean,
        padding: Int,
        warp: String,
    ): Long

    /** The in-between at [time] of the RGBA frames [earlier] and [later] into [out] as RGB; 0 when it ran. */
    external fun between(
        handle: Long,
        earlier: ByteBuffer,
        later: ByteBuffer,
        width: Int,
        height: Int,
        time: Float,
        out: ByteBuffer,
    ): Int

    /** Frees the network. */
    external fun close(handle: Long)
}

/**
 * RIFE 4.25 lite (see app/THIRD-PARTY-NOTICE.RIFE.txt) on ncnn, on the device's GPU (Vulkan) or its CPU, at any size:
 * two RGBA frames in, the in-between halfway as RGB bytes out. A network belongs to the thread that opened it.
 */
class NeuralInterpolator private constructor(
    private var handle: Long,
    /** Where it runs. */
    val unit: NeuralUpscaler.Unit,
) : AutoCloseable {

    private var out: ByteBuffer = ByteBuffer.allocateDirect(0)

    /**
     * The in-between of the RGBA frames [earlier] and [later], [width] × [height] with rows as stored, as RGB bytes
     * in the same row order — valid until the next call; null where the network failed.
     */
    fun run(earlier: ByteBuffer, later: ByteBuffer, width: Int, height: Int): ByteBuffer? {
        if (out.capacity() < width * height * 3) out = ByteBuffer.allocateDirect(width * height * 3)
        val ran = NativeInterpolation.between(handle, earlier, later, width, height, 0.5f, out)
        return if (ran == 0) out.also { it.rewind() } else null
    }

    override fun close() {
        if (handle != 0L) NativeInterpolation.close(handle)
        handle = 0L
    }

    /** What the self-test found for one unit: what an in-between took at each tested size, by its height. */
    data class Timing(val unit: NeuralUpscaler.Unit, val msByHeight: Map<Int, Double>)

    companion object {
        private const val MODEL = "models/rife-4.25-lite"

        /** RIFE 4.25 lite takes sizes in steps of 128, as rife-ncnn-vulkan pads it. */
        private const val PADDING = 128

        /** In-betweens timed per size in the self-test, after one to warm up. */
        private const val SAMPLE_RUNS = 2

        /** The network on [unit] (the GPU or the CPU), or null where that unit cannot run it. */
        fun open(context: Context, unit: NeuralUpscaler.Unit): NeuralInterpolator? {
            if (unit == NeuralUpscaler.Unit.NPU || !NativeInterpolation.loaded) return null
            val gpu = unit == NeuralUpscaler.Unit.GPU
            if (gpu && !NativeInterpolation.hasGpu()) return null
            val handle = NativeInterpolation.open(
                context.assets, "$MODEL/flownet.param", "$MODEL/flownet.bin", gpu, PADDING, Shaders.NCNN_WARP,
            )
            return if (handle == 0L) null else NeuralInterpolator(handle, unit)
        }

        /**
         * The self-test: on each unit that can run the network and computes it right (see [check]), what one
         * in-between takes at each of [NeuralInterpolation.TEST_SIZES], smallest first and only on while the last
         * kept up — the fastest unit at the smallest size first. Takes seconds; not on the main thread.
         */
        fun selfTest(context: Context): List<Timing> = listOf(NeuralUpscaler.Unit.GPU, NeuralUpscaler.Unit.CPU)
            .mapNotNull { unit ->
                open(context, unit)?.use { net ->
                    if (!check(net)) return@use null
                    val times = LinkedHashMap<Int, Double>()
                    for (size in NeuralInterpolation.TEST_SIZES) {
                        val ms = time(net, size) ?: break
                        times[size.height] = ms
                        if (!NeuralInterpolation.fastEnough(ms)) break
                    }
                    if (times.isEmpty()) null else Timing(unit, times)
                }
            }
            .sortedBy { it.msByHeight.values.first() }

        /**
         * Whether [net] computes right on its unit: the in-between of two test frames 10 × 6 pixels apart against the
         * frame truly halfway, away from the edges where the motion uncovers what neither frame shows.
         */
        private fun check(net: NeuralInterpolator): Boolean {
            val w = 256
            val h = 256
            val first = testFrame(w, h, 0)
            val middle = testFrame(w, h, 1)
            val last = testFrame(w, h, 2)
            val between = net.run(first, last, w, h) ?: return false
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
                "NeuralInterpolator", "check on ${net.unit}: in-between off by $networkMean, a blend by $blendMean",
            )
            return NeuralInterpolation.rightEnough(networkMean, blendMean)
        }

        /** The margin the check leaves out. */
        private const val EDGE = 24

        // One in-between of two test frames at [size], from a few after one to warm up; null where it failed.
        private fun time(net: NeuralInterpolator, size: NeuralInterpolation.Size): Double? {
            val frames = Array(2) { shift -> testFrame(size.width, size.height, shift * 2) }
            net.run(frames[0], frames[1], size.width, size.height) ?: return null
            val start = System.nanoTime()
            repeat(SAMPLE_RUNS) { net.run(frames[0], frames[1], size.width, size.height) ?: return null }
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
