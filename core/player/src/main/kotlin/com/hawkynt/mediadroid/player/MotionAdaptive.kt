package com.hawkynt.mediadroid.player

/**
 * Motion-adaptive deinterlacing (PictureAdjustment.Deinterlace.ADAPTIVE): the frame woven whole while the picture
 * stands still — every line as it was taken, nothing guessed — and a temporal deinterlacer as soon as it moves.
 *
 * DScaler's Adaptive method (`Plugins/DI_Adaptive/DI_Adaptive.c` and its help; GPL-2.0, behaviour followed and written
 * anew, no code taken — see `THIRD_PARTY_NOTICES.md`): a field differing from the one two before it by less than
 * [STILL_DIFF] ([Pulldown22.fieldDiff], DScaler's field difference) is still; after [STILL_FIELDS] still fields in a
 * row the picture is woven, and the first field that moves ends that at once. DScaler's low- and high-motion modes
 * are one here, the temporal deinterlacer.
 */
class MotionAdaptive {
    private var stillFields = 0

    /** Whether the frame last added is to be woven. */
    var weave = false
        private set

    /** Starts afresh, for a seek or a new video: the picture is taken to move until it is seen to stand. */
    fun reset() {
        stillFields = 0
        weave = false
    }

    /**
     * Takes the luma of the next frame [cur] after [prev] (null where it has none) — every [columnStep]th column of
     * the picture, which the field difference is scaled up by — and returns whether [cur] is to be woven.
     */
    fun add(prev: Deinterlacer.Plane?, cur: Deinterlacer.Plane, columnStep: Int = 1): Boolean {
        if (prev == null || prev.width != cur.width || prev.height != cur.height) {
            reset()
            return false
        }
        for (rows in 0..1) {
            val diff = Pulldown22.fieldDiff(cur, prev, rows) * columnStep
            stillFields = if (diff < STILL_DIFF) minOf(stillFields + 1, STILL_FIELDS) else 0
        }
        weave = stillFields >= STILL_FIELDS
        return weave
    }

    companion object {
        /** A field difference under this is a still field: DScaler's 15. */
        const val STILL_DIFF = 15L

        /** Still fields in a row before the picture is woven: DScaler's 100. */
        const val STILL_FIELDS = 100
    }
}
