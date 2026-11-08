package com.hawkynt.mediadroyd.player

/**
 * Film's decision for each frame, as both stages take it (PictureAdjustment.Deinterlace.FILM): at NTSC's rate 3:2
 * pulldown undone — the fields matched ([Telecine.FieldMatch]) and the repeated frame of every five dropped
 * ([Telecine.Decimate], mixed: video with nothing repeated keeps all five); at PAL's (see [Pulldown22.appliesTo]) 2:2
 * pulldown found ([Pulldown22]) — film woven whole, anything else for the temporal deinterlacer at once, nothing
 * dropped. Either way a [Verdict.cycle] closes every [Telecine.CYCLE] frames, so the stages show their frames in
 * cycles of five whatever the rate.
 */
class FilmCadence {

    /**
     * How one frame is shown: woven by [match] (the other field from the previous or next frame, of the parity
     * [field] keeps), or [combed] — for a temporal deinterlacer; at the end of a cycle, which frames of it to keep.
     */
    data class Verdict(
        val match: Telecine.Match,
        val combed: Boolean,
        /** The field kept, as [Telecine.FieldMatch.field]: 1 the top one, 0 the bottom one. */
        val field: Int,
        val cycle: Telecine.Decimate.Cycle?,
    )

    private var fieldMatch = Telecine.FieldMatch()
    private var decimate = newDecimate()
    private val pulldown = Pulldown22()

    /** Starts afresh, for a seek or a new video: the next frame opens a cycle. */
    fun reset() {
        fieldMatch = Telecine.FieldMatch(fieldMatch.field == 1)
        decimate = newDecimate()
        pulldown.reset()
    }

    /**
     * The verdict on [cur] — luma planes, every frame's the same size — between [prev] (null where it has none) and
     * [next], its fields in the order [topFieldFirst] says, at frames [frameIntervalNs] apart.
     */
    fun add(
        prev: Deinterlacer.Plane?,
        cur: Deinterlacer.Plane,
        next: Deinterlacer.Plane,
        topFieldFirst: Boolean,
        frameIntervalNs: Long,
    ): Verdict {
        if (fieldMatch.field != (if (topFieldFirst) 1 else 0)) fieldMatch = Telecine.FieldMatch(topFieldFirst)
        val field = fieldMatch.field
        if (Pulldown22.appliesTo(frameIntervalNs)) {
            val pairing = pulldown.add(prev, cur, topFieldFirst)
            // The decimator is still fed, so its cycles keep their place; at this rate none drops a frame.
            val cycle = decimate.add(Pulldown22.weave(pairing, prev ?: cur, cur, topFieldFirst))?.copy(drop = -1)
            return when (pairing) {
                Pulldown22.Pairing.VIDEO -> Verdict(Telecine.Match.CURRENT, combed = true, field, cycle)
                Pulldown22.Pairing.IN_FRAME -> Verdict(Telecine.Match.CURRENT, combed = false, field, cycle)
                // The kept field with the previous frame's other one: FieldMatch's PREVIOUS.
                Pulldown22.Pairing.ACROSS -> Verdict(Telecine.Match.PREVIOUS, combed = false, field, cycle)
            }
        }
        pulldown.reset()
        val (matched, woven) = fieldMatch.add(prev ?: cur, cur, next)
        return Verdict(matched.match, matched.combed, field, decimate.add(woven))
    }

    companion object {
        /**
         * How unlike its predecessor a frame may be and still count as the repeated one, in FFmpeg's terms (dupthresh,
         * its default 1.1): the luma read back from the graphics chip carries the colour conversion's rounding, so a
         * repeated MPEG-2 frame measured about 1.7 % on Android, real motion over 25 %.
         */
        const val DUPLICATE_PERCENT = 3.0

        private fun newDecimate() = Telecine.Decimate(mixed = true, duplicatePercent = DUPLICATE_PERCENT)
    }
}
