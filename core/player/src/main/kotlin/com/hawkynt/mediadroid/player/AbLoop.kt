package com.hawkynt.mediadroid.player

/**
 * Repeating a part of a video between two marks, worked with one button as mpv's `ab-loop`: the first press marks the
 * start ([a]), the second the end ([b]) and repeating begins, the third ends it. Playing up to the end jumps back to
 * the start; a seek before the start plays on into the part.
 */
data class AbLoop(val a: Long?, val b: Long?) {

    /** Both marks set: the part repeats. */
    val repeating: Boolean get() = a != null && b != null

    /** The button pressed at [positionMs]: the next state. An end too close to the start is not taken. */
    fun mark(positionMs: Long): AbLoop = when {
        a == null -> AbLoop(positionMs, null)
        b == null -> {
            val (start, end) = if (positionMs < a) positionMs to a else a to positionMs
            if (end - start < MIN_LENGTH_MS) this else AbLoop(start, end)
        }
        else -> OFF
    }

    /** Where to jump from [positionMs]: back to the start once the end is reached, else null. */
    fun jumpFrom(positionMs: Long): Long? = if (a != null && b != null && positionMs >= b) a else null

    companion object {
        val OFF = AbLoop(null, null)

        /** The shortest part that repeats: shorter would only stutter. */
        const val MIN_LENGTH_MS = 1_000L
    }
}
