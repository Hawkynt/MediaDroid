package com.hawkynt.mediadroid.library

/**
 * A folder settings can be kept for: one of the household's sources ([source], its id; empty for a place no source
 * holds) and a folder in it ([folder], from the source's root, `/` between folders, empty for the whole source).
 */
data class FolderPlace(val source: String, val folder: String) {
    /** The place as a key in a stored map: `source|folder`. */
    val key: String get() = "$source|$folder"

    /** Whether [other] is this folder or lies inside it, folder by folder: `VHS` holds `VHS/1990`, not `VHS-rips`. */
    fun holds(other: FolderPlace): Boolean =
        source == other.source && (folder.isEmpty() || other.folder == folder || other.folder.startsWith("$folder/"))

    companion object {
        /** The place [key] names (see [FolderPlace.key]); null when it names none. */
        fun of(key: String): FolderPlace? {
            val bar = key.indexOf('|')
            if (bar < 0) return null
            return FolderPlace(key.substring(0, bar), key.substring(bar + 1))
        }
    }
}

/**
 * Settings kept per folder or share — the picture a folder of VHS rips starts with — and which one a video gets:
 * the nearest folder above it that has one, so a folder's own beats its share's. What the video was given itself
 * beats them all, and the household's default comes last ([resolve]).
 */
object FolderDefaults {

    /**
     * The folder [location] (a video's, or a folder's own location) lies in: under the source of [sources] whose root
     * holds it, from that root; a place no source holds by its whole folder path. Windows' backslashes count as `/`.
     */
    fun placeOf(sources: List<Source>, location: String, isFolder: Boolean = false): FolderPlace {
        fun normal(path: String) = path.replace('\\', '/').trimEnd('/')
        val at = normal(location)
        val folder = if (isFolder) at else at.substringBeforeLast('/', "")
        val source = sources
            .filter { normal(it.rootUri).let { root -> at == root || at.startsWith("$root/") } }
            .maxByOrNull { it.rootUri.length }
            ?: return FolderPlace("", folder)
        return FolderPlace(source.id, folder.removePrefix(normal(source.rootUri)).trim('/'))
    }

    /** The nearest of [defaults] holding [place] — the deepest folder above it that has one — or null. */
    fun <T> nearest(defaults: Map<FolderPlace, T>, place: FolderPlace): Pair<FolderPlace, T>? = defaults.entries
        .filter { it.key.holds(place) }
        .maxByOrNull { if (it.key.folder.isEmpty()) -1 else it.key.folder.length }
        ?.let { it.key to it.value }

    /** What a video gets: its [own] choice, else its folder's ([folder]), else the [household]'s. */
    fun <T> resolve(own: T?, folder: T?, household: T): T = own ?: folder ?: household

    /** The place as it is shown: the source's name, then the folders, `NAS › Films › VHS`. */
    fun label(place: FolderPlace, sources: List<Source>): String {
        val name = sources.firstOrNull { it.id == place.source }?.name
        val folders = place.folder.split('/').filter { it.isNotEmpty() }
        return (listOfNotNull(name) + folders).joinToString(" › ").ifEmpty { "/" }
    }
}
