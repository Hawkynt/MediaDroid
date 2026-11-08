package com.hawkynt.mediadroyd.player

/**
 * The screen's refresh period, measured from the screen's refresh callbacks. The filters' stage asks for one only
 * while pictures wait, so the gap after a callback it did not follow with another spans several refreshes; only the
 * gap between two asked for back to back ([askedAgain]) is one refresh and counts, smoothed over about eight.
 * Gaps no screen has — shorter than 4 ms, longer than 50 ms — are left out, so a 24 Hz second screen still counts.
 */
class RefreshPeriod(initialNs: Long = 16_666_667L) {

    /** The refresh period measured so far, in nanoseconds. */
    var periodNs: Long = initialNs
        private set

    private var lastNs = 0L
    private var chained = false

    /** A refresh callback came at [frameTimeNanos]. */
    fun refreshed(frameTimeNanos: Long) {
        val gap = frameTimeNanos - lastNs
        if (chained && gap in MIN_NS..MAX_NS) periodNs += (gap - periodNs) / 8
        lastNs = frameTimeNanos
        chained = false
    }

    /** The next callback was asked for from within this one: the gap to it is one refresh. */
    fun askedAgain() {
        chained = true
    }

    private companion object {
        const val MIN_NS = 4_000_000L
        const val MAX_NS = 50_000_000L
    }
}
