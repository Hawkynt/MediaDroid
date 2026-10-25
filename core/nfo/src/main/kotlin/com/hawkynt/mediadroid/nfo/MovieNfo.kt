package com.hawkynt.mediadroid.nfo

/** A movie as described by a `movie.nfo` written by Kodi or a compatible scraper. */
data class MovieInfo(
    val title: String,
    val originalTitle: String? = null,
    val sortTitle: String? = null,
    val plot: String? = null,
    val outline: String? = null,
    val tagline: String? = null,
    val runtimeMinutes: Int? = null,
    val year: Int? = null,
    val mpaa: String? = null,
    val studios: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val set: SetInfo? = null,
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val trailer: String? = null,
    val playcount: Int = 0,
    val intro: TimeRange? = null,
    val credits: TimeRange? = null,
)

data class SetInfo(val name: String)

data class CastMember(val name: String, val role: String? = null)

/**
 * Reads a `movie.nfo`. The title is the only field a sane library cannot do without;
 * everything else is optional and scrapers omit fields freely, so missing fields
 * simply come back as null or empty lists.
 */
fun parseMovieNfo(xml: String): MovieInfo {
    val root = parseNfoXml(xml).rootElement()

    val title = root.childText("title") ?: throw NfoFormatException("movie NFO has no <title>.")
    val setElement = root.childElements("set").firstOrNull()
    val uniqueIds = root.uniqueIds()
    val bareId = root.childText("id")

    return MovieInfo(
        title = title,
        originalTitle = root.childText("originaltitle"),
        sortTitle = root.childText("sorttitle"),
        plot = root.childText("plot"),
        outline = root.childText("outline"),
        tagline = root.childText("tagline"),
        runtimeMinutes = root.childInt("runtime"),
        year = root.childInt("premiered") ?: root.childInt("year"),
        mpaa = root.childText("mpaa"),
        studios = root.childElements("studio").mapNotNull { it.textContent?.trim() }.filter { it.isNotEmpty() },
        genres = root.childElements("genre").mapNotNull { it.textContent?.trim() }.filter { it.isNotEmpty() },
        tags = root.childElements("tag").mapNotNull { it.textContent?.trim() }.filter { it.isNotEmpty() },
        set = setElement?.childText("name")?.let { SetInfo(it) },
        directors = root.childElements("director").mapNotNull { it.textContent?.trim() }.filter { it.isNotEmpty() },
        writers = root.childElements("writer").mapNotNull { it.textContent?.trim() }.filter { it.isNotEmpty() },
        cast = root.childElements("cast").flatMap { cast ->
            cast.childElements("actor").map { actor ->
                CastMember(name = actor.childText("name") ?: "", role = actor.childText("role"))
            }
        }.filter { it.name.isNotEmpty() },
        imdbId = root.childText("imdbid") ?: uniqueIds["imdb"] ?: bareId?.takeIf { it.startsWith("tt") },
        tmdbId = root.childText("tmdbid") ?: uniqueIds["tmdb"],
        trailer = root.childText("trailer"),
        playcount = root.childInt("playcount") ?: 0,
        intro = parseTimeRange(root.childText("intro")),
        credits = parseTimeRange(root.childText("credits")),
    )
}
