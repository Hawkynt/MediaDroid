package com.hawkynt.mediadroid.player

/**
 * When the picture on screen is read back from the graphics card where the stage presents it itself (PictureWindow):
 * only when something asks for its pixels — a snapshot once, the histogram every [histogramEveryMs] while it shows —
 * and then once for all of them.
 */
class ReadBackDemand(private val histogramEveryMs: Long) {
    private var snapshotAsked = false
    private var histogramAtMs: Long? = null

    /** What a read-back is wanted for: [snapshot], [histogram]. */
    data class Wanted(val snapshot: Boolean, val histogram: Boolean) {
        /** Whether a read-back is wanted at all. */
        val any: Boolean get() = snapshot || histogram
    }

    /** A snapshot is asked for: the next [wanted] reads the picture back for it. */
    @Synchronized
    fun askSnapshot() {
        snapshotAsked = true
    }

    /**
     * What the picture shown at [nowMs] is wanted for, each answered once: the snapshot asked for, the histogram when
     * it is [histogramShown] and due — at once when it was off before.
     */
    @Synchronized
    fun wanted(nowMs: Long, histogramShown: Boolean): Wanted {
        val snapshot = snapshotAsked
        snapshotAsked = false
        val last = histogramAtMs
        val histogram = histogramShown && (last == null || nowMs - last >= histogramEveryMs)
        if (!histogramShown) histogramAtMs = null else if (histogram) histogramAtMs = nowMs
        return Wanted(snapshot, histogram)
    }
}
