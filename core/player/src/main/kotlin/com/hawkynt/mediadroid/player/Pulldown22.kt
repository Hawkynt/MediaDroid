package com.hawkynt.mediadroid.player

/**
 * Undoing 2:2 pulldown: film at 25 frames a second carried on 50 Hz video, each film frame as two fields. Woven
 * back together the fields give the film frame whole — no combing and none of a deinterlacer's blur. Usually both
 * fields of a frame come from one film frame ([Pairing.IN_FRAME]); after some edits and transfers each frame's
 * first field belongs with the frame before's second ([Pairing.ACROSS]). Which, if either, is found from the comb
 * factor of each field woven with the one before it: on film it drops on every other field — the field that
 * belongs with its predecessor — and stays high between film frames.
 *
 * DScaler's PAL film detection (`FD_50Hz.cpp`, its help's "2:2 Pulldown Settings" and "Shared Pulldown Settings";
 * GPL-2.0, behaviour followed and written anew, no code taken — see `THIRD_PARTY_NOTICES.md`): a drop to under
 * [DROP_PERCENT] % of the field before, on a moving picture, after a field that was no drop itself, counts; [HITS] in
 * a row on the same field of the frame and the video is taken for film. On film the field meant to belong with its
 * predecessor is checked as it comes: combed enough to show, on a moving picture, and [RISE_PERCENT] % up on the last
 * such field — an edit, or video cut into the film — and it is video again from that frame on, until film is found
 * anew.
 */
class Pulldown22 {

    /** How a frame's picture is made: by a deinterlacer, or woven from two fields of one film frame. */
    enum class Pairing {
        /** Video, or film not yet found: deinterlaced. */
        VIDEO,

        /** Both fields of the frame belong together: the frame as it is. */
        IN_FRAME,

        /** The frame's first field belongs with the second field of the frame before. */
        ACROSS,
    }

    // The field film pairs with its predecessor, by its place in the frame: 0 the first, 1 the second; -1 video.
    private var film = -1
    private var hits = 0
    private var hitField = -1
    private var fieldsSinceHit = Int.MAX_VALUE
    private var lastComb = 0L
    private var lastDecrease = 0L
    private var lastPairComb = 0L

    /** Forgets the cadence, for a seek or a new video. */
    fun reset() {
        film = -1
        hits = 0
        hitField = -1
        fieldsSinceHit = Int.MAX_VALUE
        lastComb = 0L
        lastDecrease = 0L
        lastPairComb = 0L
    }

    /**
     * Takes the luma of the next frame [cur], after [prev] (null after a start or a seek), its fields in the order
     * [topFieldFirst] says; returns how [cur] is to be shown.
     */
    fun add(prev: Deinterlacer.Plane?, cur: Deinterlacer.Plane, topFieldFirst: Boolean): Pairing {
        if (prev == null || prev.width != cur.width || prev.height != cur.height) {
            reset()
            return Pairing.VIDEO
        }
        // The first field woven with the frame before's second, then the second field with the first.
        val secondRows = acrossRows(topFieldFirst)
        field(0, combFactor(cur, prev, topRowsFromFirst = topFieldFirst), fieldDiff(cur, prev, 1 - secondRows))
        field(1, combFactor(cur, cur, topRowsFromFirst = true), fieldDiff(cur, prev, secondRows))
        return when (film) {
            0 -> Pairing.ACROSS
            1 -> Pairing.IN_FRAME
            else -> Pairing.VIDEO
        }
    }

    private fun field(place: Int, comb: Long, diff: Long) {
        if (fieldsSinceHit < Int.MAX_VALUE) fieldsSinceHit++
        val decrease = comb * 100 / (lastComb + 100)
        val moving = diff > MOVEMENT
        if (film < 0) {
            if (decrease < DROP_PERCENT && lastDecrease >= DROP_PERCENT && moving && lastComb > MIN_COMB) {
                // A hit continues the count only on the same field of the very next frame.
                hits = if (place == hitField && fieldsSinceHit == 2) hits + 1 else 1
                hitField = place
                fieldsSinceHit = 0
                if (hits >= HITS) {
                    film = place
                    lastPairComb = comb
                }
            }
        } else if (place == film) {
            val rise = (comb - lastPairComb) * 100 / (lastPairComb + 100)
            if (rise > RISE_PERCENT && comb > MIN_COMB && moving) {
                film = -1
                hits = 0
                hitField = -1
            } else {
                lastPairComb = comb
            }
        }
        lastDecrease = decrease
        lastComb = comb
    }

