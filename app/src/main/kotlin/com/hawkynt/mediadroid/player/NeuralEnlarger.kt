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
        /** The test picture's side for the check: small, so a device far too slow is told so at once. */
        private const val CHECK_SIDE = 128

        private const val REALSR_KIND = 2

        /** Pictures timed at 576p, after one to warm up. */
        private const val SAMPLE_RUNS = 2

        /** [upscaler] (waifu2x or Real-CUGAN) on [unit] (the GPU or the CPU), or null where that unit cannot run it. */
        fun open(context: Context, upscaler: PictureAdjustment.Upscaler, unit: NeuralUpscaler.Unit): NeuralEnlarger? {
            if (!upscaler.isEnlarger || unit == NeuralUpscaler.Unit.NPU || !NativeInterpolation.loaded) return null
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
         * The self-test of [upscaler] on [unit]: whether the unit runs it right — a drawing shrunk to half and
         * enlarged again, the output shrunk back against its input (see NeuralEnlarging.rightEnough) — and what one
         * 576p picture takes there, by its height; past the budget on the small check already, the 576p time is
         * estimated from it. Null where the unit cannot run it, or runs it wrong. Each run is told to [progress].
         */
        fun selfTest(
            context: Context,
            upscaler: PictureAdjustment.Upscaler,
            unit: NeuralUpscaler.Unit,
            progress: NeuralInterpolator.Companion.Progress,
        ): NeuralInterpolator.Timing? = open(context, upscaler, unit)?.use { net ->
            val height = PictureCheck.HEIGHT
            progress.running(height)
            val checkMs = check(net) ?: return@use null
            val pixels = PictureCheck.WIDTH * PictureCheck.HEIGHT
            if (!NeuralEnlarging.fastEnough(checkMs)) {
                val estimate = checkMs * pixels / (CHECK_SIDE * CHECK_SIDE)
                progress.timed(height, estimate)
                return@use NeuralInterpolator.Timing(unit, mapOf(height to estimate))
            }
            val picture = drawing(PictureCheck.WIDTH, PictureCheck.HEIGHT)
            net.run(picture, PictureCheck.WIDTH, PictureCheck.HEIGHT) ?: return@use null
            val start = System.nanoTime()
            repeat(SAMPLE_RUNS) { net.run(picture, PictureCheck.WIDTH, PictureCheck.HEIGHT) ?: return@use null }
            val ms = (System.nanoTime() - start) / 1e6 / SAMPLE_RUNS
            progress.timed(height, ms)
            NeuralInterpolator.Timing(unit, mapOf(height to ms))
        }

        // Whether [net] runs right on its unit, and what the check's picture took (after one to warm up): a drawing
        // shrunk by the upscaler's scale and enlarged again, the output shrunk back against its input.
        private fun check(net: NeuralEnlarger): Double? {
            val side = CHECK_SIDE
            val k = NeuralEnlarging.scale(net.upscaler)
            // The grid grows with the scale, so the small picture always has 12-pixel cells.
            val full = drawing(side * k, side * k, 12 * k)
            val small = ByteBuffer.allocateDirect(side * side * 4)
            for (y in 0 until side) for (x in 0 until side) for (c in 0 until 4) {
                var sum = 0
                for (dy in 0 until k) for (dx in 0 until k) {
                    sum += full.byte(((y * k + dy) * side * k + x * k + dx) * 4 + c)
                }
                small.put((sum + k * k / 2) / (k * k))
            }
            small.rewind()
            net.run(small, side, side) ?: return null
            val start = System.nanoTime()
            val out = net.run(small, side, side) ?: return null
            val ms = (System.nanoTime() - start) / 1e6
            var error = 0L
            var count = 0
            val wide = side * k
            for (y in EDGE until side - EDGE) for (x in EDGE until side - EDGE) for (c in 0 until 3) {
                var sum = 0
                for (dy in 0 until k) for (dx in 0 until k) sum += out.byte(((y * k + dy) * wide + x * k + dx) * 3 + c)
                error += kotlin.math.abs((sum + k * k / 2) / (k * k) - small.byte((y * side + x) * 4 + c))
                count++
            }
            val mean = error.toDouble() / count
            android.util.Log.i("NeuralEnlarger", "${net.upscaler} check on ${net.unit}: off by $mean, $ms ms")
            return if (NeuralEnlarging.rightEnough(mean)) ms else null
        }

        private const val EDGE = 4

        /**
         * The test picture: flat colours with round edges, as drawings are — a disc in each cell of a [cell]-pixel
         * grid, its radius 7/24 to 20/24 of the cell, each its own colour, on a light ground (the desktop check's
         * `drawing`).
         */
        private fun drawing(w: Int, h: Int, cell: Int = 24): ByteBuffer =
            ByteBuffer.allocateDirect(w * h * 4).also { b -> fill(b, w, h, cell) }

        private fun fill(b: ByteBuffer, w: Int, h: Int, cell: Int) {
            for (y in 0 until h) for (x in 0 until w) {
                val cx = x / cell
                val cy = y / cell
                val dx = x - (cx * cell + cell / 2)
                val dy = y - (cy * cell + cell / 2)
                val radius = (7 + level(cx, cy, 3) % 14) * cell / 24
                val inside = dx * dx + dy * dy <= radius * radius
                for (c in 0 until 3) b.put((if (inside) level(cx, cy, c) else 230).toByte())
                b.put(-1)
            }
            b.rewind()
        }

        private fun level(cx: Int, cy: Int, channel: Int): Int {
            var v = cx * 73856093 xor cy * 19349663 xor channel * 83492791
            v = (v xor (v ushr 13)) * 0x5bd1e995
            return (v xor (v ushr 15)) and 0xFF
        }

        private fun ByteBuffer.put(value: Int): ByteBuffer = put(value.toByte())

        private fun ByteBuffer.byte(at: Int): Int = get(at).toInt() and 0xFF
    }
}
