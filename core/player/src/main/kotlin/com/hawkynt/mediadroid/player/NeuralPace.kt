package com.hawkynt.mediadroid.player

import kotlin.math.roundToInt

/**
 * The size a frame interpolation network works at, chosen as it plays (StepPace): the self-test's answer
 * (NeuralInterpolation.workingSize) is only where it starts. An in-between over half the time between frames halves
 * the size each way at once; steady room for the next size up — the material's own above the start, twice the size
 * each way below it — doubles it again. Its [sizes]: the material's [own] size, the [start] where that is smaller,
 * then halves of it down to [MIN_LINES] lines, both sides even.
 */
class NeuralPace(
    own: NeuralInterpolation.Size,
    start: NeuralInterpolation.Size,
    budgetPercent: Int = StepPace.BUDGET_PERCENT,
) {

    /** The sizes it steps between, largest first. */
    val sizes: List<NeuralInterpolation.Size> = buildList {
        if (start.area < own.area) add(own)
        var size = start
        while (size.height >= MIN_LINES) {
            add(size)
            size = NeuralInterpolation.Size(even(size.width / 2.0), even(size.height / 2.0))
        }
        if (isEmpty()) add(start)
    }

    private val pace = StepPace(
        sizes.lastIndex,
        sizes.indexOf(start).coerceAtLeast(0),
        { level -> sizes[level - 1].area.toDouble() / sizes[level].area },
        budgetPercent,
    )

    /** The size for the next in-between. */
    val size: NeuralInterpolation.Size get() = sizes[pace.level]

    /** One in-between took [workNanos] with frames [intervalNanos] apart. */
    fun report(workNanos: Long, intervalNanos: Long) = pace.report(workNanos, intervalNanos, late = false)

    private fun even(value: Double): Int = ((value / 2).roundToInt() * 2).coerceAtLeast(2)

    companion object {
        /** The smallest the network works at, in lines: a flow from there still beats a blend at 1080p. */
        const val MIN_LINES = 120

        /**
         * The part of the frame's time an in-between made on a thread of its own may take ([budgetPercent]): it has a
         * whole frame (BlendTimeline.extraFrames), less a quarter for the decoder and the read-back.
         */
        const val OWN_THREAD_PERCENT = 75
    }
}
