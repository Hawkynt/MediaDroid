package com.hawkynt.mediadroid.library

import com.hawkynt.mediadroid.nfo.TimeRange

/**
 * What binge watching skips: the intro at the start and the credits at the end.
 * Matroska chapters are the authoritative source — they are what the file
 * carries — and the edit decision list beside the video (see
 * [com.hawkynt.mediadroid.nfo.EditDecisionList]) fills in where a file has no
 * chapter atoms. Titles are matched in the languages real releases use; a
 * chapter named "Intro" in an English file and "Vorspann" in a German one mean
 * the same thing.
 */
object SkipRules {

    private val INTRO_TITLE = Regex("(?i)^(intro|opening|opener|vorspann|title sequence|leader)")
    private val CREDITS_TITLE = Regex("(?i)^(credits|end credits|closing|outro|abspann|ending|schlusstitel)")

    /** Millisecond to seek to when starting playback; null means no intro known. */
    fun introEndMs(chapters: List<Chapter>, intro: TimeRange?): Long? =
        chapters.firstOrNull { INTRO_TITLE.containsMatchIn(it.title) && it.endMs > 0 }?.endMs
            ?: intro?.endMs

    /** Where the intro runs, start to end in milliseconds; null means no intro known. */
    fun introSpanMs(chapters: List<Chapter>, intro: TimeRange?): LongRange? =
        chapters.firstOrNull { INTRO_TITLE.containsMatchIn(it.title) && it.endMs > 0 }?.let { it.startMs..it.endMs }
            ?: intro?.let { it.startMs..it.endMs }

    /**
     * Where to jump when playback is at [positionMs]: the intro's end while inside the intro, else
     * null. A cold open before the intro plays; the last second is left alone so a seek landing just
     * short of the end cannot loop.
     */
    fun skipTarget(positionMs: Long, intro: LongRange?): Long? {
        if (intro == null) return null
        return if (positionMs >= intro.first && positionMs < intro.last - 1000) intro.last else null
    }

    /** Millisecond at which the credits begin; null means no credits known. */
    fun creditsStartMs(chapters: List<Chapter>, credits: TimeRange?): Long? =
        chapters.firstOrNull { CREDITS_TITLE.containsMatchIn(it.title) }?.startMs
            ?: credits?.startMs
}
