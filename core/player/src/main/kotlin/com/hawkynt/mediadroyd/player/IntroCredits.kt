package com.hawkynt.mediadroyd.player

import com.hawkynt.mediadroyd.nfo.TimeRange

/**
 * Intros and credits found by their sound, for episodes without chapters or an edit decision list marking them (#480):
 * an episode's intro is the stretch of its opening it shares with a neighbouring episode — the theme, wherever a cold
 * open puts it — and its credits begin where the closing music it shares with one begins. Both from Chromaprint
 * fingerprints (NativeFingerprint) matched by [PrintMatcher]; the way Jellyfin's Intro Skipper finds them (GPL, read
 * for the behaviour, no code taken).
 */
object IntroCredits {
    /** A fingerprint of one stretch of a video's sound: its [items], beginning [startMs] into the video. */
    class SoundPrint(val items: IntArray, val startMs: Long)

    /** How much of an episode's opening is fingerprinted to look for its intro. */
    const val OPENING_MS = 10 * 60_000L

    /** How much before an episode's end is fingerprinted to look for its credits. */
    const val CLOSING_MS = 8 * 60_000L

    /** The shortest an intro is taken to be: a channel's jingle or a shared sting is none. */
    const val MIN_INTRO_MS = 15_000L

    /** The longest: beyond this, a shared stretch is more than an intro (a recap, a repeated scene). */
    const val MAX_INTRO_MS = 150_000L

    /** The shortest stretch of closing music taken for the credits. */
    const val MIN_CREDITS_MS = 15_000L

    /**
     * How far inside a found edge the skip begins or ends: the matcher places an edge only to about a second (an item
     * reads 0.37 s of sound, the smoothing blurs, sound alike for a moment just before extends a match), so the intro
     * is shrunk by this at both ends and the credits begin this much later — a second of the theme or the credits
     * shows rather than a second of the story going missing.
     */
    const val MARGIN_MS = 1000L

    /**
     * How far ahead of the sound its items run: Chromaprint makes item k from the image rows k to k + 15 (its
     * classifiers read up to 16 frames from there, after a chroma filter of five), so a stretch shows in the items
     * about a second before it is heard and every edge found is that much early — 0.6 to 1.1 s on generated episodes
     * (SoundPrintsTest). Item times are moved this much later.
     */
    const val LOOKAHEAD_MS = 1000L

    private fun ms(items: Int) = (items * NativeFingerprint.ITEM_SECONDS * 1000).toLong() + LOOKAHEAD_MS

    private fun inside(first: Int, length: Int) = TimeRange(ms(first) + MARGIN_MS, ms(first + length) - MARGIN_MS)

    /**
     * The intro the openings [own] and [other] (prints from each episode's start) share: its span in each, from each
     * one's start, [MARGIN_MS] inside at both ends; null where they share no stretch of an intro's length.
     */
    fun intro(own: IntArray, other: IntArray): Pair<TimeRange, TimeRange>? {
        val piece = PrintMatcher.shared(own, other)
            .filter { ms(it.length) in MIN_INTRO_MS..MAX_INTRO_MS }
            .maxByOrNull { it.length } ?: return null
        return inside(piece.first1, piece.length) to inside(piece.first2, piece.length)
    }

    /** [own]'s intro by the longest any of [neighbours]' openings shares with it; null where none does. */
    fun introAmong(own: IntArray, neighbours: List<IntArray>): TimeRange? = neighbours
        .mapNotNull { intro(own, it)?.first }
        .maxByOrNull { it.endMs - it.startMs }

    /**
     * Where the credits begin in each episode, from the closings [own] and [other] (prints of each one's end,
     * starting [ownStartMs] and [otherStartMs] into the episode): the start of the longest closing music they share,
     * [MARGIN_MS] later; null where they share none long enough.
     */
    fun credits(own: IntArray, ownStartMs: Long, other: IntArray, otherStartMs: Long): Pair<Long, Long>? {
        val piece = PrintMatcher.shared(own, other)
            .filter { ms(it.length) >= MIN_CREDITS_MS }
            .maxByOrNull { it.length } ?: return null
        return ownStartMs + ms(piece.first1) + MARGIN_MS to otherStartMs + ms(piece.first2) + MARGIN_MS
    }
}
