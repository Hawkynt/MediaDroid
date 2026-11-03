package com.hawkynt.mediadroid.player

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Real-ESRGAN's compact network (realesr-general-x4v3, see app/THIRD-PARTY-NOTICE.Real-ESRGAN.txt) on one of the
 * device's units, a tile at a time (see [NeuralUpscaling]): a [NeuralUpscaling.TILE]-square RGB input, four times
 * that as RGB bytes out. An interpreter belongs to the thread that opened it.
 */
class NeuralUpscaler private constructor(
    private val interpreter: Interpreter,
    private val delegate: AutoCloseable?,
    /** Where it runs. */
    val unit: Unit,
) : AutoCloseable {

    /** The device's units a network can run on. */
    enum class Unit { NPU, GPU, CPU }

    private val input = interpreter.getInputTensor(0)
    private val output = interpreter.getOutputTensor(0)
    private val inBuf = ByteBuffer.allocateDirect(input.numBytes()).order(ByteOrder.nativeOrder())
    private val outBuf = ByteBuffer.allocateDirect(output.numBytes()).order(ByteOrder.nativeOrder())

    // A byte's value as the network's input takes it: quantised to its scale and zero point, or as a float.
    private val inQuantised = ByteArray(256) { v ->
        val q = input.quantizationParams()
        if (q.scale > 0f) (v / 255f / q.scale + q.zeroPoint).roundToInt().coerceIn(0, 255).toByte() else v.toByte()
    }
    private val outSide = NeuralUpscaling.TILE * NeuralUpscaling.SCALE

    // The output as RGB bytes already when it is 8-bit at a scale of 1/255 and no offset; otherwise converted.
    private val outDirect = output.dataType() == DataType.UINT8 &&
        abs(output.quantizationParams().scale * 255f - 1f) < 1e-3f && output.quantizationParams().zeroPoint == 0
    private val rgb = if (outDirect) outBuf else ByteBuffer.allocateDirect(outSide * outSide * 3)

    /**
     * Runs the network on [tile] of the RGBA [picture], [width] × [height] with its rows as stored, the edges
     * repeated outside; returns the output's [NeuralUpscaling.SCALE]-times square as RGB bytes, rows in the same
     * order — valid until the next call.
     */
    fun run(picture: ByteBuffer, width: Int, height: Int, tile: NeuralUpscaling.Tile): ByteBuffer {
        val side = NeuralUpscaling.TILE
        val float = input.dataType() == DataType.FLOAT32
        inBuf.rewind()
        for (ty in 0 until side) {
            val row = (tile.y + ty).coerceIn(0, height - 1) * width
            for (tx in 0 until side) {
                val o = (row + (tile.x + tx).coerceIn(0, width - 1)) * 4
                for (c in 0 until 3) {
                    val v = picture.get(o + c).toInt() and 0xFF
                    if (float) inBuf.putFloat(v / 255f) else inBuf.put(inQuantised[v])
                }
            }
        }
        inBuf.rewind()
        outBuf.rewind()
        interpreter.run(inBuf, outBuf)
        outBuf.rewind()
        if (!outDirect) convert()
        return rgb.also { it.rewind() }
    }

    private fun convert() {
        val q = output.quantizationParams()
        val float = output.dataType() == DataType.FLOAT32
        rgb.rewind()
        repeat(outSide * outSide * 3) {
            val v = if (float) outBuf.getFloat() else ((outBuf.get().toInt() and 0xFF) - q.zeroPoint) * q.scale
            rgb.put((v * 255f).roundToInt().coerceIn(0, 255).toByte())
        }
    }

    override fun close() {
        interpreter.close()
        delegate?.close()
    }

    companion object {
        private const val MODEL = "models/real-esrgan-general-x4v3-w8a8.tflite"

        /** Tiles timed per unit in the self-test, after one to warm up. */
        private const val SAMPLE_TILES = 3

        /** The network on [unit], or null where that unit cannot run it. */
        fun open(context: Context, unit: Unit): NeuralUpscaler? = runCatching {
            val model = context.assets.openFd(MODEL).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use {
                    it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
            val options = Interpreter.Options()
            val delegate: AutoCloseable? = when (unit) {
                Unit.NPU -> NnApiDelegate().also { options.addDelegate(it) }
                Unit.GPU -> GpuDelegate(GpuDelegate.Options().setPrecisionLossAllowed(true)).also {
                    options.addDelegate(it)
                }
                Unit.CPU -> null.also { options.setNumThreads(4) }
            }
            NeuralUpscaler(Interpreter(model, options), delegate, unit)
        }.getOrNull()

        /**
         * The self-test: for each unit that can run the network, what one 576p picture takes there, worked out from
         * a few of its tiles after one to warm up — fastest first. Takes a few seconds; not on the main thread.
         */
        fun selfTest(context: Context): List<Pair<Unit, Double>> {
            val width = 720
            val height = 576
            val picture = ByteBuffer.allocateDirect(width * height * 4)
            for (i in 0 until width * height) {
                // A test picture with edges in it: a diagonal gradient crossed by stripes.
                val x = i % width
                val y = i / width
                picture.put(((x + y) / 5).toByte()).put(if ((x / 9) % 2 == 0) 40 else 200.toByte())
                    .put((y / 3).toByte()).put(-1)
            }
            val tiles = NeuralUpscaling.tiles(width, height)
            return Unit.entries.mapNotNull { unit ->
                open(context, unit)?.use { net ->
                    runCatching {
                        net.run(picture, width, height, tiles[0])
                        val start = System.nanoTime()
                        for (k in 1..SAMPLE_TILES) net.run(picture, width, height, tiles[k])
                        val perTile = (System.nanoTime() - start) / 1e6 / SAMPLE_TILES
                        unit to perTile * tiles.size
                    }.getOrNull()
                }
            }.sortedBy { it.second }
        }
    }
}
