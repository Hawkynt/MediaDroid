package com.hawkynt.mediadroid.player

import com.hawkynt.mediadroid.library.Chapter

/**
 * Moving through a video's own chapters (read by [com.hawkynt.mediadroid.library.Chapters]): which one is playing,
 * where the next and the previous begin. Previous behaves as in mpv: a few seconds into a chapter it goes back to that
 * chapter's start, right after one began to the one before.
 */
object ChapterSteps {

    /** How far into a chapter Previous restarts it, not going to the one before: mpv's `chapter-seek-threshold`. */
    const val BACK_THRESHOLD_MS = 5_000L

    /** The index in [chapters] of the one playing at [positionMs], or null before the first begins. */
    fun current(chapters: List<Chapter>, positionMs: Long): Int? =
        chapters.indices
            .filter { chapters[it].startMs <= positionMs }
            .maxByOrNull { chapters[it].startMs }

    /** Where the chapter after the one playing at [positionMs] begins; null after the last. */
    fun next(chapters: List<Chapter>, positionMs: Long): Long? =
        chapters.map { it.startMs }.filter { it > positionMs }.minOrNull()

    /** Where Previous goes from [positionMs]: this chapter's start, or the one before's (see [BACK_THRESHOLD_MS]). */
    fun previous(chapters: List<Chapter>, positionMs: Long): Long? {
        val starts = chapters.map { it.startMs }.sorted()
        if (starts.isEmpty()) return null
        val begun = starts.filter { it <= positionMs }
        val own = begun.lastOrNull() ?: return starts.first()
        if (positionMs - own > BACK_THRESHOLD_MS) return own
        return begun.getOrNull(begun.size - 2) ?: own
    }

    /** What the list calls chapter [index]: its title, or "Chapter n" where the file gives none. */
    fun name(chapters: List<Chapter>, index: Int): String =
        chapters[index].title.trim().ifEmpty { "Chapter ${index + 1}" }
}
