package com.hawkynt.mediadroid.player

/**
 * When frame blending is at which point between two frames. Frames reach the filters unevenly — Media3 hands
 * them over early or late by a few milliseconds, the device decoder's snapped to screen refreshes — and a phase
 * worked out from one frame's arrival and the last gap jumps with every wobble, and stops on a whole frame when
 * one comes a little late. Here every frame gets a steady time instead: the interval averaged over about eight
 * frames, the steady times following the real arrivals slowly. The picture shown runs [delayNs] behind on that
 * timeline — a frame and a quarter: one frame, as the frame after has to be there to blend towards it, and a
 * quarter so that one up to that much late is not waited for. Times are in nanoseconds on one clock.
 */
class BlendTimeline {
    /** The averaged time between frames; 0 until two have come. */
    var intervalNs = 0L
        private set

    private var lastArrivalNs = 0L
    private var lastSteadyNs = 0L

    /** How far behind its frames the picture runs: a frame and a quarter. */
    val delayNs: Long get() = intervalNs + intervalNs / LAG_PARTS

    /** A frame arrived at [atNs]; returns its steady time. */
    fun arrived(atNs: Long): Long {
        val gap = atNs - lastArrivalNs
        if (lastArrivalNs == 0L || gap <= 0 || gap > MAX_GAP_NS) {
            // The first frame, or the first after a pause or a seek: the timeline starts here.
            intervalNs = 0L
            lastSteadyNs = atNs
        } else {
            intervalNs = if (intervalNs == 0L) gap else intervalNs + (gap - intervalNs) / SMOOTHING
            val predicted = lastSteadyNs + intervalNs
            lastSteadyNs = predicted + (atNs - predicted) / SMOOTHING
        }
        lastArrivalNs = atNs
        return lastSteadyNs
    }

    /**
     * What to show at [nowNs] from frames with the steady times [steadyNs] (oldest first): the index of the earlier
     * of the two to mix and how far towards the next one (0..1). Past the newest, the newest whole.
     */
    fun position(nowNs: Long, steadyNs: LongArray): Pair<Int, Float> {
        val count = steadyNs.size
        if (count < 2) return 0 to 1f
        val shown = nowNs - delayNs
        if (shown <= steadyNs[0]) return 0 to 0f
        for (i in 0 until count - 1) {
            if (shown < steadyNs[i + 1]) {
                val span = (steadyNs[i + 1] - steadyNs[i]).coerceAtLeast(1L)
                return i to ((shown - steadyNs[i]).toFloat() / span).coerceIn(0f, 1f)
            }
        }
        return count - 2 to 1f
    }

    private companion object {
        /** A longer wait between two frames is a pause or a seek, not a slow frame rate. */
        const val MAX_GAP_NS = 250_000_000L

        /** How many frames the interval and the steady times follow. */
        const val SMOOTHING = 8

        /** The lag beyond one frame: a quarter of one. */
        const val LAG_PARTS = 4
    }
}
