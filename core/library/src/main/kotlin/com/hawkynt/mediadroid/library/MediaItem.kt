package com.hawkynt.mediadroid.library

/** One playable entry in the library — the folder-level view of a movie or a show. */
sealed interface MediaItem {
    val title: String
    val year: Int?
    val genres: List<String>
    val path: String
}

data class Movie(
    override val title: String,
    override val year: Int? = null,
    override val genres: List<String> = emptyList(),
    override val path: String,
    val plot: String? = null,
    val posterPath: String? = null,
    val runtimeMinutes: Int? = null,
) : MediaItem

data class Show(
    override val title: String,
    override val year: Int? = null,
    override val genres: List<String> = emptyList(),
    override val path: String,
    val plot: String? = null,
    val posterPath: String? = null,
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
)
