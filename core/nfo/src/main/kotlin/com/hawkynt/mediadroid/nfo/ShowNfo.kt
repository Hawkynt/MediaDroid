package com.hawkynt.mediadroid.nfo

/** A TV show as described by a `tvshow.nfo`. */
data class ShowInfo(
    val title: String,
    val originalTitle: String? = null,
    val sortTitle: String? = null,
    val plot: String? = null,
    val outline: String? = null,
    val mpaa: String? = null,
    val status: String? = null,
    val firstAiredYear: Int? = null,
    /** The first air date as the NFO writes it, `2017-12-01`. */
    val premiered: String? = null,
    val studios: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val imdbId: String? = null,
    val tmdbId: String? = null,
    /** The rating on a 0–10 scale; null when unrated. */
    val rating: Double? = null,
)

/** Reads a `tvshow.nfo`; like movies, only the title is required. */
fun parseShowNfo(xml: String): ShowInfo {
    val root = parseNfoXml(xml).rootElement()

    val title = root.childText("title") ?: throw NfoFormatException("tvshow NFO has no <title>.")
    val uniqueIds = root.uniqueIds()

    return ShowInfo(
        title = title,
        originalTitle = root.childText("originaltitle"),
        sortTitle = root.childText("sorttitle"),
        plot = root.childText("plot"),
        outline = root.childText("outline"),
        mpaa = root.childText("mpaa"),
        status = root.childText("status"),
        firstAiredYear = root.childInt("premiered") ?: root.childInt("year") ?: root.childInt("firstaired"),
        premiered = root.childText("premiered"),
        studios = root.childElements("studio").mapNotNull { it.textContent?.trim() }.filter { it.isNotEmpty() },
        genres = root.childElements("genre").mapNotNull { it.textContent?.trim() }.filter { it.isNotEmpty() },
        tags = root.childElements("tag").mapNotNull { it.textContent?.trim() }.filter { it.isNotEmpty() },
        imdbId = root.childText("imdbid") ?: uniqueIds["imdb"],
        tmdbId = root.childText("tmdbid") ?: uniqueIds["tmdb"],
        rating = root.rating(),
    )
}
