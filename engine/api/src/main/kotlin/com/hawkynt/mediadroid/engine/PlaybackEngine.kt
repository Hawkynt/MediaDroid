package com.hawkynt.mediadroid.engine

/**
 * The player the screens talk to, the same on every platform: Media3 behind it on Android, FFmpeg on the desktop
 * (README "Platform support"). Its commands and properties are answered on the thread it was built on; [Listener]s
 * hear from it there too, or — an engine that decodes on threads of its own — on those (the screens hand what they
 * hear back to their own thread). What every engine does alike is written down as the contract tests in this
 * module's test fixtures (PlaybackEngineContract), which the fake engine there and each platform's engine pass.
 */
interface PlaybackEngine : AutoCloseable {

    /** The live knobs (see [PlaybackTuning]). */
    val tuning: PlaybackTuning

    /** Opens [request] and, unless [autoPlay] is false, starts it. */
    fun open(request: MediaRequest, autoPlay: Boolean = true)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /** The path of what is open (the last [open]'s [MediaRequest.path]), or null before anything was. */
    val openedPath: String?

    /** Where playback is, in milliseconds. */
    val positionMs: Long

    /** How long what is open lasts, in milliseconds; 0 while unknown. */
    val durationMs: Long

    val isPlaying: Boolean

    /** Where it is with what is open (see [PlaybackState]). */
    val state: PlaybackState

    /**
     * Where the pictures go: one of the platform's outputs this engine takes (see [VideoOutput]), or null for none.
     * Setting one it does not take throws IllegalArgumentException.
     */
    var videoOutput: VideoOutput?

    /** The picture's frames a second, 0 while unknown or without a picture (for stepping frame by frame). */
    val frameRate: Float get() = 0f

    /** The picture of what is open, once known; null before, or without a picture. */
    val videoFormat: VideoFormat? get() = null

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

    /**
     * A second subtitle track shown beside the first, at the top of the picture — a second language to learn by, or
     * the signs beside the dialogue (mpv's secondary-sid): its id, or null for none. Never the track picked for the
     * first; a new video starts without one.
     */
    var secondSubtitle: String?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") value) = Unit

    fun addListener(listener: Listener)

    fun removeListener(listener: Listener)

    /** What the engine tells the screens; every method does nothing unless overridden. */
    interface Listener {
        fun onPlayingChanged(playing: Boolean) {}

        /** Played to the end; [onStateChanged] hears [PlaybackState.ENDED] as well. */
        fun onEnded() {}

        fun onStateChanged(state: PlaybackState) {}

        /** A [seekTo] taken: playback goes on from [positionMs] (the target, kept within what is open). */
        fun onSeeked(positionMs: Long) {}

        /** The first picture of what was opened, or of a new [videoOutput], has reached the output. */
        fun onFirstFrame() {}

        /** The decoder at work for [kind] (Track.Kind.VIDEO or AUDIO), by its own [name]: for the info sheet. */
        fun onDecoder(kind: Track.Kind, name: String) {}

        fun onTracksChanged(tracks: List<Track>) {}

        /** The picture's size and the shape of its pixels (width over height, 1 for square). */
        fun onVideoSize(width: Int, height: Int, pixelAspect: Float) {}

        /** Playback stopped on an error: its [code], one of [Errors]' where it is one of those. */
        fun onError(code: String) {}
    }

    /** The error codes every engine tells alike (Media3's names for them). */
    object Errors {
        /** No decoder for the picture or sound could be started. */
        const val DECODER_INIT_FAILED = "ERROR_CODE_DECODER_INIT_FAILED"

        /** The file could not be opened or read. */
        const val IO = "ERROR_CODE_IO_UNSPECIFIED"
    }
}
