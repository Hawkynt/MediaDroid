package com.hawkynt.mediadroid.nfo

/** A season as a Kodi `season.nfo` in its folder describes it; every field may be missing. */
data class SeasonInfo(val title: String? = null, val plot: String? = null, val number: Int? = null)

/** Reads a `season.nfo` (root `<season>`); the outline stands in for a missing plot. */
fun parseSeasonNfo(xml: String): SeasonInfo {
    val root = parseNfoXml(xml).rootElement()
    return SeasonInfo(
        title = root.childText("title"),
        plot = root.childText("plot") ?: root.childText("outline"),
        number = root.childInt("seasonnumber"),
    )
}
