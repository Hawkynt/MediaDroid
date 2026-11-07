package com.hawkynt.mediadroid.player

/**
 * How work that must keep up with playback steps between levels of detail as it plays — level 0 the finest, up to
 * [coarsest] — from how long each piece took against the time between frames (Motion's search scale, a neural
 * network's working size). A piece over half the frame's time, or one done too late to be used, steps one coarser at
 * once; a finer step costs [finerCost] times as much, so it is taken only once that many times the last piece has
 * fitted in the budget, with room to spare, for [CALM_FRAMES] frames in a row, which keeps the level from swinging. A
 * finer step that has to be taken back soon after doubles the frames the next one waits for (up to
 * [MAX_CALM_FRAMES]); a long steady stretch halves that wait again.
 */
class StepPace(
    private val coarsest: Int,
    start: Int,
    private val finerCost: (level: Int) -> Double = { FINER_COST },
) {
    /** The level for the next piece of work. */
    var level: Int = start.coerceIn(0, coarsest)
        private set

    private var calm = 0
    private var calmNeeded = CALM_FRAMES
    private var sinceFiner = Int.MAX_VALUE
    private var steady = 0

    /** One piece took [workNanos] with frames [intervalNanos] apart; [late] when it came too late to be used. */
    fun report(workNanos: Long, intervalNanos: Long, late: Boolean) {
        val budget = intervalNanos * BUDGET_PERCENT / 100
        if (sinceFiner != Int.MAX_VALUE) sinceFiner++
        when {
            late || workNanos > budget -> {
                calm = 0
                steady = 0
                // Taken back soon after a finer step: the next try waits longer.
                if (sinceFiner <= calmNeeded) calmNeeded = (calmNeeded * 2).coerceAtMost(MAX_CALM_FRAMES)
                sinceFiner = Int.MAX_VALUE
                level = (level + 1).coerceAtMost(coarsest)
                return
            }
            level > 0 && workNanos * finerCost(level) <= budget * HEADROOM_PERCENT / 100.0 -> {
                if (++calm >= calmNeeded) {
                    calm = 0
                    sinceFiner = 0
                    level--
                }
            }
            else -> calm = 0
        }
        if (++steady >= calmNeeded * STEADY_TIMES && calmNeeded > CALM_FRAMES) {
            steady = 0
            calmNeeded /= 2
        }
    }

    companion object {
        /** How many frames in a row must leave room for a finer step before it is taken, at first. */
        const val CALM_FRAMES = 8

        /** The longest that wait grows after finer steps taken back. */
        const val MAX_CALM_FRAMES = 128

        /** How many waits' worth of frames without trouble halve the wait again. */
        private const val STEADY_TIMES = 4

        /** The part of the time between frames a piece of work may take. */
        const val BUDGET_PERCENT = 50

        /** How much a finer step costs against this one by default: twice the size each way, four times the pixels. */
        const val FINER_COST = 4.0

        /** How much of the budget the finer step may be expected to take. */
        private const val HEADROOM_PERCENT = 80
    }
}
