package com.hawkynt.mediadroid.player

import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * One picture forward or back while paused (mpv's `frame-step` / `frame-back-step`). Both engines seek exactly and
 * show the first picture at or after the time asked for, so a step aims at the whole millisecond at or just before a
 * frame's start on the frame grid: it shows that frame, and stepping from there finds the same frame again, so a
 * thousand steps land where a thousand frames do.
 */
object FrameStep {

    /** The frame rate assumed when the file does not say: film's, so a step is never shorter than a frame. */
    const val FALLBACK_FPS = 24_000f / 1_001f

    /** Where a step from [positionMs] lands, at [fps] frames a second, inside 0 .. [durationMs] (0 when unknown). */
    fun target(positionMs: Long, fps: Float, forward: Boolean, durationMs: Long): Long {
        val rate = if (fps.isFinite() && fps > 0f) fps.toDouble() else FALLBACK_FPS.toDouble()
        // The frame showing at the position: the last one whose (truncated) start is not after it.
        val guess = floor((positionMs + 1) * rate / 1000.0).toLong()
        val frame = if (startOf(guess, rate) <= positionMs) guess else guess - 1
        val wanted = (frame + if (forward) 1 else -1).coerceAtLeast(0)
        val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return startOf(wanted, rate).coerceIn(0L, upper)
    }

    /** Frame [index]'s start at [rate] frames a second, down to the whole millisecond. */
    private fun startOf(index: Long, rate: Double): Long = floor(index * 1000.0 / rate + 1e-9).roundToLong()
}
