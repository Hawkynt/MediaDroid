package com.hawkynt.mediadroid.player

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/**
 * RIFE 4.25 lite (see app/THIRD-PARTY-NOTICE.RIFE.txt) on one of the device's units: two RGBA frames at
 * [NeuralInterpolation.WIDTH] × [NeuralInterpolation.HEIGHT] in, the in-between halfway as RGB bytes out. An
 * interpreter belongs to the thread that opened it.
 */
class NeuralInterpolator private constructor(
    private val interpreter: Interpreter,
    private val delegate: AutoCloseable?,
    /** Where it runs. */
    val unit: NeuralUpscaler.Unit,
) : AutoCloseable {

    private val pixels = NeuralInterpolation.WIDTH * NeuralInterpolation.HEIGHT
    private val inputs = Array(2) { ByteBuffer.allocateDirect(pixels * 3 * 4).order(ByteOrder.nativeOrder()) }
    private val out = ByteBuffer.allocateDirect(pixels * 3 * 4).order(ByteOrder.nativeOrder())
    private val rgb = ByteBuffer.allocateDirect(pixels * 3)

    // Which of the network's two inputs is the earlier frame, by name (the converter keeps img0 and img1).
    private val earlierIndex = (0 until interpreter.inputTensorCount).firstOrNull {
        interpreter.getInputTensor(it).name().contains("img0")
    } ?: 0

    /**
     * The in-between of the RGBA frames [earlier] and [later] (rows as stored, at the network's size), as RGB bytes
     * in the same row order — valid until the next call.
     */
    fun run(earlier: ByteBuffer, later: ByteBuffer): ByteBuffer {
        fill(inputs[earlierIndex], earlier)
        fill(inputs[1 - earlierIndex], later)
        out.rewind()
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inputs[0], inputs[1]), mapOf(0 to out))
        out.rewind()
        rgb.rewind()
        repeat(pixels * 3) { rgb.put((out.getFloat() * 255f).roundToInt().coerceIn(0, 255).toByte()) }
        return rgb.also { it.rewind() }
    }

    private fun fill(target: ByteBuffer, rgba: ByteBuffer) {
        target.rewind()
        for (i in 0 until pixels) {
            val o = i * 4
            target.putFloat((rgba.get(o).toInt() and 0xFF) / 255f)
            target.putFloat((rgba.get(o + 1).toInt() and 0xFF) / 255f)
            target.putFloat((rgba.get(o + 2).toInt() and 0xFF) / 255f)
        }
        target.rewind()
    }

    override fun close() {
        interpreter.close()
        delegate?.close()
    }

    companion object {
        private const val MODEL = "models/rife-4.25-lite-576p.tflite"

        /** In-betweens timed per unit in the self-test, after one to warm up. */
        private const val SAMPLE_RUNS = 2

        /**
         * The network on [unit], or null where that unit cannot run it. The GPU computes in full floats: in half
         * floats the sampling positions of a 768-wide frame lose too much (the float16 conversion came out wrong).
         */
        fun open(context: Context, unit: NeuralUpscaler.Unit): NeuralInterpolator? = runCatching {
            val model = context.assets.openFd(MODEL).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use {
                    it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
            val options = Interpreter.Options()
            val delegate: AutoCloseable? = when (unit) {
                NeuralUpscaler.Unit.NPU -> NnApiDelegate().also { options.addDelegate(it) }
                NeuralUpscaler.Unit.GPU -> GpuDelegate(GpuDelegate.Options().setPrecisionLossAllowed(false)).also {
                    options.addDelegate(it)
                }
                NeuralUpscaler.Unit.CPU -> null.also { options.setNumThreads(4) }
            }
            NeuralInterpolator(Interpreter(model, options), delegate, unit)
        }.getOrNull()

        /**
         * The self-test: for each unit that can run the network, what one 576p in-between takes there, from a few
         * runs after one to warm up — fastest first. Takes a few seconds; not on the main thread.
         */
        fun selfTest(context: Context): List<Pair<NeuralUpscaler.Unit, Double>> {
            val w = NeuralInterpolation.WIDTH
            val h = NeuralInterpolation.HEIGHT
            // Two test frames with edges, the second moved a few pixels.
            val frames = Array(2) { shift ->
                ByteBuffer.allocateDirect(w * h * 4).also { b ->
                    for (i in 0 until w * h) {
                        val x = i % w + shift * 5
                        val y = i / w + shift * 3
                        b.put(((x + y) / 6).toByte()).put(if ((x / 11) % 2 == 0) 50 else 210.toByte())
                            .put((y / 3).toByte()).put(-1)
                    }
                    b.rewind()
                }
            }
            return NeuralUpscaler.Unit.entries.mapNotNull { unit ->
                open(context, unit)?.use { net ->
                    runCatching {
                        net.run(frames[0], frames[1])
                        val start = System.nanoTime()
                        repeat(SAMPLE_RUNS) { net.run(frames[0], frames[1]) }
                        unit to (System.nanoTime() - start) / 1e6 / SAMPLE_RUNS
                    }.getOrNull()
                }
            }.sortedBy { it.second }
        }
    }
}
