package com.hawkynt.mediadroid.player

/**
 * How much smaller than the picture Motion's search runs — 1 (the whole picture), 2, 4, 8, 16 or 32 —
 * chosen as it plays from how long the searches take against the time between frames (StepPace: a search over
 * half the frame's time, or vectors too late to be used, one step coarser at once; a finer step, four times the
 * work, only after steady room for it). It never goes so coarse that fewer than two 16-pixel blocks fit each way.
 */
class SearchScale(val width: Int, val height: Int) {

    private val pace: StepPace

    init {
        val coarsest = SCALES.indexOf(coarsest())
        val start = SCALES.indexOfFirst { (width / it).toLong() * (height / it) <= START_PIXELS }
            .let { if (it < 0) coarsest else it }
        pace = StepPace(coarsest, start)
    }

    /** The scale for the next search. */
    val scale: Int get() = SCALES[pace.level]

    /** One search took [searchNanos] with frames [intervalNanos] apart; [late] when its vectors came too late. */
    fun report(searchNanos: Long, intervalNanos: Long, late: Boolean) = pace.report(searchNanos, intervalNanos, late)

    /** The coarsest scale that still leaves two blocks each way. */
    private fun coarsest(): Int =
        SCALES.lastOrNull { width / it >= MIN_SIZE && height / it >= MIN_SIZE } ?: 1

    companion object {
        /** The scales the search may run at. */
        val SCALES = listOf(1, 2, 4, 8, 16, 32)

        /** How many frames in a row must leave room for a finer step before it is taken, at first. */
        const val CALM_FRAMES = StepPace.CALM_FRAMES

        /** The longest that wait grows after finer steps taken back. */
        const val MAX_CALM_FRAMES = StepPace.MAX_CALM_FRAMES

        /** The search picture to start from: about a DVD at half size, 1080p at a quarter. */
        private const val START_PIXELS = 160_000L

        /** The smallest search picture each way: two of the search's 16-pixel blocks. */
        private const val MIN_SIZE = 2 * MotionInterpolation.BLOCK
    }
}
