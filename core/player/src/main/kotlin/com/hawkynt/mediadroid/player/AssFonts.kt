package com.hawkynt.mediadroid.player

/**
 * Which of a file's attachments are fonts for its subtitles: by MIME type, or by extension where the type says
 * nothing — mpv's lists (sub/sd_ass.c), the types muxers write for fonts.
 */
object AssFonts {
    private val mimeTypes = setOf(
        "application/x-truetype-font", "application/vnd.ms-opentype", "application/x-font-ttf",
        "application/x-font", "application/font-sfnt", "font/collection", "font/otf", "font/sfnt", "font/ttf",
    )
    private val extensions = listOf(".ttf", ".ttc", ".otf", ".otc")

    /** Whether the attachment [name] of [mimeType] (null where none is given) is a font. */
    fun isFont(mimeType: String?, name: String): Boolean {
        val type = mimeType.orEmpty().lowercase()
        if (type in mimeTypes) return true
        if (type.isNotEmpty() && type != "application/octet-stream") return false
        return extensions.any { name.lowercase().endsWith(it) }
    }
}
