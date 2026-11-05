package com.hawkynt.mediadroid.engine

/**
 * One track of what is playing, as the screens show it: its [id] (the engine's own, to hand back when picking it),
 * [kind], [language] (ISO 639 as the file names it, or null) and [label] (the file's or sidecar's name for it, or
 * null); [selected] when it is the one playing.
 */
data class Track(
    val id: String,
    val kind: Kind,
    val language: String?,
    val label: String?,
    val selected: Boolean = false,
) {
    enum class Kind { AUDIO, TEXT, VIDEO }
}

/** Rules over a list of tracks that every engine shares. */
object Tracks {

    /** The tracks of [kind], in their order. */
    fun of(tracks: List<Track>, kind: Track.Kind): List<Track> = tracks.filter { it.kind == kind }

    /** The first subtitle track in the household's [language] (Settings › Subtitles), or null where none is. */
    fun preferredText(tracks: List<Track>, language: String): Track? =
        if (language.isEmpty()) null else of(tracks, Track.Kind.TEXT).firstOrNull { it.language == language }
}
