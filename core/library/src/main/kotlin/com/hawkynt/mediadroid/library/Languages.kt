package com.hawkynt.mediadroid.library

/**
 * Display for stream languages: a flag and a short label. NFOs carry ISO 639-2 codes in either the
 * bibliographic (`ger`) or terminology (`deu`) form, players report ISO 639-1 (`de`), sometimes
 * with a region (`de-AT`); all of them land on the same language here. Which flag stands for a
 * language is a display choice, not a standard — the country most viewers associate with it.
 */
object Languages {

    // ISO 639-2 B and T codes → ISO 639-1, for the languages releases commonly carry.
    private val toTwoLetter = mapOf(
        "eng" to "en", "ger" to "de", "deu" to "de", "fre" to "fr", "fra" to "fr", "spa" to "es", "ita" to "it",
        "por" to "pt", "dut" to "nl", "nld" to "nl", "swe" to "sv", "nor" to "no", "nob" to "no", "dan" to "da",
        "fin" to "fi", "pol" to "pl", "cze" to "cs", "ces" to "cs", "slo" to "sk", "slk" to "sk", "hun" to "hu",
        "rum" to "ro", "ron" to "ro", "gre" to "el", "ell" to "el", "tur" to "tr", "rus" to "ru", "ukr" to "uk",
        "jpn" to "ja", "kor" to "ko", "chi" to "zh", "zho" to "zh", "ara" to "ar", "heb" to "he", "hin" to "hi",
        "tha" to "th", "vie" to "vi", "ind" to "id", "hrv" to "hr", "srp" to "sr", "bul" to "bg", "ice" to "is",
        "isl" to "is", "est" to "et", "lav" to "lv", "lit" to "lt", "slv" to "sl", "per" to "fa", "fas" to "fa",
    )

    // ISO 639-1 → ISO 3166-1 alpha-2 of the flag shown.
    private val flagCountry = mapOf(
        "en" to "GB", "de" to "DE", "fr" to "FR", "es" to "ES", "it" to "IT", "pt" to "PT", "nl" to "NL",
        "sv" to "SE", "no" to "NO", "da" to "DK", "fi" to "FI", "pl" to "PL", "cs" to "CZ", "sk" to "SK",
        "hu" to "HU", "ro" to "RO", "el" to "GR", "tr" to "TR", "ru" to "RU", "uk" to "UA", "ja" to "JP",
        "ko" to "KR", "zh" to "CN", "ar" to "SA", "he" to "IL", "hi" to "IN", "th" to "TH", "vi" to "VN",
        "id" to "ID", "hr" to "HR", "sr" to "RS", "bg" to "BG", "is" to "IS", "et" to "EE", "lv" to "LV",
        "lt" to "LT", "sl" to "SI", "fa" to "IR",
    )

    /** The ISO 639-1 code for [code], or null when the language is not one we know. */
    fun twoLetter(code: String): String? {
        val base = code.trim().lowercase().substringBefore('-').substringBefore('_')
        return when (base.length) {
            2 -> base.takeIf { it in flagCountry }
            3 -> toTwoLetter[base]
            else -> null
        }
    }

    /** The flag emoji for [code], or null for an unknown or undetermined language. */
    fun flag(code: String): String? {
        val country = twoLetter(code)?.let { flagCountry[it] } ?: return null
        return country.map { String(Character.toChars(REGIONAL_INDICATOR_A + (it - 'A'))) }.joinToString("")
    }

    /** A short label: `DE` for any German code, the code itself in capitals otherwise. */
    fun label(code: String): String = (twoLetter(code) ?: code.trim()).uppercase()

    private const val REGIONAL_INDICATOR_A = 0x1F1E6
}
