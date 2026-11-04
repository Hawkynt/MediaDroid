package com.hawkynt.mediadroid.player

import android.content.Context
import java.nio.ByteBuffer

/**
 * waifu2x or Real-CUGAN (see app/THIRD-PARTY-NOTICE.waifu2x.txt and .Real-CUGAN.txt) on ncnn, on the device's GPU
 * (Vulkan) or its CPU: an RGBA picture in, twice its size as RGB bytes out (see NeuralEnlarging). A network belongs
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
        val bytes = width * height * NeuralEnlarging.SCALE * NeuralEnlarging.SCALE * 3
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

        /** Pictures timed at 576p, after one to warm up. */
        private const val SAMPLE_RUNS = 2

        /** [upscaler] (waifu2x or Real-CUGAN) on [unit] (the GPU or the CPU), or null where that unit cannot run it. */
        fun open(context: Context, upscaler: PictureAdjustment.Upscaler, unit: NeuralUpscaler.Unit): NeuralEnlarger? {
            if (!upscaler.isEnlarger || unit == NeuralUpscaler.Unit.NPU || !NativeInterpolation.loaded) return null
            val gpu = unit == NeuralUpscaler.Unit.GPU
            if (gpu && !NativeInterpolation.hasGpu()) return null
            val cugan = upscaler == PictureAdjustment.Upscaler.REALCUGAN
            val dir = if (cugan) "models/realcugan-se" else "models/waifu2x-cunet"
            val handle = NativeInterpolation.openEnlarger(context.assets, dir, cugan, gpu)
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

        // Whether [net] runs right on its unit, and what the check's picture took (after one to warm up).
        private fun check(net: NeuralEnlarger): Double? {
            val side = CHECK_SIDE
            val full = drawing(side * 2, side * 2)
            val half = ByteBuffer.allocateDirect(side * side * 4)
            for (y in 0 until side) for (x in 0 until side) for (c in 0 until 4) {
                var sum = 0
                for (dy in 0..1) for (dx in 0..1) sum += full.byte(((y * 2 + dy) * side * 2 + x * 2 + dx) * 4 + c)
                half.put((sum + 2) / 4)
            }
            half.rewind()
            net.run(half, side, side) ?: return null
            val start = System.nanoTime()
            val out = net.run(half, side, side) ?: return null
            val ms = (System.nanoTime() - start) / 1e6
            var error = 0L
            var count = 0
            val wide = side * 2
            for (y in EDGE until side - EDGE) for (x in EDGE until side - EDGE) for (c in 0 until 3) {
                var sum = 0
                for (dy in 0..1) for (dx in 0..1) sum += out.byte(((y * 2 + dy) * wide + x * 2 + dx) * 3 + c)
                error += kotlin.math.abs((sum + 2) / 4 - (half.get((y * side + x) * 4 + c).toInt() and 0xFF))
                count++
            }
            val mean = error.toDouble() / count
            android.util.Log.i("NeuralEnlarger", "${net.upscaler} check on ${net.unit}: off by $mean, $ms ms")
            return if (NeuralEnlarging.rightEnough(mean)) ms else null
        }

        private const val EDGE = 4

        /**
         * The test picture: flat colours with round edges, as drawings are — discs of 7 to 20 pixels' radius on a
         * 24-pixel grid, each its own colour, on a light ground (the desktop check's `drawing`).
         */
        private fun drawing(w: Int, h: Int): ByteBuffer = ByteBuffer.allocateDirect(w * h * 4).also { b ->
            for (y in 0 until h) for (x in 0 until w) {
                val cx = x / 24
                val cy = y / 24
                val dx = x - (cx * 24 + 12)
                val dy = y - (cy * 24 + 12)
                val radius = 7 + level(cx, cy, 3) % 14
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
