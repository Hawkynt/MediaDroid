package com.hawkynt.mediadroid.library

/**
 * Subtitle and audio files beside a video that belong to it, the convention Kodi and the other players
 * read: named after the video, with the language and what kind of track it is between the name and the
 * extension — `Heat (1995).de.srt`, `Heat (1995).en.forced.ass`, `Heat (1995).en.sdh.vtt`,
 * `Heat (1995).de.ac3`. The language is an ISO 639 code (`de`, `ger`, `deu`); `forced` marks forced
 * subtitles, `sdh`, `cc` and `hi` subtitles for the hard of hearing, anything else is a label.
 */
object Sidecars {

    enum class Kind { SUBTITLES, AUDIO }

    data class Sidecar(
        val name: String,
        val kind: Kind,
        /** ISO 639-1 code, or null when the name gives no language. */
        val language: String?,
        /** The subtitle format's MIME type (Media3's names); null for audio, which is recognised by content. */
        val mimeType: String?,
        val forced: Boolean = false,
        val hearingImpaired: Boolean = false,
        /** Any other word between the name and the extension, as `commentary`. */
        val label: String? = null,
    ) {
        /** How a track picker shows it: `DE · forced`, else the file's name. */
        fun describe(): String {
            val parts = listOfNotNull(
                language?.let(Languages::label),
                "forced".takeIf { forced },
                "for the hard of hearing".takeIf { hearingImpaired },
                label,
            )
            return if (parts.isEmpty()) name else parts.joinToString(" · ")
        }
    }

    /** Subtitle formats by extension, as Media3 names them. */
    private val SUBTITLES = mapOf(
        "srt" to "application/x-subrip",
        "ass" to "text/x-ssa",
        "ssa" to "text/x-ssa",
        "vtt" to "text/vtt",
        "ttml" to "application/ttml+xml",
        "dfxp" to "application/ttml+xml",
    )

    private val AUDIO = setOf("ac3", "eac3", "ec3", "dts", "aac", "m4a", "mka", "mp3", "flac", "opus", "ogg", "wav")

    private val HEARING_IMPAIRED = setOf("sdh", "cc", "hi")

    /** The sidecars of the video [videoName] among the file names of its folder: subtitles first, each by name. */
    fun of(videoName: String, siblings: List<String>): List<Sidecar> {
        val stem = videoName.substringBeforeLast('.')
        return siblings.mapNotNull { name ->
            if (name == videoName || !name.startsWith("$stem.", ignoreCase = true)) return@mapNotNull null
            val extension = name.substringAfterLast('.').lowercase()
            val kind = when {
                extension in SUBTITLES -> Kind.SUBTITLES
                extension in AUDIO -> Kind.AUDIO
                else -> return@mapNotNull null
            }
            val words = name.substring(stem.length + 1, name.length - extension.length).trim('.').split('.')
                .map { it.lowercase() }
                .filter { it.isNotEmpty() }
            val language = words.firstNotNullOfOrNull { Languages.twoLetter(it) }
            Sidecar(
                name = name,
                kind = kind,
                language = language,
                mimeType = SUBTITLES[extension],
                forced = "forced" in words,
                hearingImpaired = words.any { it in HEARING_IMPAIRED },
                label = words.filter { Languages.twoLetter(it) == null && it != "forced" && it !in HEARING_IMPAIRED }
                    .joinToString(" ")
                    .ifEmpty { null },
            )
        }.sortedWith(compareBy<Sidecar> { it.kind }.thenBy(NaturalOrder) { it.name })
    }
}
