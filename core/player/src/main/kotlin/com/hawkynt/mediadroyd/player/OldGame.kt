package com.hawkynt.mediadroyd.player

/**
 * Old-console mode (PictureAdjustment.Deinterlace.OLD_CONSOLE): for 240p game consoles and other sources that send
 * half-height progressive pictures — every field a whole picture of its own, which a capture card weaves into frames
 * as if they were interlaced. Deinterlacing such frames rebuilds lines that were never missing: bob shows doubled
 * blocks, YADIF flickers on thin horizontal detail. Here each field is taken as the picture it is and stretched back to
 * the full height smoothly, every line from the two field lines nearest it; a field that has hardly moved from the one
 * before is averaged with it, which evens out noise and a composite cable's colour crosstalk.
 *
 * DScaler's Old Game method (`Plugins/DI_OldGame`, its help's "Old Game"; GPL-2.0, behaviour followed and written anew,
 * no code taken — see `THIRD_PARTY_NOTICES.md`): the newest field shown on its own, averaged with the field before
 * while their comb factor ([Pulldown22.combFactor]) stays at most [MAX_COMB]; the stretch is DScaler's overlay
 * scaling, here bilinear, as FFmpeg's scale filter draws it.
 */
object OldGame {

    /** The comb factor up to which a field is averaged with the one before: DScaler's maximum motion, 300. */
    const val MAX_COMB = 300L

    /**
     * Whether the newest field — [cur]'s first by [topFieldFirst], or with [second] its second — is averaged with the
     * field before it, [prev]'s second or [cur]'s first; luma planes, every [columnStep]th column of the picture, the
     * comb factor scaled up by it. Never without a [prev] for the first field.
     */
    fun averages(
        prev: Deinterlacer.Plane?,
        cur: Deinterlacer.Plane,
        second: Boolean,
        topFieldFirst: Boolean,
        columnStep: Int = 1,
    ): Boolean {
        val comb = if (second) {
            Pulldown22.combFactor(cur, cur, topRowsFromFirst = true)
        } else {
            if (prev == null || prev.width != cur.width || prev.height != cur.height) return false
            Pulldown22.combFactor(cur, prev, topRowsFromFirst = topFieldFirst)
        }
        return comb * columnStep <= MAX_COMB
    }

    /**
     * The picture of the newest field (see [averages]) stretched to [cur]'s height: picture row y from field lines
     * y / 2 − ¼ — three quarters of the nearer line, a quarter of the next, rounded — each line [average]d with the
     * field before's same line where [averaged].
     */
    fun picture(
        prev: Deinterlacer.Plane,
        cur: Deinterlacer.Plane,
        second: Boolean,
        topFieldFirst: Boolean,
        averaged: Boolean,
    ): Deinterlacer.Plane {
        val w = cur.width
        val kept = Deinterlacer.rebuiltParity(second, topFieldFirst)
        val before = if (second) cur else prev
        val lines = cur.height / 2
        fun line(x: Int, i: Int): Int {
            val row = 2 * i.coerceIn(0, lines - 1)
            val newest = cur[x, row + kept]
            return if (averaged) average(newest, before[x, row + 1 - kept]) else newest
        }
        val out = IntArray(w * cur.height)
        for (y in 0 until cur.height) {
            val near = y / 2
            val far = if (y % 2 == 0) near - 1 else near + 1
            for (x in 0 until w) out[y * w + x] = (3 * line(x, near) + line(x, far) + 2) shr 2
        }
        return Deinterlacer.Plane(w, cur.height, out)
    }

    /** The newest field's value averaged with the one before's: its lowest bit dropped, then rounded up, as DScaler. */
    fun average(newest: Int, before: Int): Int = ((newest and 0xFE) + before + 1) shr 1
}
