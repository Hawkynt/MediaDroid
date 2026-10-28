package com.hawkynt.mediadroid.library

/**
 * One title, several files: the same film found more than once (the same title and year) becomes one
 * [Movie] carrying all of them as [Movie.versions], the best picture first and in front.
 */
object MovieVersions {

    /** Best picture first: resolution, then HDR, then the bigger file; the path keeps it stable. */
    private val bestFirst: Comparator<Movie> = compareByDescending<Movie> { it.quality.pictureRank }
        .thenByDescending { it.sizeBytes ?: 0L }
        .thenBy { it.videoPath }

    /** [movies] with every film found more than once folded into one card. */
    fun merge(movies: List<Movie>): List<Movie> =
        movies.groupBy { it.title.lowercase() to it.year }.values.map { group ->
            if (group.size == 1) return@map group[0]
            val ordered = group.sortedWith(bestFirst)
            // What one copy's NFO or art lacks, another may have.
            ordered.first().copy(
                plot = ordered.firstNotNullOfOrNull { it.plot },
                posterPath = ordered.firstNotNullOfOrNull { it.posterPath },
                fanartPath = ordered.firstNotNullOfOrNull { it.fanartPath },
                minAge = ordered.firstNotNullOfOrNull { it.minAge },
                genres = ordered.firstOrNull { it.genres.isNotEmpty() }?.genres ?: emptyList(),
                runtimeMinutes = ordered.firstNotNullOfOrNull { it.runtimeMinutes },
                rating = ordered.firstNotNullOfOrNull { it.rating },
                trailerPath = ordered.firstNotNullOfOrNull { it.trailerPath },
                versions = ordered,
            )
        }

    /** The version Play starts: the one with a resume point ([resumed] says which videos have one), else the best. */
    fun toPlay(movie: Movie, resumed: (String) -> Boolean): Movie =
        movie.versions.firstOrNull { version -> version.parts.any(resumed) } ?: movie.versions.firstOrNull() ?: movie
}
