package com.hawkynt.mediadroyd.player

/**
 * When to show pictures that a decoder hands over at uneven moments: Media3 releases a software decoder's
 * pictures up to 30 ms early, now and then two in one go, and a view shows only the later of two pictures
 * that arrive within one refresh. The pacer spaces them by the average time between arrivals — measured, so
 * it follows any playback speed — and catches up at once when pictures pile up or after a pause.
 * Times are in nanoseconds on one clock.
 */
class FramePacer {
    private var intervalNs = 0L
    private var lastArrivalNs = 0L
    private var lastShownNs = 0L

    /** A picture arrived at [nowNs]. */
    fun arrived(nowNs: Long) {
        val gap = nowNs - lastArrivalNs
        if (lastArrivalNs == 0L || gap <= 0 || gap > MAX_GAP_NS) {
            // The first picture, or the first after a pause or a seek: nothing to pace by yet.
            intervalNs = 0L
            lastShownNs = 0L
        } else {
            intervalNs = if (intervalNs == 0L) gap else intervalNs + (gap - intervalNs) / SMOOTHING
        }
        lastArrivalNs = nowNs
    }

    /** When the oldest of [queued] waiting pictures is to be shown, at [nowNs] or later. */
    fun dueAt(nowNs: Long, queued: Int): Long {
        if (intervalNs == 0L || lastShownNs == 0L || queued >= MAX_QUEUED) return nowNs
        return maxOf(nowNs, lastShownNs + intervalNs)
    }

    /** A picture was shown at [nowNs]. */
    fun shown(nowNs: Long) {
        lastShownNs = nowNs
    }

    companion object {
        /** Pictures waiting at which the pacer stops spacing them and catches up. */
        const val MAX_QUEUED = 3

        /** A longer wait between two pictures is a pause or a seek, not a slow frame rate. */
        private const val MAX_GAP_NS = 250_000_000L

        /** How many arrivals the average interval follows. */
        private const val SMOOTHING = 8
    }
}
