package com.hawkynt.mediadroid.nfo

import com.hawkynt.mediadroid.imaging.ImageFormats

/** The art kinds the library displays, in the order a scraper would have written them. */
enum class ArtKind { Poster, Fanart, Banner, ClearLogo, DiscArt }

// Base names in preference order. `folder.jpg` is the cover Kodi shows in a file
// browser, so it is the fallback every media folder is likely to have.
private val baseNamePreferences = mapOf(
    ArtKind.Poster to listOf("poster", "folder", "tvshow-poster", "movie-poster", "cover"),
    ArtKind.Fanart to listOf("fanart", "backdrop", "background", "tvshow-fanart", "movie-backdrop"),
    ArtKind.Banner to listOf("banner", "tvshow-banner"),
    ArtKind.ClearLogo to listOf("clearlogo", "tvshow-clearlogo"),
    ArtKind.DiscArt to listOf("discart", "cdart", "diskart"),
)

/**
 * Picks one image per [ArtKind] from a folder's file names, following the Kodi art
 * conventions: the first entry of [baseNamePreferences] that is present wins, and a
 * numbered variant counts as its base — `poster.jpg` and `poster-1.jpg` are both posters.
 */
fun findArt(fileNames: List<String>): Map<ArtKind, String> {
    val result = HashMap<ArtKind, String>()
    for ((kind, bases) in baseNamePreferences) {
        for (base in bases) {
            val hit = fileNames.firstOrNull { name ->
                val dot = name.lastIndexOf('.')
                if (dot <= 0) return@firstOrNull false
                val stem = name.substring(0, dot).lowercase()
                val extension = name.substring(dot + 1).lowercase()
                // `poster.jpg` matches exactly; `poster-1.jpg` matches as a numbered variant.
                (stem == base || stem.startsWith("$base-")) && extension in ImageFormats.extensions
            }
            if (hit != null) {
                result[kind] = hit
                break
            }
        }
    }
    return result
}
