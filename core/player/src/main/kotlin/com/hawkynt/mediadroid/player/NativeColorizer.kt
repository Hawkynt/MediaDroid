package com.hawkynt.mediadroid.player

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DDColor on ncnn (NativeInterpolation.openColorizer), the same on every platform: the network's grey input in
 * (Colorizing.input), the a and b of Lab at 256 × 256 out. It belongs to the thread that opened it.
 */
class NativeColorizer private constructor(private var handle: Long, val gpu: Boolean) :
    NeuralChecks.Colorize, AutoCloseable {
    private val plane = Colorizing.SIZE * Colorizing.SIZE
    private val input = ByteBuffer.allocateDirect(3 * plane * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val output = ByteBuffer.allocateDirect(2 * plane * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** The a and b planes [grey] gets (a fresh array); null where the network failed. */
    override fun run(grey: FloatArray): FloatArray? {
        input.clear()
        input.put(grey)
        input.flip()
        output.clear()
        if (NativeInterpolation.colorize(handle, input, output) != 0) return null
        return FloatArray(2 * plane).also { output.get(it) }
    }

    override fun close() {
        if (handle != 0L) NativeInterpolation.closeColorizer(handle)
        handle = 0L
    }

    companion object {
        /**
         * DDColor from its files in [dir] on the GPU or the CPU ([gpu]); null where it does not load. The native
         * library must be loaded.
         */
        fun open(dir: String, gpu: Boolean): NativeColorizer? {
            val handle = NativeInterpolation.openColorizer(null, dir, gpu)
            return if (handle == 0L) null else NativeColorizer(handle, gpu)
        }
    }
}
