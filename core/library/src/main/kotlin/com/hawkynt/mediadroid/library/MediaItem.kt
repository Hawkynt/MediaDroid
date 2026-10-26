package com.hawkynt.mediadroid.library

import com.hawkynt.mediadroid.nfo.TimeRange

/** One playable entry in the library — the folder-level view of a movie or a show. */
sealed interface MediaItem {
    val title: String
    val year: Int?
    val genres: List<String>
    val path: String
    val posterPath: String?

    /** Minimum viewer age derived from the NFO certification; null when unknown. */
    val minAge: Int?

    /**
     * Unique within one [Library]. [path] is not: loose files in one folder all share it, and
     * several shows can be grouped out of one dump folder.
     */
    val key: String
}

data class Movie(
    override val title: String,
    override val year: Int? = null,
    override val genres: List<String> = emptyList(),
    override val path: String,
    override val posterPath: String? = null,
    override val minAge: Int? = null,
    val fanartPath: String? = null,
    val videoPath: String,
    val plot: String? = null,
    val runtimeMinutes: Int? = null,
    val cast: List<String> = emptyList(),
    val playcount: Int = 0,
    val intro: TimeRange? = null,
    val credits: TimeRange? = null,
) : MediaItem {
    override val key: String get() = "movie:$videoPath"
}

data class Show(
    override val title: String,
    override val year: Int? = null,
    override val genres: List<String> = emptyList(),
    override val path: String,
    override val posterPath: String? = null,
    override val minAge: Int? = null,
    val fanartPath: String? = null,
    val plot: String? = null,
    val seasons: List<Season> = emptyList(),
) : MediaItem {
    override val key: String get() = "show:$path:${title.lowercase()}"
}

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
    val intro: TimeRange? = null,
    val credits: TimeRange? = null,
)

/** Everything one scan of one library root produced. */
data class Library(val movies: List<Movie>, val shows: List<Show>) {
    val items: List<MediaItem> get() = movies + shows
}
