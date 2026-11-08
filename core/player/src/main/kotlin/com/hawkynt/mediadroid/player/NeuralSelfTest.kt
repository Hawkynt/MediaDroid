package com.hawkynt.mediadroid.player

/**
 * A neural network's self-test on the device, as every platform runs it: on the GPU first, then on the CPU unless the
 * GPU kept up, each unit in a process of its own that a network too large for the device can end without taking the
 * app along. Each unit may take up to [LOAD_WAIT_MS] to start and load the network — a graphics chip compiles its
 * shaders then, seconds on a phone — and then its share of [NeuralInterpolation.MAX_WAIT_MS] to run it ([Deadline],
 * [across]). How one unit is tested is the platform's: a [OneUnit] runs a test and answers.
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

    /** The longest a unit may take to start its test process and load the network, before it runs anything. */
    const val LOAD_WAIT_MS = 10_000.0

    /**
     * When one unit's test is cut short (System.nanoTime): [LOAD_WAIT_MS] from [startNanos] while the network loads,
     * then [runNanos] from the moment it was [loaded] — the test's first report that it runs.
     */
    class Deadline(private val startNanos: Long, private val runNanos: Long, private val nowNanos: () -> Long) {
        private var loadedAt: Long? = null

        /** The network is loaded and its test runs, at [atNanos]; only the first call counts. */
        fun loaded(atNanos: Long = nowNanos()) {
            if (loadedAt == null) loadedAt = atNanos
        }

        /** The moment the test is cut short, as far as it is known now. */
        val until: Long get() = loadedAt?.let { it + runNanos } ?: (startNanos + (LOAD_WAIT_MS * 1e6).toLong())

        /** Whether [atNanos] is past [until]. */
        fun passed(atNanos: Long = nowNanos()): Boolean = atNanos >= until
    }

    /**
     * One unit's test, cut short at its [Deadline], which it tells when the network is loaded: the unit's timing, a
     * [Answer] with none where the unit cannot run the network or runs it wrong, or null where its process ended
     * without answering.
     */
    fun interface OneUnit {
        fun test(unit: String, deadline: Deadline): Answer?
    }

    /** One unit's answer: its timing, or null where it cannot run the network or runs it wrong. */
    data class Answer(val timing: Timing?)

    /**
     * The test on every unit in [UNITS] by [unit], stopping once one keeps up ([fast], on the first size's time),
     * each with its load and its share of [NeuralInterpolation.MAX_WAIT_MS] ([Deadline]); [nowNanos] tells the time.
     */
    fun across(
        fast: (Double) -> Boolean,
        nowNanos: () -> Long = System::nanoTime,
        unit: OneUnit,
    ): Outcome {
        val timings = ArrayList<Timing>()
        var ended = 0
        val share = (NeuralInterpolation.MAX_WAIT_MS * 1e6 / UNITS.size).toLong()
        for (name in UNITS) {
            if (timings.any { fast(it.msByHeight.values.first()) }) break
            when (val one = unit.test(name, Deadline(nowNanos(), share, nowNanos))) {
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
