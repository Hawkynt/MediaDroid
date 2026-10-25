package com.hawkynt.mediadroid.library

import com.hawkynt.mediadroid.nfo.TimeRange

/**
 * What binge watching skips: the intro at the start and the credits at the end.
 * Matroska chapters are the authoritative source — they are what the file
 * carries — and the NFO sidecar spans (our extension, see [TimeRange]) fill in
 * where a file has no chapter atoms. Titles are matched in the languages real
 * releases use; a chapter named "Intro" in an English file and "Vorspann" in a
 * German one mean the same thing.
 */
object SkipRules {

    private val INTRO_TITLE = Regex("(?i)^(intro|opening|opener|vorspann|title sequence|leader)")
    private val CREDITS_TITLE = Regex("(?i)^(credits|end credits|closing|outro|abspann|ending|schlusstitel)")

    /** Millisecond to seek to when starting playback; null means no intro known. */
    fun introEndMs(chapters: List<Chapter>, intro: TimeRange?): Long? =
        chapters.firstOrNull { INTRO_TITLE.containsMatchIn(it.title) && it.endMs > 0 }?.endMs
            ?: intro?.let { it.endSec * 1000L }

    /** Millisecond at which the credits begin; null means no credits known. */
    fun creditsStartMs(chapters: List<Chapter>, credits: TimeRange?): Long? =
        chapters.firstOrNull { CREDITS_TITLE.containsMatchIn(it.title) }?.startMs
            ?: credits?.let { it.startSec * 1000L }
}
