package com.hawkynt.mediadroid.engine

/**
 * A playback engine that decodes nothing: what is open lasts [length] milliseconds and carries [ownTracks], its clock
 * moves only when [advance] says so, and every command and event behaves as [PlaybackEngineContract] asks of a real
 * engine.
 * For the screens' tests (#286) and as the contract's own reference; [commands] keeps what it was told, in order.
 * A path starting with [UNREADABLE] cannot be opened (told through [PlaybackEngine.Listener.onError]).
 */
class FakePlaybackEngine(
    private val length: Long = 10_000,
    private val ownTracks: List<Track> = listOf(
        Track("v", Track.Kind.VIDEO, null, null, selected = true, format = PICTURE),
        Track("a1", Track.Kind.AUDIO, "eng", "English", selected = true, format = SOUND),
        Track("a2", Track.Kind.AUDIO, "deu", "Deutsch", format = SOUND),
        Track("t1", Track.Kind.TEXT, "eng", "English", format = TrackFormat("application/x-subrip")),
        Track("t2", Track.Kind.TEXT, "deu", "Deutsch", format = TrackFormat("application/x-subrip")),
    ),
    private val videoSize: Pair<Int, Int> = 1920 to 1080,
    override val tuning: PlaybackTuning = PlaybackTuning.Mutable(),
) : PlaybackEngine {

    /** The one kind of picture output this engine takes. */
    class Output : VideoOutput

    /** Every command, as "open <path>", "play", "pause", "seek <ms>", "select <kind> <id>". */
    val commands = mutableListOf<String>()

    private val listeners = mutableListOf<PlaybackEngine.Listener>()
    private var position = 0L
    private var playing = false
    private var opened: MediaRequest? = null
    private var picked = mapOf<Track.Kind, String?>()
    private var second: String? = null
    private var framed = false

    override var openedPath: String? = null
        private set

    override var state: PlaybackState = PlaybackState.IDLE
        private set(value) {
            if (value == field) return
            field = value
            tell { it.onStateChanged(value) }
        }

    override fun open(request: MediaRequest, autoPlay: Boolean) {
        commands += "open ${request.path}"
        openedPath = request.path
        setPlaying(false)
        if (request.path.startsWith(UNREADABLE)) {
            opened = null
            state = PlaybackState.IDLE
            tell { it.onError(PlaybackEngine.Errors.IO) }
            return
        }
        opened = request
        state = PlaybackState.BUFFERING
        position = request.startMs.coerceIn(0, length)
        picked = ownTracks.filter { it.selected }.associate { it.kind to it.id }
        second = null
        framed = false
        state = PlaybackState.READY
        tell {
            it.onTracksChanged(tracks)
            it.onVideoSize(videoSize.first, videoSize.second, 1f)
            it.onDecoder(Track.Kind.VIDEO, "fake-video")
            it.onDecoder(Track.Kind.AUDIO, "fake-audio")
        }
        frame()
        if (autoPlay) play()
    }

    override fun play() {
        commands += "play"
        if (opened == null) return
        if (state == PlaybackState.ENDED) seekTo(0)
        setPlaying(true)
    }

    override fun pause() {
        commands += "pause"
        setPlaying(false)
    }

    override fun seekTo(positionMs: Long) {
        commands += "seek $positionMs"
        if (opened == null) return
        position = positionMs.coerceIn(0, length)
        state = PlaybackState.READY
        tell { it.onSeeked(position) }
    }

    /** Lets [ms] pass: playing, the position moves on (at [speed]) and, reaching the end, it ends. */
    fun advance(ms: Long) {
        if (!playing) return
        position = (position + (ms * speed).toLong()).coerceAtMost(length)
        if (position >= length) {
            setPlaying(false)
            state = PlaybackState.ENDED
            tell { it.onEnded() }
        }
    }

    override val positionMs: Long get() = position

    override val durationMs: Long get() = if (opened == null) 0 else length

    override val isPlaying: Boolean get() = playing

    override var videoOutput: VideoOutput? = null
        set(value) {
            require(value == null || value is Output) { "FakePlaybackEngine takes FakePlaybackEngine.Output only" }
            field = value
            framed = false
            frame()
        }

    override val frameRate: Float get() = if (opened == null) 0f else 25f

    override val videoFormat: VideoFormat?
        get() = if (opened == null) null else VideoFormat(videoSize.first, videoSize.second, frameRate = frameRate)

    override val report: PlaybackReport?
        get() = opened?.let { request ->
            PlaybackReport(
                inputs = (listOf(request.path) + request.subtitles.map { it.path } + request.audio)
                    .map { PlaybackReport.Input(it) },
                video = PlaybackReport.Video(width = videoSize.first, height = videoSize.second, frameRate = frameRate),
                audio = PlaybackReport.Audio(sampleRate = SOUND.sampleRate, channels = SOUND.channels),
                player = "FakePlaybackEngine",
            )
        }

    override var speed: Float = 1f

    override var volume: Float = 1f

    override val tracks: List<Track>
        get() = if (opened == null) {
            emptyList()
        } else {
            ownTracks.map { it.copy(selected = picked[it.kind] == it.id, second = it.id == second) }
        }

    override fun select(kind: Track.Kind, id: String?) {
        commands += "select $kind $id"
        if (kind == Track.Kind.TEXT && id != null && id == second) second = null
        picked = picked + (kind to (id ?: ownTracks.firstOrNull { it.kind == kind && it.selected }?.id))
        tell { it.onTracksChanged(tracks) }
    }

    override var subtitlesOn: Boolean = true

    override var secondSubtitle: String?
        get() = second
        set(value) {
            second = value?.takeIf { id ->
                id != picked[Track.Kind.TEXT] && ownTracks.any { it.kind == Track.Kind.TEXT && it.id == id }
            }
            tell { it.onTracksChanged(tracks) }
        }

    override fun addListener(listener: PlaybackEngine.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: PlaybackEngine.Listener) {
        listeners -= listener
    }

    override fun close() {
        setPlaying(false)
        opened = null
        openedPath = null
        state = PlaybackState.IDLE
        listeners.clear()
    }

    private fun setPlaying(value: Boolean) {
        if (value == playing) return
        playing = value
        tell { it.onPlayingChanged(value) }
    }

    // The first picture reaches an output once something is open and an output is set.
    private fun frame() {
        if (framed || opened == null || videoOutput == null) return
        framed = true
        tell { it.onFirstFrame() }
    }

    private fun tell(event: (PlaybackEngine.Listener) -> Unit) = listeners.toList().forEach(event)

    companion object {
        /** The start of a path this engine cannot open. */
        const val UNREADABLE = "unreadable:"

        /** The picture of what is open, as its tracks carry it. */
        val PICTURE = TrackFormat("video/avc", "avc1.640028", width = 1920, height = 1080, frameRate = 25f)

        /** The sound of what is open, as its tracks carry it. */
        val SOUND = TrackFormat("audio/ac3", channels = 6, sampleRate = 48_000)
    }
}
