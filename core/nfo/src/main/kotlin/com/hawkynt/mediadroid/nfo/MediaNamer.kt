package com.hawkynt.mediadroid.nfo

/** A season/episode pair as it can be read from a file or directory name. */
data class EpisodeRef(val season: Int, val episode: Int)

// The `NxNN` form carries word boundaries and refuses `x264`-style codec junk,
// so `Movie.5.1x264` is not mistaken for episode 264.
internal val episodePattern =
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

private val bareEpisodePattern = Regex("(?i)^ep?(\\d{1,4})(?![0-9])")

/**
 * The episode number of a name that starts with a bare `E2`, `E02`, `EP002` (up to four digits for
 * long-running anime). No season in it: a `Season NN` folder supplies one, and a show without season
 * folders — absolute numbering, as anime often has — puts it in season 1.
 */
fun bareEpisodeFromName(fileName: String): Int? =
    bareEpisodePattern.find(fileName)?.groupValues?.get(1)?.toIntOrNull()

/** An episode a folder's numbering gives a file: its [ref] and, for `… 101 - Title`, the [title]. */
data class NumberedEpisode(val ref: EpisodeRef, val title: String?)

// The first number of up to four digits standing between separators (or at the start or end).
private val numberToken = Regex("(?:^|[._ -])(\\d{1,4})(?=[._ -]|$)")
private val numberedTitle = Regex("^\\s*-\\s*(.*\\S)\\s*$")
private val yearLike = Regex("^(?:19|20)\\d{2}$")
private val separators = Regex("[._ -]+")

/**
 * Episodes in names that carry neither `SxxExx` nor `NxNN`, read from a whole folder at once. The
 * [fileNames] sharing the shortest name in front of their number are the series, and it needs at
 * least two videos; every other name must be a special of it, or none counts. Kodi reads these
 * forms only in sources marked as TV shows; a folder agreeing on them is our stand-in for that mark,
 * so a `Blade Runner 2049` beside `Fahrenheit 451` stays a movie.
 *
 * - Zero-padded numbers (`sr.01.title`, `001 - Pilot`) are absolute numbering, episodes of
 *   [defaultSeason] — Kodi's documented "filenames without season" form.
 * - Otherwise three or four digits are season and episode, the last two digits the episode
 *   (`Show 101 - Title` is S01E01) — Kodi's default `[\/._ -]([0-9]+)([0-9][0-9])([._ -][^\/]*)$`.
 *   Years are no such number.
 * - A name that extends the series' name and carries a number of its own (`sr.le.1.title` beside
 *   `sr.01.title`) is a special: season 0, the Kodi convention.
 *
 * Returns the episodes by file name, in the order given, or null when the folder does not agree.
 */
fun numberedEpisodes(fileNames: List<String>, defaultSeason: Int = 1): Map<String, NumberedEpisode>? {
    data class Token(val name: String, val prefix: String, val digits: String, val rest: String)

    val tokens = fileNames.map { name ->
        val stem = name.substringBeforeLast('.')
        val match = numberToken.find(stem) ?: return null
        val digits = match.groups[1]!!
        Token(
            name = name,
            prefix = stem.substring(0, match.range.first).replace(separators, " ").trim().lowercase(),
            digits = digits.value,
            rest = stem.substring(digits.range.last + 1),
        )
    }
    val seriesPrefix = tokens.minOfOrNull { it.prefix.length }?.let { length ->
        tokens.map { it.prefix }.filter { it.length == length }.distinct().singleOrNull()
    } ?: return null
    val series = tokens.filter { it.prefix == seriesPrefix }
    if (series.size < 2) return null
    val specials = tokens - series.toSet()
    if (specials.any { seriesPrefix.isEmpty() || !it.prefix.startsWith("$seriesPrefix ") }) return null

    if (series.any { it.digits.length < 2 }) return null
    val absolute = series.any { it.digits.startsWith('0') }
    if (!absolute && series.any { it.digits.length < 3 || yearLike.matches(it.digits) }) return null
    return tokens.associate { token ->
        val number = token.digits.toInt()
        val ref = when {
            token !in series -> EpisodeRef(0, number)
            absolute -> EpisodeRef(defaultSeason, number)
            else -> EpisodeRef(number / 100, number % 100)
        }
        token.name to NumberedEpisode(ref, numberedTitle.matchEntire(token.rest)?.groupValues?.get(1))
    }
}