    companion object {
        /** A field's comb factor under this share of the one before it, in percent, is a drop: DScaler's 40. */
        const val DROP_PERCENT = 40L

        /** A paired field's comb factor this much over the last paired field's, in percent, ends film: DScaler's 10. */
        const val RISE_PERCENT = 10L

        /** Drops in a row on the same field of the frame that make film: DScaler's 3. */
        const val HITS = 3

        /** The field difference that counts as a moving picture: DScaler's movement threshold, 100. */
        private const val MOVEMENT = 100L

        /** The comb factor a drop has to come from, and a bad pair has to reach: DScaler's 150. */
        private const val MIN_COMB = 150L

        /** How far a field's pixel may stick out between the other field's lines before it counts: DScaler's 73. */
        private const val JAGGIE = 73

        /** The squared differences of [fieldDiff] are shifted down by this much, which drops noise: DScaler's 12. */
        private const val DIFF_SHIFT = 12

        /** Columns at either side and field lines at top and bottom left out, where signals carry no picture. */
        private const val MARGIN = 16

        /** The frame intervals 2:2 pulldown is looked for at: 25 frames a second, and 24 — slower than 27.8. */
        fun appliesTo(frameIntervalNs: Long): Boolean = frameIntervalNs > 36_000_000L

        /**
         * The comb factor of the frame woven from [first]'s rows of one field and [second]'s of the other — the top
         * rows from [first] when [topRowsFromFirst], else the bottom rows: for each pixel of a bottom row, whether it
         * sticks out from the top rows above and below it beyond what their own difference explains, counted per
         * line; a line counts only where the lines either side comb too, and then no more than the fewest of the
         * three, so that one noisy line is not taken for combing.
         */
        fun combFactor(first: Deinterlacer.Plane, second: Deinterlacer.Plane, topRowsFromFirst: Boolean): Long {
            val top = if (topRowsFromFirst) first else second
            val bottom = if (topRowsFromFirst) second else first
            val w = first.width
            val fieldHeight = first.height / 2
            if (fieldHeight <= 2 * MARGIN + 2 || w <= 2 * MARGIN) return 0L
            val lines = IntArray(fieldHeight)
            for (line in MARGIN until fieldHeight - MARGIN) {
                val above = 2 * line * w
                val middle = above + w
                val below = middle + w
                var count = 0
                for (x in MARGIN until w - MARGIN) {
                    val o1 = top.pixels[above + x] shr 1
                    val e = bottom.pixels[middle + x] shr 1
                    val o2 = top.pixels[below + x] shr 1
                    val jag = (o1 - e) * (o2 - e) - (((o1 - o2) * (o1 - o2)) shr 7)
                    if (jag > JAGGIE) count++
                }
                lines[line] = count
            }
            var total = 0L
            for (line in MARGIN + 1 until fieldHeight - MARGIN - 1) {
                val up = lines[line - 1]
                val down = lines[line + 1]
                if (up > 0 && down > 0) total += minOf(up, lines[line], down)
            }
            return total
        }

        /** How much the rows of parity [rows] (0 the even, from the top) moved from [before] to [after]. */
        fun fieldDiff(after: Deinterlacer.Plane, before: Deinterlacer.Plane, rows: Int): Long {
            val w = after.width
            val fieldHeight = after.height / 2
            var sum = 0L
            for (line in MARGIN until fieldHeight - MARGIN) {
                val row = (2 * line + rows) * w
                for (x in MARGIN until w - MARGIN) {
                    val d = after.pixels[row + x] - before.pixels[row + x]
                    sum += (d * d) shr DIFF_SHIFT
                }
            }
            return sum
        }

        /**
         * The film frame [pairing] makes of [cur]: [cur] itself, or its first field (by [topFieldFirst]) with the
         * second field of [prev]. Video is [cur] as it is too: it is for a deinterlacer.
         */
        fun weave(
            pairing: Pairing,
            prev: Deinterlacer.Plane,
            cur: Deinterlacer.Plane,
            topFieldFirst: Boolean,
        ): Deinterlacer.Plane {
            if (pairing != Pairing.ACROSS) return cur
            val out = cur.pixels.copyOf()
            for (y in acrossRows(topFieldFirst) until cur.height step 2) {
                System.arraycopy(prev.pixels, y * cur.width, out, y * cur.width, cur.width)
            }
            return Deinterlacer.Plane(cur.width, cur.height, out)
        }

        /** The rows of [cur] [Pairing.ACROSS] takes from the frame before: 0 the even rows, 1 the odd, from the top. */
        fun acrossRows(topFieldFirst: Boolean): Int = if (topFieldFirst) 1 else 0
    }
}
