package com.hawkynt.mediadroyd.player

import java.util.Locale

/**
 * A snapshot of the picture on screen, saved as a PNG: what it is called and how the pixels read back from the
 * graphics chip become a picture. The behaviour follows DScaler's stills as its help describes them — the picture as
 * shown, after the filters; a lossless file; an existing file never overwritten — the naming is MediaDroyd's own: the
 * title and where in the video, `Title 01-23-45.png`.
 */
object Snapshot {

    /** The longest a title is kept in a file name, in UTF-16 units: with the position, well within 255 bytes. */
    const val MAX_TITLE = 100

    /** What a title with nothing usable left is called. */
    const val FALLBACK = "Snapshot"

    // What Windows, and therefore exFAT and NTFS sticks, refuses in a name, and the path separators everywhere.
    private const val REFUSED = "\\/:*?\"<>|"

    // Device names Windows keeps whatever the extension: `CON.png` cannot be created there.
    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

    /** The file name for a snapshot of [title] at [positionMs]: `Title HH-MM-SS.png`. */
    fun fileName(title: String, positionMs: Long): String {
        val s = positionMs.coerceAtLeast(0L) / 1000
        // Latin digits whatever the system's language: Arabic or Persian ones would read as a different name.
        return String.format(Locale.ROOT, "%s %02d-%02d-%02d.png", sanitise(title), s / 3600, s / 60 % 60, s % 60)
    }

    /**
     * [title] as a file name takes it on any system: refused and control characters as spaces, runs of spaces as
     * one, no dots or spaces at either end, Windows' device names with an underscore, at most [MAX_TITLE] long.
     */
    fun sanitise(title: String): String {
        val replaced = buildString {
            for (c in title) append(if (c in REFUSED || c.isISOControl()) ' ' else c)
        }
        var name = replaced.split(' ').filter { it.isNotEmpty() }.joinToString(" ").trim('.', ' ')
        if (name.length > MAX_TITLE) {
            var end = MAX_TITLE
            if (Character.isLowSurrogate(name[end])) end--
            name = name.substring(0, end).trimEnd('.', ' ')
        }
        if (name.isEmpty()) return FALLBACK
        return if (name.uppercase() in RESERVED) name + "_" else name
    }

    /** [name], or with ` (2)`, ` (3)` … before its extension, the first that [taken] does not report. */
    fun freeName(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        return generateSequence(2) { it + 1 }.map { "$stem ($it)$extension" }.first { !taken(it) }
    }

    /**
     * RGBA pixels as OpenGL reads them back — rows from the bottom — as a picture stores them, rows from the top, every
     * pixel opaque: a window's alpha is whatever the passes left in it, and a viewer would show that as transparency.
     */
    fun upright(bottomUp: ByteArray, width: Int, height: Int): ByteArray {
        val row = width * 4
        require(bottomUp.size >= row * height) { "$width × $height needs ${row * height} bytes" }
        val out = ByteArray(row * height)
        for (y in 0 until height) System.arraycopy(bottomUp, (height - 1 - y) * row, out, y * row, row)
        for (i in 3 until out.size step 4) out[i] = -1
        return out
    }
}
