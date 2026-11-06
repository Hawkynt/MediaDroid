package com.hawkynt.mediadroid.player

import kotlin.math.roundToInt

/**
 * Burn-in care for OLED screens: the picture — and with it the edges of its black bars — and the overlays that stay
 * on screen for long (subtitles, the statistics, the histogram) drift a few pixels over time, so no edge stays on the
 * same pixels for hours. One pixel every [STEP_MS], too slow to see: over a square of ±[amplitude] pixels row by row,
 * each row the other way (every step to a neighbouring pixel), and back again, starting from the middle — every
 * offset in the square visited, none jumped to.
 */
object BurnInDrift {
    /** How long the picture stays at one offset. */
    const val STEP_MS = 30_000L

    /**
     * How far the picture drifts either way on a screen [screenHeight] pixels high: four pixels at 1080p, two at the
     * least, more on a sharper screen — a few of its pixels, a sliver of the picture.
     */
    fun amplitude(screenHeight: Int): Int = maxOf(2, (screenHeight / 270.0).roundToInt())

    /** The offset in pixels, across and down, [timeMs] into the drift. */
    fun offset(timeMs: Long, amplitude: Int): Pair<Int, Int> {
        if (amplitude <= 0) return 0 to 0
        val side = 2 * amplitude + 1
        val cells = side * side
        val cycle = 2L * (cells - 1)
        // The middle of the square is where the path starts.
        val step = (Math.floorDiv(timeMs, STEP_MS) + (cells - 1) / 2).mod(cycle)
        val index = (if (step < cells) step else cycle - step).toInt()
        val row = index / side
        val column = if (row % 2 == 0) index % side else side - 1 - index % side
        return (column - amplitude) to (row - amplitude)
    }

    /** How long the drift takes to come round to where it started. */
    fun period(amplitude: Int): Long {
        val side = 2 * amplitude + 1
        return 2L * (side * side - 1) * STEP_MS
    }
}
