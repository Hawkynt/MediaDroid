package com.hawkynt.mediadroid.nfo

/** A season/episode pair as it can be read from a file or directory name. */
data class EpisodeRef(val season: Int, val episode: Int)

// The `NxNN` form carries word boundaries and refuses `x264`-style codec junk,
// so `Movie.5.1x264` is not mistaken for episode 264.
private val episodePattern =
    Regex("(?i)(?:\\bs(\\d{1,2})\\s*e(\\d{1,3})\\b)|(?:\\b(\\d{1,2})x(?!26[456]\\b)(\\d{1,3})\\b)")

/**
 * Reads `S01E02` or `1x02` out of a file name — the naming convention Kodi and most
 * rip groups follow. Returns null when the name carries no episode reference, which
 * is the signal to fall back to the NFO or to a directory listing.
 */
fun episodeRefFromName(fileName: String): EpisodeRef? {
    val match = episodePattern.find(fileName) ?: return null
    val season = (match.groupValues[1].ifEmpty { match.groupValues[3] }).toIntOrNull() ?: return null
    val episode = (match.groupValues[2].ifEmpty { match.groupValues[4] }).toIntOrNull() ?: return null
    return EpisodeRef(season = season, episode = episode)
}
