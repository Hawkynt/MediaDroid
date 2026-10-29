package com.hawkynt.mediadroid.player

import kotlin.math.abs

/**
 * Which field of an interlaced picture was taken first, told from the pictures themselves — the
 * decoders do not say, and a temporal deinterlacer at field rate that guesses wrong steps back and
 * forth in time. Each field is set between the other field of the frame before and of the frame after:
 * where it was taken first, it fits the earlier one better, and moving edges comb less. A verdict
 * stands once the last frames agree on it, so a still scene or a cut changes nothing.
 *
 * Converted from FFmpeg's `vf_idet.c` (LGPL-2.1-or-later, © 2012 Michael Niedermayer, see
 * `THIRD_PARTY_NOTICES.md`) with its default thresholds, on one plane.
 */
class FieldOrder {

    enum class Type(val label: String) {
        TOP_FIRST("tff"),
        BOTTOM_FIRST("bff"),
        PROGRESSIVE("progressive"),
        UNDETERMINED("undetermined"),
    }

    private val history = Array(HISTORY) { Type.UNDETERMINED }

    /** The verdict over the frames so far. */
    var order = Type.UNDETERMINED
        private set

    /** Whether to deinterlace top field first: so until the pictures tell otherwise, as FFmpeg assumes. */
    val topFieldFirst: Boolean get() = order != Type.BOTTOM_FIRST

    /** Judges [cur] between [prev] and [next], returns that frame's own verdict, and updates [order]. */
    fun add(prev: Deinterlacer.Plane, cur: Deinterlacer.Plane, next: Deinterlacer.Plane): Type {
        val type = single(prev, cur, next)
        history.copyInto(history, 1, 0, HISTORY - 1)
        history[0] = type
        var best = Type.UNDETERMINED
        var match = 0
        for (entry in history) {
            if (entry == Type.UNDETERMINED) continue
            if (best == Type.UNDETERMINED) best = entry
            if (entry == best) {
                match++
            } else {
                match = 0
                break
            }
        }
        if (order == Type.UNDETERMINED) {
            if (match > 0) order = best
        } else if (match > 2) {
            order = best
        }
        return type
    }

    /** Forgets every verdict, for a new film. */
    fun reset() {
        history.fill(Type.UNDETERMINED)
        order = Type.UNDETERMINED
    }

    companion object {
        private const val HISTORY = 4
        private const val INTERLACE_THRESHOLD = 1.04
        private const val PROGRESSIVE_THRESHOLD = 1.5

        /** One frame's verdict: how much each field combs against the neighbouring frames' other field. */
        fun single(prev: Deinterlacer.Plane, cur: Deinterlacer.Plane, next: Deinterlacer.Plane): Type {
            val alpha = LongArray(2)
            var delta = 0L
            for (y in 2 until cur.height - 2) {
                alpha[y and 1] += comb(cur, prev, y)
                alpha[(y xor 1) and 1] += comb(cur, next, y)
                delta += comb(cur, cur, y)
            }
            return when {
                alpha[0] > INTERLACE_THRESHOLD * alpha[1] -> Type.TOP_FIRST
                alpha[1] > INTERLACE_THRESHOLD * alpha[0] -> Type.BOTTOM_FIRST
                alpha[1] > PROGRESSIVE_THRESHOLD * delta -> Type.PROGRESSIVE
                else -> Type.UNDETERMINED
            }
        }

        /** How far row [y] of [middle] sits off the mean of [cur]'s rows above and below it. */
        private fun comb(cur: Deinterlacer.Plane, middle: Deinterlacer.Plane, y: Int): Long {
            var sum = 0L
            for (x in 0 until cur.width) sum += abs(cur[x, y - 1] + cur[x, y + 1] - 2 * middle[x, y])
            return sum
        }
    }
}
