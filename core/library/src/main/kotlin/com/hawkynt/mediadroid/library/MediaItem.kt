package com.hawkynt.mediadroid.library

import com.hawkynt.mediadroid.nfo.TimeRange
import kotlinx.serialization.Serializable

/** One playable entry in the library — the folder-level view of a movie or a show. */
sealed interface MediaItem {
    val title: String
    val year: Int?
    val genres: List<String>
    val path: String
    val posterPath: String?

    /** Minimum viewer age derived from the NFO certification; null when unknown. */
    val minAge: Int?

    /** The backdrop (`fanart.jpg`) a hero banner or details screen paints behind the title. */
    val fanartPath: String?

    /**
     * Unique within one [Library]. [path] is not: loose files in one folder all share it, and
     * several shows can be grouped out of one dump folder.
     */
    val key: String
}

@Serializable
data class Movie(
    override val title: String,
    override val year: Int? = null,
    override val genres: List<String> = emptyList(),
    override val path: String,
    override val posterPath: String? = null,
    override val minAge: Int? = null,
    override val fanartPath: String? = null,
    val videoPath: String,
    val plot: String? = null,
    val runtimeMinutes: Int? = null,
    val cast: List<String> = emptyList(),
    val playcount: Int = 0,
    val intro: TimeRange? = null,
    val credits: TimeRange? = null,
    /** Every file of a multi-part movie in playing order; just [videoPath] for a single file. */
    val parts: List<String> = listOf(videoPath),
    val audioLanguages: List<String> = emptyList(),
    val subtitleLanguages: List<String> = emptyList(),
    /** Resolution, HDR, video and audio format for the badges; see [Quality]. */
    val quality: Quality = Quality.NONE,
    /** The NFO's rating on a 0–10 scale; null when unrated. */
    val rating: Double? = null,
    /** When it arrived: the NFO's `<dateadded>`, else the video file's time, as epoch milliseconds. */
    val addedMs: Long? = null,
    val directors: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    /** The release date as the NFO writes it, `2017-10-06`. */
    val premiered: String? = null,
    /** The `<name>-trailer.*` beside the film, the Kodi convention; never a title of its own. */
    val trailerPath: String? = null,
    /** All its files together, in bytes; null when unknown. */
    val sizeBytes: Long? = null,
    /**
     * Every file of the same film — 1080p and 4K, two cuts — best picture first, this one among them;
     * empty when the film has only the one. See [MovieVersions].
     */
    val versions: List<Movie> = emptyList(),
) : MediaItem {
    override val key: String get() = "movie:$videoPath"

    /** Every video of every version, so a resume point or a watched mark on any of them is found. */
    val allParts: List<String> get() = versions.ifEmpty { listOf(this) }.flatMap { it.parts }
}

@Serializable
data class Show(
    override val title: String,
    override val year: Int? = null,
    override val genres: List<String> = emptyList(),
    override val path: String,
    override val posterPath: String? = null,
    override val minAge: Int? = null,
    override val fanartPath: String? = null,
    val plot: String? = null,
    val seasons: List<Season> = emptyList(),
    /** The NFO's rating on a 0–10 scale; null when unrated. */
    val rating: Double? = null,
    val studios: List<String> = emptyList(),
    /** The first air date as the NFO writes it, `2017-12-01`. */
    val premiered: String? = null,
) : MediaItem {
    override val key: String get() = "show:$path:${title.lowercase()}"

    private val episodeTimes: List<Long> get() = seasons.flatMap { s -> s.episodes.mapNotNull { it.addedMs } }

    /** When the show arrived: its first episode's time. */
    val addedMs: Long? get() = episodeTimes.minOrNull()

    /** When it last grew: its newest episode's time. */
    val updatedMs: Long? get() = episodeTimes.maxOrNull()

    /**
     * The video whose frame stands in for missing art: the first episode of the first regular season,
     * the specials (season 0) only when there is nothing else.
     */
    val representativeVideo: String?
        get() = (seasons.filter { it.number > 0 }.minByOrNull { it.number } ?: seasons.minByOrNull { it.number })
            ?.representativeVideo
}

@Serializable
data class Season(
    val number: Int,
    val episodes: List<Episode> = emptyList(),
    /** The season's own cover: `folder.jpg` in its folder, or `seasonNN-poster.jpg` beside the show. */
    val posterPath: String? = null,
    /** What the season is about, from its `season.nfo`; null when it has none. */
    val plot: String? = null,
) {
    private val firstEpisode: Episode? get() = episodes.minByOrNull { it.number }

    /** The first episode's video, whose frame stands in for a missing season cover. */
    val representativeVideo: String? get() = firstEpisode?.videoPath?.ifEmpty { null }

    /** The first episode's scraped thumb, the next best thing to a season cover. */
    val representativeThumb: String? get() = firstEpisode?.thumbPath
}

@Serializable
data class Episode(
    val number: Int,
    val title: String? = null,
    val firstAired: String? = null,
    /** What the episode is about, from its NFO (its plot, else its outline). */
    val plot: String? = null,
    val watched: Boolean = false,
    val videoPath: String = "",
    val thumbPath: String? = null,
    val intro: TimeRange? = null,
    val credits: TimeRange? = null,
    val audioLanguages: List<String> = emptyList(),
    val subtitleLanguages: List<String> = emptyList(),
    /** Resolution, HDR, video and audio format for the badges; see [Quality]. */
    val quality: Quality = Quality.NONE,
    /** When it arrived: the NFO's `<dateadded>`, else the video file's time, as epoch milliseconds. */
    val addedMs: Long? = null,
)

/** The two halves of the library the sidebar switches between. */
enum class LibrarySection { MOVIES, SHOWS }

/** Everything one scan of one library root produced — or of several, [merge]d. */
@Serializable
data class Library(val movies: List<Movie>, val shows: List<Show>) {
    val items: List<MediaItem> get() = movies + shows

    /** Only the items of one [section], in scan order. */
    fun itemsIn(section: LibrarySection): List<MediaItem> = when (section) {
        LibrarySection.MOVIES -> movies
        LibrarySection.SHOWS -> shows
    }

    companion object {
        /**
         * The libraries of several roots as one: a film found on more than one root is one card with
         * every copy (see [MovieVersions]), a show split over roots one show with all its seasons.
         */
        fun merge(libraries: List<Library>): Library = Library(
            movies = MovieVersions.merge(libraries.flatMap { it.movies }.flatMap { it.versions.ifEmpty { listOf(it) } })
                .sortedBy { it.title.lowercase() },
            shows = LibraryScanner.mergeShowsByTitle(libraries.flatMap { it.shows }),
        )
    }
}
