package com.hawkynt.mediadroid.player

/**
 * A neural network's self-test on the device, as every platform runs it: on the GPU first, then on the CPU unless the
 * GPU kept up, each unit in a process of its own that a network too large for the device can end without taking the
 * app along — all of it within [NeuralInterpolation.MAX_WAIT_MS] from the tap, the GPU until half of that, the CPU
 * until the end ([across]). How one unit is tested is the platform's: a [OneUnit] runs a test and answers.
 */
object NeuralSelfTest {
    /** The units a network is tried on, in this order. */
    val UNITS = listOf("GPU", "CPU")

    /** What a unit's test found: what one picture took on [unit], by the height of each size tried. */
    data class Timing(val unit: String, val msByHeight: Map<Int, Double>)

    /** How a self-test came out. */
    sealed interface Outcome {
        /** It ran: what each unit that ran the network right took, fastest first (empty where none did). */
        data class Ran(val timings: List<Timing>) : Outcome

        /** Every unit's test process ended before it answered: the network was more than the device could give. */
        data object Ended : Outcome
    }

    /**
     * One unit's test, cut short by [untilNanos] (System.nanoTime) — its process started and the network loaded
     * included: the unit's timing, a [Answer] with none where the unit cannot run the network or runs it wrong, or
     * null where its process ended without answering.
     */
    fun interface OneUnit {
        fun test(unit: String, untilNanos: Long): Answer?
    }

    /** One unit's answer: its timing, or null where it cannot run the network or runs it wrong. */
    data class Answer(val timing: Timing?)

    /**
     * The test on every unit in [UNITS] by [unit], stopping once one keeps up ([fast], on the first size's time),
     * within [NeuralInterpolation.MAX_WAIT_MS] split between them; [nowNanos] tells the time.
     */
    fun across(
        fast: (Double) -> Boolean,
        nowNanos: () -> Long = System::nanoTime,
        unit: OneUnit,
    ): Outcome {
        val timings = ArrayList<Timing>()
        var ended = 0
        val start = nowNanos()
        for ((i, name) in UNITS.withIndex()) {
            if (timings.any { fast(it.msByHeight.values.first()) }) break
            val until = start + (NeuralInterpolation.MAX_WAIT_MS * 1e6 * (i + 1) / UNITS.size).toLong()
            when (val one = unit.test(name, until)) {
                null -> ended++
                else -> one.timing?.let { timings += it }
            }
        }
        return if (timings.isEmpty() && ended > 0) {
            Outcome.Ended
        } else {
            Outcome.Ran(timings.sortedBy { it.msByHeight.values.first() })
        }
    }

    /**
     * What a unit's test that ran out of time at [untilNanos] counts as: the times it reported so far ([times]), and
     * the size being made — [runningHeight], else [smallestHeight] while still loading — as having taken as long as a
     * whole test may, so it is far past any budget.
     */
    fun cutShort(unit: String, times: Map<Int, Double>, runningHeight: Int, smallestHeight: Int): Timing {
        val all = LinkedHashMap(times)
        all[runningHeight.takeIf { it != 0 } ?: smallestHeight] = NeuralInterpolation.MAX_WAIT_MS
        return Timing(unit, all)
    }
}
