package com.hawkynt.mediadroid.nfo

/** A single episode as described by an episode NFO (named after its video file). */
data class EpisodeInfo(
    val showTitle: String?,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val plot: String? = null,
    val outline: String? = null,
    val firstAired: String? = null,
    val directors: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val guests: List<CastMember> = emptyList(),
    val imdbId: String? = null,
    val showImdbId: String? = null,
)

/**
 * Reads an episode NFO. Season and episode numbers may be absent here — the file
 * name usually carries them, see [episodeRefFromName].
 */
fun parseEpisodeNfo(xml: String): EpisodeInfo {
    val root = parseNfoXml(xml).rootElement()

    val title = root.childText("title") ?: throw NfoFormatException("episode NFO has no <title>.")

    return EpisodeInfo(
        showTitle = root.childText("showtitle"),
        title = title,
        season = root.childInt("season"),
        episode = root.childInt("episode"),
        plot = root.childText("plot"),
        outline = root.childText("outline"),
        firstAired = root.childText("aired"),
        directors = root.childElements("director").mapNotNull { it.textContent?.trim() }.filter { it.isNotEmpty() },
        studios = root.childElements("studio").mapNotNull { it.textContent?.trim() }.filter { it.isNotEmpty() },
        guests = root.childElements("gueststars").flatMap { guests ->
            guests.childElements("actor").map { actor ->
                CastMember(name = actor.childText("name") ?: "", role = actor.childText("role"))
            }
        }.filter { it.name.isNotEmpty() },
        imdbId = root.childText("imdbid"),
        showImdbId = root.childText("showid"),
    )
}
