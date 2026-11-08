package com.hawkynt.mediadroyd.player

import android.content.Context
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The frame interpolation network on a thread of its own (#468): the stage hands it one pair at a time and goes on
 * drawing, the picture running a frame later (BlendTimeline.extraFrames), so a network has a whole frame's time for
 * an in-between instead of the half it could hold up the drawing for. The network is opened, run and closed on that
 * thread only (NeuralInterpolator belongs to the thread that opened it). A pair handed over while one is still being
 * made, or before the last answer was taken, is not taken: that pair is blended.
 */
internal class NeuralWorker(private val context: Context) : AutoCloseable {
    /**
     * One pair: [earlier] and [later] (RGBA, [width] × [height], read back from the frames) for [network] on [unit],
     * its flow and mask asked for where [flow] (NeuralInterpolation.handsFlow on a GLSL ES 3 chip), else its picture;
     * [laterId] the later frame's arrival (the stage's keptAt), [before] the frame before for FLAVR.
     */
    class Job(
        val network: PictureAdjustment.Interpolation,
        val unit: NeuralUpscaler.Unit,
        val flow: Boolean,
        val width: Int,
        val height: Int,
        val earlierId: Long,
        val laterId: Long,
        val earlier: ByteBuffer,
        val later: ByteBuffer,
        val before: ByteBuffer?,
    )

    /** A pair made: [result] (the network's floats or RGB bytes, valid until the next job) and what it took. */
    class Done(val job: Job, val result: ByteBuffer, val tookNanos: Long)

    private val thread = Executors.newSingleThreadExecutor { Thread(it, "neural-inbetween") }
    private val done = AtomicReference<Done?>()

    @Volatile
    private var busy = false

    // On the worker thread only.
    private var net: NeuralInterpolator? = null

    /** Whether a pair would be taken now: none being made, the last answer taken. Read back only when it is. */
    val idle: Boolean get() = !busy && done.get() == null

    /** Hands [job] over; false where it was not taken (see [idle]). Its buffers stay untouched until it is done. */
    fun submit(job: Job): Boolean {
        if (!idle) return false
        busy = true
        thread.execute {
            try {
                make(job)
            } finally {
                busy = false
            }
        }
        return true
    }

    /** The pair made since the last call, or null; taking it frees the worker for the next pair. */
    fun take(): Done? = done.getAndSet(null)

    private fun make(job: Job) {
        val open = net
        val ready = if (open != null && open.network == job.network && open.unit == job.unit) {
            open
        } else {
            open?.close()
            NeuralInterpolator.open(context, job.network, job.unit).also { net = it }
        } ?: return
        val began = System.nanoTime()
        val result = if (job.flow) {
            ready.flow(job.earlier, job.later, job.width, job.height)
        } else {
            ready.run(
                job.earlier, job.later, job.width, job.height, job.earlierId, job.laterId, before = job.before,
            )
        } ?: return
        done.set(Done(job, result, System.nanoTime() - began))
    }

    /** Closes the network on its thread, then the thread. */
    override fun close() {
        thread.execute {
            net?.close()
            net = null
        }
        thread.shutdown()
    }
}
