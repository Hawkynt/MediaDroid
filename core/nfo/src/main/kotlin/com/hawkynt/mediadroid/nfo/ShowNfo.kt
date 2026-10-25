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
    val studios: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val imdbId: String? = null,
    val tmdbId: String? = null,
)

/** Reads a `tvshow.nfo`; like movies, only the title is required. */
fun parseShowNfo(xml: String): ShowInfo {
    val root = parseNfoXml(xml).rootElement()

    val title = root.childText("title") ?: throw NfoFormatException("tvshow NFO has no <title>.")

    return ShowInfo(
        title = title,
        originalTitle = root.childText("originaltitle"),
        sortTitle = root.childText("sorttitle"),
        plot = root.childText("plot"),
        outline = root.childText("outline"),
        mpaa = root.childText("mpaa"),
        status = root.childText("status"),
        firstAiredYear = root.childInt("premiered") ?: root.childInt("firstaired"),
        studios = root.childElements("studio").mapNotNull { it.textContent?.trim() }.filter { it.isNotEmpty() },
        genres = root.childElements("genre").mapNotNull { it.textContent?.trim() }.filter { it.isNotEmpty() },
        tags = root.childElements("tag").mapNotNull { it.textContent?.trim() }.filter { it.isNotEmpty() },
        imdbId = root.childText("imdbid") ?: root.childText("id"),
        tmdbId = root.childText("tmdbid"),
    )
}
