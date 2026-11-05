package com.hawkynt.mediadroid.player

/**
 * Subtitle text as it is shown, from an ASS event — the form FFmpeg hands every text subtitle in (SubRip, WebVTT, ASS,
 * MOV text …): `ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text`, the text with ASS override tags.
 */
object SubtitleText {

    private val fields = Regex("""^\d+,-?\d+,[^,]*,[^,]*,-?\d+,-?\d+,-?\d+,[^,]*,""")
    private val drawing = Regex("""\{[^}]*\\p[1-9][^}]*}.*?(\{[^}]*\\p0[^}]*}|$)""")
    private val tags = Regex("""\{[^}]*}""")

    /**
     * The text of [event]: its eight fields dropped, drawings (`\p1` … `\p0`) left out, override tags (`{…}`)
     * removed, `\N` and `\n` as new lines, `\h` as a space; trimmed.
     */
    fun fromAssEvent(event: String): String {
        val text = event.replaceFirst(fields, "")
        return text.replace(drawing, "")
            .replace(tags, "")
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", " ")
            .lines().joinToString("\n") { it.trim() }
            .trim()
    }
}
