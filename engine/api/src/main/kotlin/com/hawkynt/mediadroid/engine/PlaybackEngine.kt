package com.hawkynt.mediadroid.engine

/**
 * The player the screens talk to, the same on every platform: Media3 behind it on Android, FFmpeg on the desktop
 * (README "Platform support"). Its commands and properties are answered on the thread it was built on; [Listener]s
 * hear from it there too.
 */
interface PlaybackEngine : AutoCloseable {

    /** The live knobs (see [PlaybackTuning]). */
    val tuning: PlaybackTuning

    /** Opens [request] and, unless [autoPlay] is false, starts it. */
    fun open(request: MediaRequest, autoPlay: Boolean = true)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /** Where playback is, in milliseconds. */
    val positionMs: Long

    /** How long what is open lasts, in milliseconds; 0 while unknown. */
    val durationMs: Long

    val isPlaying: Boolean

    /** Playback speed, 1 for normal. */
    var speed: Float

    /** The engine's own volume, 0..1, apart from the system's. */
    var volume: Float

    /** Every track of what is open, with the playing ones [Track.selected]. */
    val tracks: List<Track>

    /** Plays the track with [id] for its kind, or — [id] null — goes back to the file's own choice for [kind]. */
    fun select(kind: Track.Kind, id: String?)

    /** Subtitles shown at all, whichever track. */
    var subtitlesOn: Boolean

    fun addListener(listener: Listener)

    fun removeListener(listener: Listener)

    /** What the engine tells the screens; every method does nothing unless overridden. */
    interface Listener {
        fun onPlayingChanged(playing: Boolean) {}

        fun onEnded() {}

        fun onTracksChanged(tracks: List<Track>) {}

        /** The picture's size and the shape of its pixels (width over height, 1 for square). */
        fun onVideoSize(width: Int, height: Int, pixelAspect: Float) {}

        fun onError(code: String) {}
    }
}
