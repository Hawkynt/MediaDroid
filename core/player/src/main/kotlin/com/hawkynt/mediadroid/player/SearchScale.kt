package com.hawkynt.mediadroid.player

/**
 * How much smaller than the picture Motion's search runs — 1 (the whole picture), 2, 4, 8, 16 or 32 —
 * chosen as it plays from how long the searches take against the time between frames. A search over
 * half the frame's time, or vectors that came too late to be used, step one coarser at once; a finer step
 * costs about four times as much, so it is taken only once four times the last search has fitted in the
 * budget for [CALM_FRAMES] frames in a row, which keeps the scale from swinging. A finer step that has to
 * be taken back soon after doubles the frames the next one waits for (up to [MAX_CALM_FRAMES]); a long
 * steady stretch halves that wait again. It never goes so coarse that fewer than two 16-pixel blocks fit
 * each way.
 */
class SearchScale(val width: Int, val height: Int) {

    /** The scale for the next search. */
    var scale: Int = SCALES.firstOrNull { (width / it).toLong() * (height / it) <= START_PIXELS } ?: coarsest()
        private set

    private var calm = 0
    private var calmNeeded = CALM_FRAMES
    private var sinceFiner = Int.MAX_VALUE
    private var steady = 0

    init {
        scale = scale.coerceAtMost(coarsest())
    }

    /** One search took [searchNanos] with frames [intervalNanos] apart; [late] when its vectors came too late. */
    fun report(searchNanos: Long, intervalNanos: Long, late: Boolean) {
        val budget = intervalNanos * BUDGET_PERCENT / 100
        if (sinceFiner != Int.MAX_VALUE) sinceFiner++
        when {
            late || searchNanos > budget -> {
                calm = 0
                steady = 0
                // Taken back soon after a finer step: the next try waits longer.
                if (sinceFiner <= calmNeeded) calmNeeded = (calmNeeded * 2).coerceAtMost(MAX_CALM_FRAMES)
                sinceFiner = Int.MAX_VALUE
                scale = (scale * 2).coerceAtMost(coarsest())
                return
            }
            searchNanos * FINER_COST <= budget * HEADROOM_PERCENT / 100 -> {
                if (++calm >= calmNeeded && scale > 1) {
                    calm = 0
                    sinceFiner = 0
                    scale /= 2
                }
            }
            else -> calm = 0
        }
        if (++steady >= calmNeeded * STEADY_TIMES && calmNeeded > CALM_FRAMES) {
            steady = 0
            calmNeeded /= 2
        }
    }

    /** The coarsest scale that still leaves two blocks each way. */
    private fun coarsest(): Int =
        SCALES.lastOrNull { width / it >= MIN_SIZE && height / it >= MIN_SIZE } ?: 1

    companion object {
        /** The scales the search may run at. */
        val SCALES = listOf(1, 2, 4, 8, 16, 32)

        /** How many frames in a row must leave room for a finer step before it is taken, at first. */
        const val CALM_FRAMES = 8

        /** The longest that wait grows after finer steps taken back. */
        const val MAX_CALM_FRAMES = 128

        /** How many waits' worth of frames without trouble halve the wait again. */
        private const val STEADY_TIMES = 4

        /** The search picture to start from: about a DVD at half size, 1080p at a quarter. */
        private const val START_PIXELS = 160_000L

        /** The part of the time between frames the search may take. */
        private const val BUDGET_PERCENT = 50

        /** How much the next finer step costs against this one: four times the pixels. */
        private const val FINER_COST = 4

        /** How much of the budget the finer step may be expected to take. */
        private const val HEADROOM_PERCENT = 80

        /** The smallest search picture each way: two of the search's 16-pixel blocks. */
        private const val MIN_SIZE = 2 * MotionInterpolation.BLOCK
    }
}
