package com.hawkynt.mediadroid.player

import com.hawkynt.mediadroid.library.Episode
import com.hawkynt.mediadroid.library.Season

/** A queued episode plus where it sits, so the UI can show "S03 · E07 of 56". */
data class QueuedEpisode(val season: Int, val episode: Episode, val position: Int, val total: Int)

/**
 * The binge logic: what plays next, and what plays after that. Episodes run in
 * season-then-episode order; watched ones are skipped, so "continue" after any
 * interruption resumes at the first thing not yet seen.
 */
object BingeQueue {

    fun queue(seasons: List<Season>, skipWatched: Boolean = true): List<QueuedEpisode> {
        val ordered = seasons.sortedBy { it.number }
            .flatMap { season -> season.episodes.sortedBy { it.number }.map { season.number to it } }
            .filter { (_, episode) -> !skipWatched || !episode.watched }
        val total = ordered.size
        return ordered.mapIndexed { index, (seasonNumber, episode) ->
            QueuedEpisode(season = seasonNumber, episode = episode, position = index + 1, total = total)
        }
    }

    fun next(seasons: List<Season>): QueuedEpisode? = queue(seasons).firstOrNull()
}
