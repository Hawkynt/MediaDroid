package com.hawkynt.mediadroid.library

/** One playable entry in the library — the folder-level view of a movie or a show. */
sealed interface MediaItem {
    val title: String
    val year: Int?
    val genres: List<String>
    val path: String
    val posterPath: String?
}

data class Movie(
    override val title: String,
    override val year: Int? = null,
    override val genres: List<String> = emptyList(),
    override val path: String,
    override val posterPath: String? = null,
    val fanartPath: String? = null,
    val videoPath: String,
    val plot: String? = null,
    val runtimeMinutes: Int? = null,
    val cast: List<String> = emptyList(),
    val playcount: Int = 0,
) : MediaItem

data class Show(
    override val title: String,
    override val year: Int? = null,
    override val genres: List<String> = emptyList(),
    override val path: String,
    override val posterPath: String? = null,
    val fanartPath: String? = null,
    val plot: String? = null,
    val seasons: List<Season> = emptyList(),
) : MediaItem

data class Season(
    val number: Int,
    val episodes: List<Episode> = emptyList(),
)

data class Episode(
    val number: Int,
    val title: String? = null,
    val firstAired: String? = null,
    val watched: Boolean = false,
    val videoPath: String = "",
    val thumbPath: String? = null,
)

/** Everything one scan of one library root produced. */
data class Library(val movies: List<Movie>, val shows: List<Show>) {
    val items: List<MediaItem> get() = movies + shows
}
