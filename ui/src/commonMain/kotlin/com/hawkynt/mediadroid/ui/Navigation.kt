package com.hawkynt.mediadroid.ui

import com.hawkynt.mediadroid.library.HomeRow
import com.hawkynt.mediadroid.library.LibrarySection
import com.hawkynt.mediadroid.library.MediaItem
import com.hawkynt.mediadroid.library.Movie
import com.hawkynt.mediadroid.library.Show
import com.hawkynt.mediadroid.state.StateStore

/** One thing the player can play, with enough context to label itself and skip its intro. */
data class Playable(
    val title: String,
    val subtitle: String?,
    val videoPath: String,
    val posterPath: String?,
    val intro: com.hawkynt.mediadroid.nfo.TimeRange? = null,
    val credits: com.hawkynt.mediadroid.nfo.TimeRange? = null,
    /** What the NFO says: watched unless the household unticked it. */
    val nfoWatched: Boolean = false,
    /** What it is about, from the NFO or the file's own tags; null when nothing says. */
    val plot: String? = null,
    /** The NFO's facts beyond the plot: year, length, genres, director, cast. */
    val facts: List<String> = emptyList(),
)

/** Where the app is: a wall, a place, a tool, a title's details, or the player. */
sealed interface Screen {
    /** A wall; with a [row], the full wall of one home row ("See all") instead of the home. */
    data class Browse(val section: LibrarySection, val row: HomeRow? = null) : Screen
    data class Places(val kind: PlaceKind) : Screen
    /** The Files browser at [dir]; null lists the storage volumes. */
    data class Files(val dir: String? = null) : Screen
    data object Search : Screen
    data object Filters : Screen
    data object Settings : Screen
    data class Details(val item: MediaItem) : Screen
    /** The player; leaving it goes to [returnTo], else to the wall last browsed. */
    data class Player(
        val queue: List<Playable>,
        val startIndex: Int,
        val autoAdvance: Boolean,
        val returnTo: Screen? = null,
    ) : Screen
}

/** The movie's files in order; a multi-part movie labels them `Part 1 of 2`. */
fun Movie.playQueue(): List<Playable> = parts.mapIndexed { i, part ->
    Playable(
        title = title,
        subtitle = if (parts.size > 1) "Part ${i + 1} of ${parts.size}" else year?.toString(),
        videoPath = part,
        posterPath = posterPath,
        intro = intro.takeIf { i == 0 },
        credits = credits.takeIf { i == parts.lastIndex },
        nfoWatched = playcount > 0,
        plot = plot,
        facts = aboutOf(this).let { listOfNotNull(it.line) + it.facts },
    )
}

/** All episodes in binge order, labelled `S04 · E09 · Title`. */
fun Show.playQueue(): List<Playable> =
    seasons.sortedBy { it.number }.flatMap { season ->
        season.episodes.sortedBy { it.number }.map { episode ->
            Playable(
                title = episode.title ?: "Episode ${episode.number}",
                subtitle = "S%02d · E%02d".format(season.number, episode.number),
                videoPath = episode.videoPath,
                posterPath = episode.thumbPath ?: posterPath,
                intro = episode.intro,
                credits = episode.credits,
                nfoWatched = episode.watched,
                plot = episode.plot,
                facts = listOf(title),
            )
        }
    }

/**
 * Where a queue picks up: the file asked for, else one with a resume point, else the first not
 * yet watched — so Play on a show continues it instead of starting at the pilot again.
 */
fun startOf(queue: List<Playable>, path: String?, store: StateStore): Int =
    path?.let { p -> queue.indexOfFirst { it.videoPath == p } }?.takeIf { it >= 0 }
        ?: queue.indexOfFirst { store.resumeFor(it.videoPath) != null }.takeIf { it >= 0 }
        ?: queue.indexOfFirst { !store.isWatched(it.videoPath, it.nfoWatched) }.takeIf { it >= 0 }
        ?: 0
