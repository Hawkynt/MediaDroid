package com.hawkynt.mediadroid.nfo

/** One movie split over several files, its parts in playing order. */
data class Stack(val title: String, val parts: List<String>)

/**
 * Multi-part movies (`Heat.CD1.avi`, `Heat.CD2.avi`) recognised the way Kodi stacks them. The
 * expressions are Kodi's default `<moviestacking>` entries from the advancedsettings.xml
 * documentation (kodi.wiki/view/Advancedsettings.xml), groups (title)(volume)(ignore)(extension),
 * matched case-insensitively. Kodi's third default, a bare `a`–`d` after the title, is left out:
 * in a flat folder it pairs unrelated films.
 */
object MovieStacking {

    private val expressions = listOf(
        Regex("(?i)(.*?)([ _.-]*(?:cd|dvd|p(?:ar)?t|dis[ck])[ _.-]*[0-9]+)(.*?)(\\.[^.]+)$"),
        Regex("(?i)(.*?)([ _.-]*(?:cd|dvd|p(?:ar)?t|dis[ck])[ _.-]*[a-d])(.*?)(\\.[^.]+)$"),
    )

    /**
     * Groups [fileNames] into stacks: two or more files whose title, trailing part and extension
     * agree and whose volumes differ. Files that stack with nothing are not returned.
     */
    fun stack(fileNames: List<String>): List<Stack> {
        data class Volume(val key: String, val title: String, val volume: String, val name: String)

        val volumes = fileNames.mapNotNull { name ->
            expressions.firstNotNullOfOrNull { it.matchEntire(name) }?.let { m ->
                val (title, volume, ignore, extension) = m.destructured
                Volume("${title.lowercase()}|${ignore.lowercase()}|${extension.lowercase()}", title, volume, name)
            }
        }
        return volumes.groupBy { it.key }
            .values
            .filter { group -> group.size > 1 && group.map { it.volume.lowercase() }.distinct().size == group.size }
            .map { group ->
                val ordered = group.sortedWith(compareBy({ volumeNumber(it.volume) }, { it.name.lowercase() }))
                Stack(group.first().title.trim(' ', '.', '_', '-'), ordered.map { it.name })
            }
    }

    /** `CD2` → 2, `part b` → 2: digits as they are, letters a–d as 1–4. */
    private fun volumeNumber(volume: String): Int {
        val digits = volume.filter { it.isDigit() }
        if (digits.isNotEmpty()) return digits.toInt()
        return volume.trim().last().lowercaseChar() - 'a' + 1
    }
}
