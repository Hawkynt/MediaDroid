package com.hawkynt.mediadroid.engine

/**
 * One track of what is playing, as the screens show it: its [id] (the engine's own, to hand back when picking it),
 * [kind], [language] (ISO 639 as the file names it, or null) and [label] (the file's or sidecar's name for it, or
 * null); [selected] when it is the one playing; [source] the file it comes from — 0 the video itself, n the n-th of
 * the files beside it of its kind ([MediaRequest.audio] for sound, [MediaRequest.subtitles] for subtitles); [second]
 * when it is the subtitle track shown second, at the top ([PlaybackEngine.secondSubtitle]).
 */
data class Track(
    val id: String,
    val kind: Kind,
    val language: String?,
    val label: String?,
    val selected: Boolean = false,
    val source: Int = 0,
    val second: Boolean = false,
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

    /** The subtitle tracks that can show second: every one but the first's ([PlaybackEngine.secondSubtitle]). */
    fun secondText(tracks: List<Track>): List<Track> = of(tracks, Track.Kind.TEXT).filter { !it.selected }

    /** The subtitle track shown second, or null. */
    fun second(tracks: List<Track>): Track? = tracks.firstOrNull { it.kind == Track.Kind.TEXT && it.second }
}
