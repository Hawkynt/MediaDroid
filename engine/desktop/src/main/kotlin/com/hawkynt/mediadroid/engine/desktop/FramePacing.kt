package com.hawkynt.mediadroid.engine.desktop

/**
 * How pictures reached the screen against the clock they were due by (the presentation benchmark, README "Platform
 * support"): how many the clock passed by without them being shown (dropped) and how many extra periods a picture
 * stayed on screen because the next came late (doubled).
 */
class FramePacing(val dropped: Int, val doubled: Int) {

    /** What presenting a picture cost, in milliseconds: on average, at the 95th percentile and at worst. */
    class Costs(val meanMs: Double, val p95Ms: Double, val maxMs: Double) {
        companion object {
            /** The costs [nanos] (one per picture) summed up; the percentile is the nearest rank. */
            fun of(nanos: LongArray): Costs {
                if (nanos.isEmpty()) return Costs(0.0, 0.0, 0.0)
                val sorted = nanos.sorted()
                val rank = Math.ceil(sorted.size * 0.95).toInt().coerceIn(1, sorted.size)
                return Costs(sorted.average() / 1e6, sorted[rank - 1] / 1e6, sorted.last() / 1e6)
            }
        }
    }

    companion object {
        /**
         * The pacing of pictures shown at [shownNanos], each the clock's picture [content] (its index at the clock's
         * [periodNanos]). A picture held at least three quarters of a period past its due counts as doubled once per
         * period — so a faster screen's refresh landing half a period either side of the due time counts as neither.
         */
        fun of(content: IntArray, shownNanos: LongArray, periodNanos: Long): FramePacing {
            require(content.size == shownNanos.size)
            var dropped = 0
            var doubled = 0
            for (i in 1 until content.size) {
                val steps = content[i] - content[i - 1]
                dropped += maxOf(0, steps - 1)
                val excess = (shownNanos[i] - shownNanos[i - 1] - steps * periodNanos).toDouble() / periodNanos
                doubled += maxOf(0, Math.floor(excess + 0.25).toInt())
            }
            return FramePacing(dropped, doubled)
        }
    }
}
