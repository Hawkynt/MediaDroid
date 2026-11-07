package com.hawkynt.mediadroid.stage

import com.hawkynt.mediadroid.player.Colorizing
import com.hawkynt.mediadroid.player.NeuralChecks
import com.hawkynt.mediadroid.shaders.Shaders
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Colour for black-and-white pictures (Colorizing): the network runs on a thread of its own, on the latest picture at
 * 256 × 256 whenever it is free, so playback never waits on it; its results are smoothed over time and laid under every
 * picture (glsl/video/colorize.frag), a cut dropping the colour until the new scene's has come. The same on every
 * platform; the platform says which network ([colored]'s key and opener) and where the coloured picture goes.
 */
class Colouring(private val gl: Gles, private val passes: Passes) {

    /** A colouriser: Colorizing.input's grey in, the a and b of Lab at 256 × 256 out; null where it failed. */
    interface Network : AutoCloseable {
        fun run(grey: FloatArray): FloatArray?
    }

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "colorize").apply { isDaemon = true } }
    private var job: Future<*>? = null

    // On the colour thread: the network open, and what it was opened for.
    private var open: Network? = null
    private var openFor: Any? = null

    private var last: FloatArray? = null
    private var cutAt = 0L
    private var runs = 0L
    private val smoother = Colorizing.Smoother()
    private val small = StageFrame(gl)
    private val ab = StageFrame(gl)
    private val read = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
    private val top = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())

    // A finished run's colour and the cut count it was started at, handed from the colour thread.
    @Volatile private var done: Pair<FloatArray, Long>? = null

    /**
     * [from] in colour, into the target [into] gives, where a result is in; [from] itself until then. The network is
     * the one [opener] opens (on the colour thread, once for each [key]: another key closes it and opens anew).
     */
    fun colored(from: DrawTarget, key: Any, opener: () -> Network?, into: () -> DrawTarget): DrawTarget {
        small.ensure(SIZE, SIZE)
        // A copy: a blend wholly of the one picture, the other input pointed at it too.
        passes.draw(Shaders.VIDEO_BLEND, small, from.texture, listOf(from.texture to "uPrevious")) {
            set("uWeight", 1f)
        }
        gl.bindFramebuffer(small.framebuffer)
        gl.pixelStorei(Gles.PACK_ALIGNMENT, 1)
        read.clear()
        gl.readPixels(SIZE, SIZE, read)
        // Read back bottom row first, as the stage holds pictures; the network wants the top first.
        top.clear()
        for (y in 0 until SIZE) {
            read.limit((SIZE - y) * SIZE * 4).position((SIZE - 1 - y) * SIZE * 4)
            top.put(read)
        }
        top.flip()
        val grey = Colorizing.input(top, SIZE, SIZE)
        if (Colorizing.cut(last, grey)) {
            cutAt = ++runs
            smoother.clear()
        }
        last = grey
        done?.let { (colour, at) ->
            done = null
            // A result from before the last cut belongs to the scene before.
            if (at == cutAt) {
                smoother.add(colour, cut = smoother.current == null)
                upload(smoother.current!!)
            }
        }
        if (job?.isDone != false) {
            val at = cutAt
            job = thread.submit {
                if (openFor != key) {
                    open?.close()
                    open = opener()
                    openFor = key
                }
                open?.run(grey)?.let { done = it to at }
            }
        }
        if (smoother.current == null) return from
        return passes.draw(Shaders.VIDEO_COLORIZE, into(), from.texture, listOf(ab.texture to "uAb"))
    }

    // The smoothed colour into the a-and-b texture: its rows turned back to the stage's, bottom first.
    private fun upload(colour: FloatArray) {
        val plane = SIZE * SIZE
        val bytes = ByteBuffer.allocateDirect(plane * 4 * 4).order(ByteOrder.nativeOrder())
        val texels = bytes.asFloatBuffer()
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val at = (SIZE - 1 - y) * SIZE + x
            texels.put(colour[at]).put(colour[plane + at]).put(0f).put(1f)
        }
        ab.ensure(SIZE, SIZE, half = true)
        gl.bindTexture(Gles.TEXTURE_2D, ab.texture)
        gl.texSubImage2D(SIZE, SIZE, Gles.RGBA, Gles.FLOAT, bytes)
    }

    /** Closes the network on its thread and lets the thread go; the frames go with the context, or [releaseFrames]. */
    fun close() {
        thread.submit { open?.close() }
        thread.shutdown()
    }

    /** Deletes the frames, on the stage's thread while its context is current. */
    fun releaseFrames() {
        small.release()
        ab.release()
    }

    companion object {
        private const val SIZE = Colorizing.SIZE

        /** [net] (a colouriser of the core's, NativeColorizer on ncnn) as the stage runs it. */
        fun <T> network(net: T): Network where T : NeuralChecks.Colorize, T : AutoCloseable = object : Network {
            override fun run(grey: FloatArray) = net.run(grey)

            override fun close() = net.close()
        }
    }
}
