package com.hawkynt.mediadroid.player

import kotlin.math.sqrt

/**
 * How playback has been going lately, for the statistics overlay: the last [capacity] frame times, times
 * on screen, processor work and search scales, with the late and dropped frames counted. Filled from the
 * player's threads and read from the screen's, so every call is synchronised.
 *
 * - **Frame time**: the time between two pictures arriving from the decoder.
 * - **On screen**: the time between two new pictures reaching the screen.
 * - **Jitter**: how far the frame times spread around their mean (their standard deviation), in ms.
 * - **Judder**: how unevenly pictures stay on screen — the standard deviation of the times on screen
 *   against their mean, in percent; 0 is perfectly even.
 */
class PlaybackStatistics(private val capacity: Int = 240) {

    /** What the overlay draws, copied out at one moment. */
    class Snapshot(
        val frameTimesMs: FloatArray,
        val shownTimesMs: FloatArray,
        val workMs: FloatArray,
        val scales: IntArray,
        val late: Int,
        val dropped: Int,
    ) {
        val framesPerSecond: Float get() = perSecond(frameTimesMs)
        val shownPerSecond: Float get() = perSecond(shownTimesMs)
        val jitterMs: Float get() = deviation(frameTimesMs)
        val judderPercent: Float
            get() {
                if (shownTimesMs.isEmpty()) return 0f
                val mean = shownTimesMs.average().toFloat()
                return if (mean > 0f) deviation(shownTimesMs) / mean * 100f else 0f
            }

        private fun perSecond(times: FloatArray): Float {
            if (times.isEmpty()) return 0f
            val mean = times.average().toFloat()
            return if (mean > 0f) 1000f / mean else 0f
        }

        private fun deviation(values: FloatArray): Float {
            if (values.isEmpty()) return 0f
            val mean = values.average()
            return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size).toFloat()
        }
    }

    private val frameTimes = Ring(capacity)
    private val shownTimes = Ring(capacity)
    private val work = Ring(capacity)
    private val scales = Ring(capacity)
    private var lastArrival: Long? = null
    private var lastShown: Long? = null
    private var late = 0
    private var dropped = 0

    /** A picture came from the decoder at [atNanos] (System.nanoTime or the decoder's release time). */
    @Synchronized
    fun frameArrived(atNanos: Long) {
        lastArrival?.let { frameTimes.add((atNanos - it) / 1e6f) }
        lastArrival = atNanos
    }

    /** A new picture reached the screen at [atNanos]. */
    @Synchronized
    fun pictureShown(atNanos: Long) {
        lastShown?.let { shownTimes.add((atNanos - it) / 1e6f) }
        lastShown = atNanos
    }

    /** The processor took [nanos] for a frame's work (Film's matching, Motion's search). */
    @Synchronized
    fun work(nanos: Long) = work.add(nanos / 1e6f)

    /** Motion searched at [scale]. */
    @Synchronized
    fun searchScale(scale: Int) = scales.add(scale.toFloat())

    /** A frame's work came too late to be used. */
    @Synchronized
    fun late() {
        late++
    }

    /** A frame was left out on purpose (Film's repeated frame). */
    @Synchronized
    fun dropped() {
        dropped++
    }

    /** Forgets everything, for a new video. */
    @Synchronized
    fun clear() {
        frameTimes.clear()
        shownTimes.clear()
        work.clear()
        scales.clear()
        lastArrival = null
        lastShown = null
        late = 0
        dropped = 0
    }

    @Synchronized
    fun snapshot() = Snapshot(
        frameTimes.toArray(),
        shownTimes.toArray(),
        work.toArray(),
        scales.toArray().map { it.toInt() }.toIntArray(),
        late,
        dropped,
    )

    /** The last [capacity] values, oldest first. */
    private class Ring(private val capacity: Int) {
        private val values = FloatArray(capacity)
        private var start = 0
        private var size = 0

        fun add(value: Float) {
            values[(start + size) % capacity] = value
            if (size < capacity) size++ else start = (start + 1) % capacity
        }

        fun clear() {
            start = 0
            size = 0
        }

        fun toArray() = FloatArray(size) { values[(start + it) % capacity] }
    }
}
