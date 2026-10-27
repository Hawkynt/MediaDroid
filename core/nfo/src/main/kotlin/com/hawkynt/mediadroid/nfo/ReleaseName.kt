package com.hawkynt.mediadroid.nfo

/** A title as a person would write it, and the year when the name carried one. */
data class CleanName(val title: String, val year: Int?)

/**
 * Strips what release groups and rippers append to movie and show names — resolution, source,
 * codecs, audio layout, language tags, `[tags]`, `-GROUP` suffixes — so a wall shows
 * `The Big Short`, not `The.Big.Short.2015.German.DL.1080p.BluRay.x264-GRP`. Only used where no
 * NFO names the item; an NFO title always wins. Episode titles follow a stricter rule, see
 * [episodeTitle].
 *
 * The junk vocabulary is a hypothesis built from common scene and P2P naming, not a standard:
 * no specification exists for release names. Words that are also ordinary title words are left out
 * on purpose, and the first word of a name is never treated as junk.
 */
object ReleaseName {

    // Every bracket and brace escaped: Android's ICU regex engine rejects a bare `}` that the JVM
    // accepts, and the class would fail to load on a device while every unit test passes.
    private val bracketed = Regex("\\[[^\\]]*\\]|\\{[^\\}]*\\}")
    private val year = Regex("^\\(?((?:19|20)\\d{2})\\)?$")
    private val groupSuffix = Regex("-[A-Z0-9]{2,}$")

    private val junkWords = setOf(
        // resolution and source
        "4k", "8k", "uhd", "web", "webdl", "webrip", "webhd", "hdtv", "pdtv", "dvd", "dvdr", "dvdrip", "dvd5", "dvd9",
        "bdrip", "brrip", "bluray", "blu", "bd", "bd25", "bd50", "remux", "hdrip", "hdcam", "cam", "telesync", "ts",
        "amzn", "nf", "dsnp", "hmax", "atvp", "hulu", "pcok", "itunes",
        // video
        "x264", "x265", "x266", "h264", "h265", "h266", "hevc", "avc", "av1", "divx", "xvid", "vp9", "hdr", "hdr10",
        "hdr10plus", "dv", "dovi", "sdr", "10bit", "8bit", "hi10p",
        // audio
        "atmos", "truehd", "dts", "dtshd", "dtsx", "dtsma", "dd", "ddp", "eac3", "ac3", "aac", "flac", "mp3", "opus",
        "lpcm", "pcm",
        // language and subtitles
        "german", "ger", "deutsch", "english", "eng", "french", "fre", "truefrench", "vff", "vostfr", "ita", "italian",
        "spanish", "spa", "latino", "castellano", "jap", "japanese", "kor", "korean", "rus", "russian", "hindi",
        "nordic", "swedish", "danish", "norwegian", "finnish", "polish", "dutch", "multi", "multisubs", "dual", "dl",
        "ml", "dubbed", "dub", "subbed", "subs", "hardsub",
        // release flags
        "proper", "repack", "rerip", "internal", "remastered", "unrated", "uncut", "readnfo", "sample", "retail",
    )
    private val junkPatterns = listOf(
        Regex("^\\d{3,4}[pi]$"), // 1080p, 576i
        Regex("^\\d{3,4}x\\d{3,4}$"), // 1920x1080
        Regex("^ddp?\\d$"), // DD5, DDP5 (the .1 follows as its own word)
        Regex("^(?:aac|dts)\\d$"),
    )

    /** The title and year in a movie or show folder or file name. */
    fun parse(raw: String): CleanName {
        val words = words(raw)
        if (words.isEmpty()) return CleanName(raw.trim(), null)
        // The year is the last year-looking word (never the first) that ends the name or is
        // followed by release junk — so `Blade Runner 2049 (2017)` keeps 2049 in the title.
        for (i in words.indices.reversed()) {
            if (i == 0) break
            val match = year.matchEntire(words[i]) ?: continue
            val next = words.getOrNull(i + 1)
            if (next == null || isJunk(next) || year.matches(next)) {
                return CleanName(join(words.subList(0, i)), match.groupValues[1].toInt())
            }
        }
        val cut = (1 until words.size).firstOrNull { isJunk(words[it]) } ?: words.size
        return CleanName(join(words.subList(0, cut)), null)
    }

    // `S01E02 - Title`, `1x02 - Title`, `E02 - Title`, `EP002 - Title`: the token opens the name,
    // a hyphen separates.
    private val cleanEpisodeName =
        Regex("(?i)^(?:s\\d{1,2}e\\d{1,3}|\\d{1,2}x\\d{1,3}|ep?\\d{1,4})\\s*-\\s*(.*\\S)\\s*$")

    /**
     * The episode title a file name carries — only in the clean forms `S01E02 - Title`,
     * `1x02 - Title` and `E02 - Title`. Any other name (scene releases, a show name in front, no
     * hyphen) has no title from the name: rename the file or add an episode NFO to give it one.
     */
    fun episodeTitle(stem: String): String? = cleanEpisodeName.matchEntire(stem)?.groupValues?.get(1)

    /**
     * Splits a name into words. Scene names (no spaces) use dots and underscores as separators;
     * names with spaces keep their dots, so `Mr. Robot` stays intact.
     */
    private fun words(raw: String, spaced: Boolean = raw.contains(' ')): List<String> {
        var text = raw.replace(bracketed, " ")
        text = if (spaced) text.replace('_', ' ') else text.replace('.', ' ').replace('_', ' ')
        val words = text.split(' ').map { it.trim() }.filter { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }
            .toMutableList()
        // A trailing `-GROUP` on the last word of a scene name is the release group.
        if (!spaced && words.isNotEmpty()) {
            words[words.lastIndex] = words.last().replace(groupSuffix, "")
        }
        return words
    }

    private fun isJunk(word: String): Boolean {
        val lower = word.lowercase().trim('(', ')', ',')
        if (isJunkPiece(lower)) return true
        // `WEB-DL`, `x264-GRP`, `DTS-HD`: junk when any hyphenated piece is.
        return lower.contains('-') && lower.split('-').any(::isJunkPiece)
    }

    private fun isJunkPiece(piece: String): Boolean = piece in junkWords || junkPatterns.any { it.matches(piece) }

    private fun join(words: List<String>): String = words.joinToString(" ").trim(' ', '-')
}
