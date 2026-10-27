package com.hawkynt.mediadroid.library

/**
 * What has been watched: the household's own marks on top of what the NFOs say. A mark made in the
 * app wins both ways — ticking an episode off makes it watched, and unticking one makes it unwatched
 * even when its NFO carries a playcount. Without a mark the NFO decides.
 *
 * [watched] and [unwatched] are the stored mark lists (video paths); they never both hold a path.
 */
class WatchMarks(
    private val watched: MutableList<String>,
    private val unwatched: MutableList<String>,
) {

    /** Whether the video at [path] counts as watched; [nfoWatched] is what its NFO says. */
    fun isWatched(path: String, nfoWatched: Boolean = false): Boolean =
        path in watched || (nfoWatched && path !in unwatched)

    /** A season is watched once every one of its episodes is. */
    fun isWatched(season: Season): Boolean =
        season.episodes.isNotEmpty() && season.episodes.all { isWatched(it.videoPath, it.watched) }

    /** A show is watched once every one of its episodes is. */
    fun isWatched(show: Show): Boolean =
        show.seasons.any { it.episodes.isNotEmpty() } && show.seasons.all { it.episodes.isEmpty() || isWatched(it) }

    /** A movie is watched once every one of its parts is. */
    fun isWatched(movie: Movie): Boolean = movie.parts.all { isWatched(it, movie.playcount > 0) }

    /** Marks every video in [paths] as [watched] or unwatched. */
    fun set(paths: Collection<String>, watched: Boolean) {
        for (path in paths) {
            if (watched) {
                unwatched.remove(path)
                if (path !in this.watched) this.watched.add(path)
            } else {
                this.watched.remove(path)
                if (path !in unwatched) unwatched.add(path)
            }
        }
    }
}
