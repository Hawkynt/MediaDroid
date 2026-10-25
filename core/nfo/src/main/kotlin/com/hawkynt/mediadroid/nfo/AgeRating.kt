package com.hawkynt.mediadroid.nfo

/**
 * Turns a certification string from an NFO (`mpaa` for movies and shows,
 * `certification` for episodes) into a minimum age in years, so the guarded
 * max-age mode can hide what is too old for the child holding the device.
 *
 * The strings come from whatever scraper wrote the NFO, so several rating
 * systems show up: German FSK ("FSK 16", "ab 16"), MPAA ("PG-13", "Rated R"),
 * US TV ("TV-14", "TV-MA") and bare numbers. Where a system has no exact
 * numeric age (R is 17 in the US, but sits with FSK 18 in spirit; PG is
 * advisory), the mapping rounds to the age a household would recognize.
 * Anything unrecognized yields null — the content stays visible.
 */
fun minAgeOf(certification: String?): Int? {
    val text = certification?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null

    TV_RATINGS[text]?.let { return it }
    Regex("^TV-?(Y7(?:-FV)?|Y|G|PG|14|MA)$").find(text)?.let { return TV_RATINGS[text] }

    Regex("\\bFSK\\D*(\\d{1,2})").find(text)?.let { return it.groupValues[1].toIntOrNull() }
    Regex("\\bAB\\s*(\\d{1,2})\\b").find(text)?.let { return it.groupValues[1].toIntOrNull() }

    // MPAA, longest patterns first so PG-13 wins over PG.
    when {
        Regex("^NC-?17$").matches(text) -> return 18
        Regex("^(RATED\\s+)?PG-?13$").matches(text) -> return 13
        Regex("^(RATED\\s+)?R$").matches(text) -> return 18
        Regex("^(RATED\\s+)?PG$").matches(text) -> return 6
        Regex("^(RATED\\s+)?G$").matches(text) -> return 0
    }

    Regex("^(\\d{1,2})$").find(text)?.let { return it.groupValues[1].toIntOrNull() }
    return null
}

private val TV_RATINGS = mapOf(
    "TV-Y" to 0,
    "TV-Y7" to 7,
    "TV-Y7-FV" to 7,
    "TV-G" to 0,
    "TV-PG" to 7,
    "TV-14" to 14,
    "TV-MA" to 18,
)
